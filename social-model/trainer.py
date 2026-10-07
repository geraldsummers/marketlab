#!/usr/bin/env python3
"""Frozen chronological search and CPU shadow scorer for social attention models."""

from __future__ import annotations

import argparse
import hashlib
import json
import math
import pickle
import random
import time
from datetime import datetime
from dataclasses import asdict, dataclass
from pathlib import Path

import numpy as np
import torch
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import ElasticNet
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import OneHotEncoder, StandardScaler
from sklearn.compose import ColumnTransformer

DAY = 86_400_000
TARGETS = {
    "return_15m": "next15mReturn",
    "variance_1h": "next1hRealizedVariance",
    "return_1d": "next1dReturn",
    "variance_1d": "next1dRealizedVariance",
}
MARKET_FEATURES = {
    "latest_return", "btc_latest_return", "log_rv_1h", "log_rv_24h",
    "hour_sin", "hour_cos", "day_sin", "day_cos",
}


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_rows(path: Path, target: str, require_label: bool = True, expected_names=None):
    rows = []
    with path.open() as handle:
        for line in handle:
            row = json.loads(line)
            label = row.get(TARGETS[target])
            daily_eligible = not target.endswith("_1d") or row["decisionTimeEpochMillis"] % DAY == 0
            if daily_eligible and (not require_label or (label is not None and math.isfinite(label))):
                rows.append(row)
    rows.sort(key=lambda row: (row["decisionTimeEpochMillis"], row["symbol"]))
    if not rows:
        raise ValueError(f"target {target} emitted no rows")
    symbols = np.asarray([row["symbol"] for row in rows], dtype=object)
    if expected_names is None:
        numeric_names = sorted(rows[0]["features"])
        symbol_names = [f"symbol_{symbol}" for symbol in sorted(set(symbols.tolist()))]
    else:
        numeric_names = [name for name in expected_names if not name.startswith("symbol_")]
        symbol_names = [name for name in expected_names if name.startswith("symbol_")]
        if any(set(row["features"]) != set(numeric_names) for row in rows):
            raise ValueError("feature schema mismatch")
        if any(f"symbol_{symbol}" not in symbol_names for symbol in symbols):
            raise ValueError("unknown symbol in feature rows")
    names = numeric_names + symbol_names
    x = np.asarray([
        [row["features"][name] for name in numeric_names] +
        [1.0 if row["symbol"] == name.removeprefix("symbol_") else 0.0 for name in symbol_names]
        for row in rows
    ], dtype=np.float64)
    y = np.asarray([
        np.nan if row.get(TARGETS[target]) is None else row[TARGETS[target]]
        for row in rows
    ], dtype=np.float64)
    if require_label and target.startswith("variance"):
        y = np.log(np.maximum(y, 1e-12))
    times = np.asarray([row["decisionTimeEpochMillis"] for row in rows], dtype=np.int64)
    return rows, names, x, symbols, y, times


def folds(times: np.ndarray, horizon: int):
    first = int(times.min()) + 60 * DAY
    boundaries = [(first + i * 30 * DAY, first + (i + 1) * 30 * DAY) for i in range(6)]
    result = []
    for start, end in boundaries:
        train = np.where(times < start - horizon)[0]
        test = np.where((times >= start) & (times < end))[0]
        if len(train) and len(test):
            result.append((train, test))
    if len(result) != 6:
        raise ValueError(f"expected six complete outer folds, found {len(result)}")
    return result


def loss(target: str, actual: np.ndarray, prediction: np.ndarray) -> np.ndarray:
    if target.startswith("variance"):
        forecast = np.exp(np.clip(prediction, -30.0, 30.0))
        realized = np.exp(actual)
        return realized / forecast + np.log(forecast)
    return np.square(actual - prediction)


class TorchRegressor:
    BATCH_SIZE = 4_096

    def __init__(self, family: str, width: int, learning_rate: float, epochs: int, seed: int):
        self.family, self.width, self.learning_rate, self.epochs, self.seed = family, width, learning_rate, epochs, seed
        self.mean = self.scale = self.model = None

    def fit(self, x: np.ndarray, y: np.ndarray):
        torch.manual_seed(self.seed)
        device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
        self.mean = x.mean(axis=0)
        self.scale = np.where(x.std(axis=0) == 0.0, 1.0, x.std(axis=0))
        if self.family == "temporal_convolution":
            model = torch.nn.Sequential(
                torch.nn.Unflatten(1, (1, x.shape[1])),
                torch.nn.Conv1d(1, self.width, kernel_size=3, padding=1),
                torch.nn.GELU(), torch.nn.AdaptiveAvgPool1d(1), torch.nn.Flatten(),
                torch.nn.Linear(self.width, 1),
            )
        else:
            model = GruHead(x.shape[1], self.width)
        model = model.to(device)
        optimizer = torch.optim.AdamW(model.parameters(), lr=self.learning_rate, weight_decay=1e-4)
        model.train()
        for _ in range(self.epochs):
            optimizer.zero_grad()
            for start in range(0, len(x), self.BATCH_SIZE):
                stop = min(start + self.BATCH_SIZE, len(x))
                values = torch.as_tensor(
                    (x[start:stop] - self.mean) / self.scale,
                    dtype=torch.float32,
                    device=device,
                )
                labels = torch.as_tensor(y[start:stop, None], dtype=torch.float32, device=device)
                error = torch.nn.functional.mse_loss(model(values), labels)
                (error * ((stop - start) / len(x))).backward()
            optimizer.step()
        self.model = model.cpu().eval()
        if device.type == "cuda":
            torch.cuda.empty_cache()
        return self

    def predict(self, x: np.ndarray) -> np.ndarray:
        predictions = []
        with torch.no_grad():
            for start in range(0, len(x), self.BATCH_SIZE):
                values = torch.as_tensor(
                    (x[start:start + self.BATCH_SIZE] - self.mean) / self.scale,
                    dtype=torch.float32,
                )
                predictions.append(self.model(values).numpy().reshape(-1))
        return np.concatenate(predictions).astype(np.float64)


class GruHead(torch.nn.Module):
    def __init__(self, features: int, width: int):
        super().__init__()
        self.gru = torch.nn.GRU(1, width, batch_first=True)
        self.output = torch.nn.Linear(width, 1)

    def forward(self, values):
        encoded, _ = self.gru(values.unsqueeze(-1))
        return self.output(encoded[:, -1, :])


def config_for(family: str, trial: int):
    rng = random.Random(19_870_403 + trial)
    if family == "elastic_net":
        return {"alpha": 10 ** rng.uniform(-7, -2), "l1_ratio": rng.choice([0.0, 0.1, 0.5, 0.9, 1.0])}
    if family == "gradient_boosted_trees":
        return {"learning_rate": 10 ** rng.uniform(-2, -0.5), "max_leaf_nodes": rng.choice([7, 15, 31]), "l2_regularization": 10 ** rng.uniform(-4, 1)}
    return {"width": rng.choice([8, 16, 32]), "learning_rate": 10 ** rng.uniform(-4, -2), "epochs": rng.choice([8, 12, 16])}


def estimator(family: str, config: dict, seed: int):
    if family == "elastic_net":
        return Pipeline([("scale", StandardScaler()), ("model", ElasticNet(max_iter=20_000, random_state=seed, **config))])
    if family == "gradient_boosted_trees":
        return HistGradientBoostingRegressor(random_state=seed, max_iter=200, **config)
    return TorchRegressor(family, seed=seed, **config)


def baseline(x: np.ndarray, symbols: np.ndarray, names: list[str]):
    market = [index for index, name in enumerate(names) if name in MARKET_FEATURES]
    design = np.column_stack([x[:, market], symbols])
    transform = ColumnTransformer([("numeric", StandardScaler(), list(range(len(market)))), ("symbol", OneHotEncoder(handle_unknown="ignore"), [len(market)])])
    return Pipeline([("transform", transform), ("model", ElasticNet(alpha=1e-5, l1_ratio=0.1, max_iter=20_000))]), design


def baseline_predictions(target, x, symbols, names, y, train_index, test_index):
    dynamic, design = baseline(x, symbols, names)
    dynamic.fit(design[train_index], y[train_index])
    predictions = {"market_elastic_net": dynamic.predict(design[test_index])}
    if target.startswith("variance"):
        predictions["historical_mean"] = np.full(len(test_index), float(np.mean(y[train_index])))
        predictions["latest_variance"] = x[test_index, names.index("log_rv_1h")]
    else:
        predictions["zero_return"] = np.zeros(len(test_index))
        predictions["latest_return"] = x[test_index, names.index("latest_return")]
    return predictions, dynamic, design


def strongest_improvement(target, actual, candidate, controls):
    candidate_loss = loss(target, actual, candidate)
    control_losses = {name: loss(target, actual, values) for name, values in controls.items()}
    strongest_name = min(control_losses, key=lambda name: float(np.mean(control_losses[name])))
    return float(np.mean(control_losses[strongest_name] - candidate_loss)), strongest_name


@dataclass
class Trial:
    target: str
    family: str
    trial: int
    configuration: dict
    innerFoldImprovements: list[float]
    positiveInnerFolds: int
    meanInnerImprovement: float
    eligible: bool


def train(args):
    feature_path = Path(args.features)
    feature_manifest_path = Path(args.feature_manifest)
    feature_lock_path = Path(args.feature_lock)
    feature_manifest = json.loads(feature_manifest_path.read_text())
    if feature_manifest.get("schemaVersion") != "marketlab.social-functional-feature-manifest.v1":
        raise ValueError("unsupported functional feature manifest")
    if feature_manifest.get("outputSha256") != sha256(feature_path):
        raise ValueError("feature rows differ from their immutable manifest")
    if feature_manifest.get("featureLockSha256") != sha256(feature_lock_path):
        raise ValueError("feature lock differs from the feature manifest")
    lock_path = Path(args.search_lock)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=True)
    if (output / "frozen-models.json").exists():
        raise ValueError("frozen model manifest already exists")
    lock = json.loads(lock_path.read_text())
    maximum = int(lock["maximumTrialsPerFamilyAndTarget"])
    if args.trials < 1 or args.trials > maximum:
        raise ValueError(f"trials must be within 1..{maximum}")
    if args.trials != maximum and not args.allow_test_trials:
        raise ValueError("production search must use the locked maximum trial count")
    families = lock["families"]
    ledger, winners = [], []
    for target, horizon in (("return_15m", 15 * 60_000), ("variance_1h", 60 * 60_000), ("return_1d", DAY), ("variance_1d", DAY)):
        rows, names, x, symbols, y, times = load_rows(feature_path, target)
        development_end = int(datetime.fromisoformat(lock["developmentPeriod"]["endExclusive"].replace("Z", "+00:00")).timestamp() * 1000)
        if int(times.max()) >= development_end:
            raise ValueError("training features enter the locked blind-extension period")
        split = folds(times, horizon)
        target_trials = []
        for family in families:
            for number in range(args.trials):
                from budget import record_trial
                record_trial()
                config = config_for(family, number)
                improvements = []
                for fold, (train_index, test_index) in enumerate(split):
                    outer_start = int(times[test_index].min())
                    inner_start = outer_start - 30 * DAY
                    inner_train = train_index[times[train_index] < inner_start - horizon]
                    inner_test = train_index[(times[train_index] >= inner_start) & (times[train_index] < outer_start)]
                    if not len(inner_train) or not len(inner_test):
                        raise ValueError("nested validation emitted an empty fold")
                    model = estimator(family, config, args.seed + fold).fit(x[inner_train], y[inner_train])
                    controls, _, _ = baseline_predictions(target, x, symbols, names, y, inner_train, inner_test)
                    improvement, _ = strongest_improvement(target, y[inner_test], model.predict(x[inner_test]), controls)
                    improvements.append(improvement)
                trial = Trial(target, family, number, config, improvements, sum(value > 0 for value in improvements), float(np.mean(improvements)), sum(value > 0 for value in improvements) >= 4)
                ledger.append(asdict(trial)); target_trials.append(trial)
        eligible = [trial for trial in target_trials if trial.eligible]
        winner = max(eligible or target_trials, key=lambda trial: (trial.meanInnerImprovement, -trial.trial))
        asset_gains = {symbol: 0.0 for symbol in sorted(set(symbols.tolist()))}
        outer_improvements = []
        for fold, (train_index, test_index) in enumerate(split):
            audit_model = estimator(winner.family, winner.configuration, args.seed + fold).fit(x[train_index], y[train_index])
            candidate_prediction = audit_model.predict(x[test_index])
            controls, _, _ = baseline_predictions(target, x, symbols, names, y, train_index, test_index)
            strongest = min(controls, key=lambda name: float(np.mean(loss(target, y[test_index], controls[name]))))
            differential = loss(target, y[test_index], controls[strongest]) - loss(target, y[test_index], candidate_prediction)
            outer_improvements.append(float(np.mean(differential)))
            for symbol in asset_gains:
                mask = symbols[test_index] == symbol
                if np.any(mask): asset_gains[symbol] += float(np.sum(differential[mask]))
        positive_gain = sum(max(value, 0.0) for value in asset_gains.values())
        dominance = max((max(value, 0.0) / positive_gain for value in asset_gains.values()), default=1.0) if positive_gain > 0 else 1.0
        final_model = estimator(winner.family, winner.configuration, args.seed).fit(x, y)
        fitted = final_model.predict(x)
        residual_std = float(np.std(y - fitted))
        artifact = output / f"{target}.pkl"
        all_index = np.arange(len(y))
        _, final_baseline, _ = baseline_predictions(target, x, symbols, names, y, all_index, all_index[:1])
        artifact.write_bytes(pickle.dumps({"model": final_model, "baseline": final_baseline, "features": names, "target": target, "trainingMean": float(np.mean(y))}, protocol=5))
        winners.append({
            "modelId": f"attention-{target}-v1", "target": target, "family": winner.family,
            "configuration": winner.configuration, "featureRowsSha256": sha256(feature_path),
            "searchLockSha256": sha256(lock_path), "modelArtifact": artifact.name,
            "modelArtifactSha256": sha256(artifact), "selectedAtEpochMillis": int(time.time() * 1000),
            "innerFoldImprovements": winner.innerFoldImprovements,
            "outerFoldImprovements": outer_improvements, "positiveOuterFolds": sum(value > 0 for value in outer_improvements),
            "engineeringEligible": sum(value > 0 for value in outer_improvements) >= 4 and dominance <= 0.5,
            "trainingResidualStd": residual_std,
            "singleAssetPositiveGainShare": dominance, "outerAssetGainSums": asset_gains,
        })
    ledger_path = output / "trial-ledger.json"
    ledger_path.write_text(json.dumps({"schemaVersion": "marketlab.social-trial-ledger.v1", "trials": ledger}, indent=2, sort_keys=True) + "\n")
    manifest = {
        "schemaVersion": "marketlab.social-frozen-model-set.v1", "classification": "RETROSPECTIVE_EXPLORATION",
        "featureRowsSha256": sha256(feature_path), "searchLockSha256": sha256(lock_path),
        "featureManifestSha256": sha256(feature_manifest_path), "featureLockSha256": sha256(feature_lock_path),
        "trialLedgerSha256": sha256(ledger_path), "models": winners,
    }
    frozen_path = output / "frozen-models.json"
    frozen_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n")
    (output / "frozen-models.sha256").write_text(f"{sha256(frozen_path)}  frozen-models.json\n")


def hac_standard_error(values: np.ndarray, lag: int) -> float:
    centered = values - np.mean(values)
    size = len(values)
    variance = float(np.dot(centered, centered) / size)
    for offset in range(1, min(lag, size - 1) + 1):
        covariance = float(np.dot(centered[offset:], centered[:-offset]) / size)
        variance += 2.0 * (1.0 - offset / (lag + 1.0)) * covariance
    return math.sqrt(max(variance, 0.0) / size)


def holm(p_values):
    order = sorted(range(len(p_values)), key=lambda index: p_values[index])
    adjusted = [1.0] * len(p_values); running = 0.0
    for rank, index in enumerate(order):
        running = max(running, p_values[index] * (len(p_values) - rank))
        adjusted[index] = min(running, 1.0)
    return adjusted


def evaluate(args):
    frozen_path = Path(args.frozen)
    if sha256(frozen_path) != args.frozen_sha256:
        raise ValueError("frozen model-set manifest differs from the externally pinned hash")
    manifest = json.loads(frozen_path.read_text())
    if sha256(Path(args.search_lock)) != manifest["searchLockSha256"]:
        raise ValueError("search lock differs from frozen model set")
    ledger = frozen_path.parent / "trial-ledger.json"
    if sha256(ledger) != manifest["trialLedgerSha256"]:
        raise ValueError("trial ledger differs from frozen model set")
    feature_path = Path(args.features)
    results, p_values = [], []
    for model_info in manifest["models"]:
        artifact = frozen_path.parent / model_info["modelArtifact"]
        if sha256(artifact) != model_info["modelArtifactSha256"]:
            raise ValueError(f"model artifact hash mismatch: {artifact}")
        stored = pickle.loads(artifact.read_bytes())
        target = model_info["target"]
        rows, names, x, symbols, y, times = load_rows(feature_path, target, expected_names=stored["features"])
        candidate = stored["model"].predict(x)
        market = [index for index, name in enumerate(names) if name in MARKET_FEATURES]
        design = np.column_stack([x[:, market], symbols])
        controls = {"market_elastic_net": stored["baseline"].predict(design)}
        if target.startswith("variance"):
            controls["historical_mean"] = np.full(len(y), stored["trainingMean"])
            controls["latest_variance"] = x[:, names.index("log_rv_1h")]
        else:
            controls["zero_return"] = np.zeros(len(y))
            controls["latest_return"] = x[:, names.index("latest_return")]
        improvement, strongest = strongest_improvement(target, y, candidate, controls)
        candidate_loss = loss(target, y, candidate)
        control_loss = loss(target, y, controls[strongest])
        differences = control_loss - candidate_loss
        by_time = []
        for decision in sorted(set(times.tolist())):
            by_time.append(float(np.mean(differences[times == decision])))
        standard_error = hac_standard_error(np.asarray(by_time), 96 if target == "return_15m" else 24 if target == "variance_1h" else 7)
        z = improvement / standard_error if standard_error > 0 else 0.0
        p_value = min(1.0, math.erfc(abs(z) / math.sqrt(2.0)))
        per_asset = {symbol: float(np.mean(differences[symbols == symbol])) for symbol in sorted(set(symbols.tolist()))}
        positive_assets = sum(value > 0.0 for value in per_asset.values())
        p_values.append(p_value)
        results.append({
            "modelId": model_info["modelId"], "target": target, "strongestBaseline": strongest,
            "meanLossImprovement": improvement, "hacStandardError": standard_error, "twoSidedPValue": p_value,
            "positiveAssets": positive_assets, "perAssetLossImprovement": per_asset,
        })
        if target.startswith("return"):
            residual_std = model_info.get("trainingResidualStd", 0.0)
            probabilities = np.asarray([
                0.5 * (1.0 + math.erf(float(value) / (residual_std * math.sqrt(2.0)))) if residual_std > 0 else 0.5
                for value in candidate
            ])
            actions = np.where(probabilities > 0.55, 1.0, np.where(probabilities < 0.45, -1.0, 0.0))
            gross = actions * y
            results[-1]["shadowPolicyDiagnostic"] = {
                "longThreshold": 0.55, "shortThreshold": 0.45, "assumedCostPerPositionChange": 0.0005,
                "activeFraction": float(np.mean(actions != 0.0)), "grossMeanLogReturn": float(np.mean(gross)),
                "costAdjustedMeanLogReturn": float(np.mean(gross - np.abs(actions) * 0.0005)),
                "promotionUseAllowed": False,
            }
    adjusted = holm(p_values)
    for result, value in zip(results, adjusted):
        result["holmAdjustedPValue"] = value
        result["engineeringFunctional"] = result["meanLossImprovement"] > 0 and result["positiveAssets"] >= 6 and value < 0.05
    report = {
        "schemaVersion": "marketlab.social-blind-extension-report.v1", "classification": "BLIND_RETROSPECTIVE_VALIDATION",
        "frozenModelSetSha256": sha256(frozen_path), "blindFeatureRowsSha256": sha256(feature_path),
        "searchLockSha256": sha256(Path(args.search_lock)), "results": results,
    }
    with Path(args.output).open("x") as handle:
        handle.write(json.dumps(report, indent=2, sort_keys=True) + "\n")


def score(args):
    frozen = Path(args.frozen)
    if sha256(frozen) != args.frozen_sha256:
        raise ValueError("frozen model-set manifest differs from the externally pinned hash")
    manifest = json.loads(frozen.read_text())
    feature_path = Path(args.features)
    output = Path(args.output)
    with output.open("x") as handle:
        for model_info in manifest["models"]:
            artifact = frozen.parent / model_info["modelArtifact"]
            if sha256(artifact) != model_info["modelArtifactSha256"]:
                raise ValueError(f"model artifact hash mismatch: {artifact}")
            stored = pickle.loads(artifact.read_bytes())
            rows, names, x, _, _, _ = load_rows(
                feature_path, model_info["target"], require_label=False, expected_names=stored["features"]
            )
            predictions = stored["model"].predict(x)
            residual_std = model_info.get("trainingResidualStd", 0.0)
            for row, prediction in zip(rows, predictions):
                direction = None
                if model_info["target"].startswith("return") and residual_std > 0.0:
                    z = float(prediction) / (residual_std * math.sqrt(2.0))
                    direction = 0.5 * (1.0 + math.erf(z))
                handle.write(json.dumps({
                    "schemaVersion": "marketlab.social-shadow-forecast.v1", "modelId": model_info["modelId"],
                    "rowId": row["rowId"], "decisionTimeEpochMillis": row["decisionTimeEpochMillis"],
                    "symbol": row["symbol"], "prediction": float(prediction), "directionProbability": direction,
                    "producedAtEpochMillis": int(time.time() * 1000),
                }, sort_keys=True) + "\n")


def main():
    parser = argparse.ArgumentParser()
    sub = parser.add_subparsers(dest="command", required=True)
    training = sub.add_parser("train")
    training.add_argument("--features", required=True); training.add_argument("--feature-manifest", required=True)
    training.add_argument("--feature-lock", required=True); training.add_argument("--search-lock", required=True)
    training.add_argument("--output", required=True); training.add_argument("--trials", type=int, default=40); training.add_argument("--seed", type=int, default=20260803)
    training.add_argument("--allow-test-trials", action="store_true", help=argparse.SUPPRESS)
    scoring = sub.add_parser("score")
    scoring.add_argument("--features", required=True); scoring.add_argument("--frozen", required=True)
    scoring.add_argument("--frozen-sha256", required=True); scoring.add_argument("--output", required=True)
    evaluation = sub.add_parser("evaluate")
    evaluation.add_argument("--features", required=True); evaluation.add_argument("--frozen", required=True)
    evaluation.add_argument("--frozen-sha256", required=True)
    evaluation.add_argument("--search-lock", required=True); evaluation.add_argument("--output", required=True)
    args = parser.parse_args()
    if args.command != "score":
        from budget import require_budget
        require_budget(model=args.command == "train",
                       outcome_access="SINGLE_USE_HISTORICAL_CONFIRMATION" if args.command == "evaluate" else "DEVELOPMENT_ONLY")
    if args.command == "train": train(args)
    elif args.command == "score": score(args)
    else: evaluate(args)


if __name__ == "__main__":
    main()
