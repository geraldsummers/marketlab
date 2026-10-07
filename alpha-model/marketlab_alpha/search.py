"""Chronological search utilities shared by CPU and GPU estimator families."""

from __future__ import annotations

import math
import multiprocessing as mp
import os
import pickle
import random
import shutil
import signal
import tempfile
import time
from collections import Counter, defaultdict
from concurrent.futures import FIRST_COMPLETED, Future, ProcessPoolExecutor, wait
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Callable, Iterable, Iterator, Mapping, Sequence

from .artifacts import iter_jsonl, read_json, sha256_bytes, sha256_file, write_once_bytes, write_once_json
from .contracts import (
    FAMILY_SCHEMA,
    RESULT_SCHEMA,
    SEARCH_SCHEMA,
    TRIAL_SCHEMA,
    canonical_sha256,
    confirmation_marker_payload,
    confirmation_marker_path,
    validate_campaign_lock,
    validate_candidate_search_manifest,
    validate_confirmation_family_manifest,
    validate_confirmation_marker,
    validate_confirmation_preflight,
    validate_confirmation_result,
    validate_frozen_candidate_lock,
    validate_trial_ledger_entry,
)
from .panel import HORIZON_MILLIS, temporal_windows


@dataclass(frozen=True)
class Fold:
    train: tuple[int, ...]
    test: tuple[int, ...]
    train_end_exclusive: int
    test_start_inclusive: int
    test_end_exclusive: int


_PARALLEL_ROW_SETS: dict[str, Sequence[Mapping[str, Any]]] | None = None
_PARALLEL_CPU_MODELS = frozenset(
    {
        "ridge",
        "elastic_net",
        "shallow_tree",
        "random_forest",
        "extra_trees",
        "hist_gradient_boosting",
    }
)
_GIB = 1024**3
_SEARCH_STEP_ACTIVE = False
_SEARCH_STEP_REMAINING: int | None = None


class _SearchStepBoundary(RuntimeError):
    """Stop a disposable search process at the next durable trial boundary."""


def _write_trial_plan(model_id: str, tasks: Sequence[Mapping[str, Any]]) -> Path | None:
    """Publish the exact ordered task batch before any task in it is executed."""
    if not tasks:
        return None
    first = tasks[0]
    summaries = [
        {
            "trialId": str(task["trial_id"]),
            "configuration": dict(task["configuration"]),
            "seed": int(task["seed"]),
            "horizon": str(task["horizon"]),
            "target": str(task["target"]),
            "factor": str(task["factor"]),
            "rowSet": str(task["row_set"]),
            "promotionSourceTrialSha256s": list(task.get("promotion_source_hashes", [])),
        }
        for task in tasks
    ]
    payload = {
        "schemaVersion": "marketlab.alpha-trial-plan.v1",
        "campaignId": first["manifest"]["campaignId"],
        "candidateId": first["manifest"]["candidateId"],
        "epistemicStage": "EXPLORATORY",
        "rung": str(first["rung"]),
        "modelId": model_id,
        "orderedTasks": summaries,
    }
    identity = canonical_sha256(payload)[:16]
    filename = (
        f"{payload['candidateId']}-{str(first['rung']).lower()}-{model_id}-{identity}.json"
        .replace("_", "-")
    )
    path = Path(first["output_directory"]) / "checkpoints" / "plans" / filename
    if path.exists():
        if canonical_sha256(read_json(path)) != canonical_sha256(payload):
            raise ValueError(f"immutable trial plan differs from replay: {path}")
        return path
    write_once_json(path, payload)
    return path


def _cgroup_working_set_bytes(current: int, memory_stat: str) -> int:
    inactive_file = 0
    for line in memory_stat.splitlines():
        name, _, value = line.partition(" ")
        if name == "inactive_file":
            inactive_file = int(value)
            break
    return max(0, current - inactive_file)


def _cgroup_memory_usage() -> tuple[int, int] | None:
    try:
        current = int(Path("/sys/fs/cgroup/memory.current").read_text().strip())
        maximum_text = Path("/sys/fs/cgroup/memory.max").read_text().strip()
        memory_stat = Path("/sys/fs/cgroup/memory.stat").read_text()
        working_set = _cgroup_working_set_bytes(current, memory_stat)
        return None if maximum_text == "max" else (working_set, int(maximum_text))
    except (FileNotFoundError, OSError, ValueError):
        return None


def _adaptive_trial_workers(
    model_id: str,
    task_count: int,
    worker_ceiling: int,
    *,
    memory_usage: tuple[int, int] | None = None,
    reserve_bytes: int = 12 * _GIB,
    worker_bytes: int = 8 * _GIB,
) -> int:
    """Bound exact-trial concurrency by family policy and cgroup headroom."""

    if os.environ.get("MARKETLAB_EXPERIMENT_ID"):
        return 1
    if task_count < 2 or worker_ceiling < 2 or model_id not in _PARALLEL_CPU_MODELS:
        return 1
    usage = _cgroup_memory_usage() if memory_usage is None else memory_usage
    if usage is None or worker_bytes < 1:
        return 1
    current, maximum = usage
    affordable = max(1, (maximum - current - reserve_bytes) // worker_bytes)
    return max(1, min(task_count, worker_ceiling, affordable))


def _run_parallel_trial(task: Mapping[str, Any]) -> tuple[dict[str, Any], dict[str, Any] | None]:
    if _PARALLEL_ROW_SETS is None:
        raise RuntimeError("parallel trial rows are not initialized")
    arguments = dict(task)
    row_set = str(arguments.pop("row_set"))
    arguments.pop("promotion_source_hashes", None)
    return _checkpointed_trial(rows=_PARALLEL_ROW_SETS[row_set], **arguments)


def _parallel_worker_ready(_index: int) -> int:
    time.sleep(0.05)
    return os.getpid()


class _AdaptiveTrialRunner:
    """Persistent pre-CUDA CPU pool with deterministic ordered collection."""

    def __init__(
        self,
        breadth_rows: Sequence[Mapping[str, Any]],
        full_rows: Sequence[Mapping[str, Any]],
        worker_ceiling: int | None = None,
        memory_usage: tuple[int, int] | None = None,
    ) -> None:
        global _PARALLEL_ROW_SETS
        _PARALLEL_ROW_SETS = {"breadth": breadth_rows, "full": full_rows}
        configured = int(os.environ.get("MARKETLAB_SEARCH_WORKERS", "1"))
        if _SEARCH_STEP_ACTIVE:
            configured = 1
        self.maximum_workers = max(1, worker_ceiling if worker_ceiling is not None else configured)
        self._memory_usage = memory_usage
        self.last_worker_count = 1
        self.in_flight_trial_ids: tuple[str, ...] = ()
        self._executor: ProcessPoolExecutor | None = None
        if self.maximum_workers > 1 and "fork" in mp.get_all_start_methods():
            self._executor = ProcessPoolExecutor(
                max_workers=self.maximum_workers,
                mp_context=mp.get_context("fork"),
            )
            list(self._executor.map(_parallel_worker_ready, range(self.maximum_workers)))

    def run(
        self,
        model_id: str,
        tasks: Sequence[Mapping[str, Any]],
        on_checkpoint: Callable[[dict[str, Any]], None] | None = None,
    ) -> Iterator[tuple[dict[str, Any], dict[str, Any] | None]]:
        _write_trial_plan(model_id, tasks)
        reserve = int(os.environ.get("MARKETLAB_SEARCH_MEMORY_RESERVE_GIB", "12")) * _GIB
        per_worker = int(os.environ.get("MARKETLAB_SEARCH_WORKER_GIB", "8")) * _GIB
        worker_count = _adaptive_trial_workers(
            model_id,
            len(tasks),
            self.maximum_workers,
            memory_usage=self._memory_usage,
            reserve_bytes=reserve,
            worker_bytes=per_worker,
        )
        if self._executor is None:
            worker_count = 1
        self.last_worker_count = worker_count
        prepared = [dict(task) for task in tasks]
        if worker_count == 1:
            for task in prepared:
                self.in_flight_trial_ids = (str(task["trial_id"]),)
                result = _run_parallel_trial(dict(task, execution_workers=1))
                self.in_flight_trial_ids = ()
                if on_checkpoint is not None:
                    on_checkpoint(result[0])
                yield result
            return

        resolved: dict[int, tuple[dict[str, Any], dict[str, Any] | None]] = {}
        missing: dict[int, dict[str, Any]] = {}
        for index, task in enumerate(prepared):
            trial_root = Path(task["output_directory"]) / "checkpoints" / "trials" / str(task["trial_id"])
            if trial_root.exists():
                result = _run_parallel_trial(dict(task, execution_workers=1))
                resolved[index] = result
                if on_checkpoint is not None:
                    on_checkpoint(result[0])
            else:
                missing[index] = task

        futures: dict[Future[tuple[dict[str, Any], dict[str, Any] | None]], int] = {}
        undispatched = list(missing)
        next_result = 0
        while next_result < len(prepared):
            while undispatched:
                allowed = _adaptive_trial_workers(
                    model_id,
                    len(tasks),
                    self.maximum_workers,
                    memory_usage=self._memory_usage,
                    reserve_bytes=reserve,
                    worker_bytes=per_worker,
                )
                self.last_worker_count = allowed
                if len(futures) >= allowed:
                    break
                index = undispatched.pop(0)
                future = self._executor.submit(
                    _run_parallel_trial,
                    dict(missing[index], execution_workers=allowed),
                )
                futures[future] = index
                self.in_flight_trial_ids = tuple(
                    str(missing[pending]["trial_id"]) for pending in sorted(futures.values())
                )

            if next_result in resolved:
                result = resolved.pop(next_result)
                next_result += 1
                yield result
                continue
            if not futures:
                raise RuntimeError("adaptive scheduler has no runnable or resolved trial")

            done, _ = wait(tuple(futures), return_when=FIRST_COMPLETED)
            for future in done:
                index = futures.pop(future)
                result = future.result()
                resolved[index] = result
                self.in_flight_trial_ids = tuple(
                    str(missing[pending]["trial_id"]) for pending in sorted(futures.values())
                )
                if on_checkpoint is not None:
                    on_checkpoint(result[0])
        self.in_flight_trial_ids = ()

    def close(self) -> None:
        global _PARALLEL_ROW_SETS
        if self._executor is not None:
            self._executor.shutdown(wait=True, cancel_futures=True)
            self._executor = None
        _PARALLEL_ROW_SETS = None


def chronological_folds(
    times: Sequence[int], horizon_ms: int, outer_folds: int, minimum_train_rows: int
) -> list[Fold]:
    if horizon_ms <= 0 or outer_folds < 2 or minimum_train_rows < 2:
        raise ValueError("invalid chronological fold configuration")
    unique = sorted(set(int(value) for value in times))
    if len(unique) < outer_folds + 2:
        raise ValueError("insufficient distinct decision times")
    first_test_position = max(1, len(unique) // 2)
    test_times = unique[first_test_position:]
    width = len(test_times) // outer_folds
    if width < 1:
        raise ValueError("insufficient test timestamps for outer folds")
    result: list[Fold] = []
    for number in range(outer_folds):
        start_position = number * width
        end_position = len(test_times) if number == outer_folds - 1 else (number + 1) * width
        start, end = test_times[start_position], test_times[end_position - 1] + 1
        train_end = start - horizon_ms
        train = tuple(index for index, value in enumerate(times) if int(value) < train_end)
        test = tuple(index for index, value in enumerate(times) if start <= int(value) < end)
        if len(train) >= minimum_train_rows and test:
            result.append(Fold(train, test, train_end, start, end))
    if len(result) < 2:
        raise ValueError("purging leaves fewer than two usable folds")
    return result


def squared_losses(actual: Sequence[float], prediction: Sequence[float]) -> list[float]:
    if len(actual) != len(prediction):
        raise ValueError("actual and prediction lengths differ")
    return [(float(a) - float(p)) ** 2 for a, p in zip(actual, prediction)]


def strongest_baseline_improvement(
    actual: Sequence[float], candidate: Sequence[float], controls: Mapping[str, Sequence[float]]
) -> tuple[float, str]:
    if not controls:
        raise ValueError("at least one baseline is required")
    candidate_loss = _mean(squared_losses(actual, candidate))
    losses = {name: _mean(squared_losses(actual, values)) for name, values in controls.items()}
    strongest = min(losses, key=lambda name: (losses[name], name))
    return losses[strongest] - candidate_loss, strongest


def baseline_predictions(
    train_targets: Sequence[float], test_persistence_returns: Sequence[float]
) -> dict[str, list[float]]:
    mean = _mean([float(value) for value in train_targets])
    return {
        "zero-return": [0.0] * len(test_persistence_returns),
        "historical-mean": [mean] * len(test_persistence_returns),
        "persistence": [float(value) for value in test_persistence_returns],
        "reversal": [-float(value) for value in test_persistence_returns],
    }


def select_development_winners(
    trials: Iterable[Mapping[str, Any]], maximum_per_mechanism: int = 1, maximum_total: int = 4
) -> list[dict[str, Any]]:
    """Deterministic stability-first selection from already out-of-fold trials."""
    if maximum_per_mechanism != 1 or maximum_total < 1:
        raise ValueError("campaign permits one winner per mechanism and a positive total limit")
    eligible = []
    for trial in trials:
        improvements = [float(value) for value in trial.get("outerFoldImprovements", [])]
        if len(improvements) < 2 or not all(math.isfinite(value) for value in improvements):
            continue
        positive = sum(value > 0 for value in improvements)
        if positive <= len(improvements) // 2:
            continue
        item = dict(trial)
        item["meanOuterImprovement"] = _mean(improvements)
        item["positiveOuterFolds"] = positive
        eligible.append(item)
    eligible.sort(key=lambda value: (-value["positiveOuterFolds"], -value["meanOuterImprovement"], str(value["trialId"])))
    selected: list[dict[str, Any]] = []
    mechanisms: set[str] = set()
    for trial in eligible:
        mechanism = str(trial["mechanism"])
        if mechanism in mechanisms:
            continue
        selected.append(trial)
        mechanisms.add(mechanism)
        if len(selected) == maximum_total:
            break
    return selected


def holm_adjust(p_values: Sequence[float]) -> list[float]:
    """Holm step-down adjusted p-values in original order."""
    if any(not 0.0 <= float(value) <= 1.0 for value in p_values):
        raise ValueError("p-values must lie in [0, 1]")
    order = sorted(range(len(p_values)), key=lambda index: p_values[index])
    adjusted = [0.0] * len(p_values)
    running = 0.0
    count = len(p_values)
    for rank, index in enumerate(order):
        running = max(running, min(1.0, (count - rank) * float(p_values[index])))
        adjusted[index] = running
    return adjusted


def _mean(values: Sequence[float]) -> float:
    if not values:
        raise ValueError("cannot average an empty sequence")
    return sum(values) / len(values)


MECHANISM_PREFIXES = {
    "market-state": ("latest_", "mean_", "realized_", "log_", "volume_", "basket_", "cross_section_"),
    "market-transmission": ("btc_", "eth_", "basket_"),
    "cross-asset-divergence": ("btc_divergence", "cross_section_", "factor_"),
    "trade-flow": ("taker_", "flow_", "impact_", "trade_", "log_trade_count"),
    "derivatives-positioning": ("funding_", "premium_", "open_interest_", "liquidation_", "basis_"),
    "cross-market": ("spot_", "perp_", "venue_", "lead_", "lag_"),
    "social-information": ("social_", "attention_", "polarity_", "disagreement_", "novelty_"),
    "options-implied": ("option_", "iv_", "skew_", "dvol_", "term_structure_"),
    "onchain-macro": ("onchain_", "stablecoin_", "macro_"),
}

MARKET_PREFIXES = MECHANISM_PREFIXES["market-state"]


def _utc_now() -> str:
    return datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")


def _instant_ms(value: str) -> int:
    return int(datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp() * 1000)


def _rows_with_targets_inside_period(
    rows: Sequence[Mapping[str, Any]], start_ms: int, end_ms: int, horizon: str
) -> list[Mapping[str, Any]]:
    """Keep origins whose complete target window lies inside a sealed period."""

    horizon_ms = HORIZON_MILLIS[horizon]
    return [
        row
        for row in rows
        if start_ms <= int(row["decisionTimeEpochMillis"])
        and int(row["decisionTimeEpochMillis"]) + horizon_ms <= end_ms
    ]


def _gpu_identity() -> dict[str, Any]:
    from .models import probe_gpu_environment

    probe = probe_gpu_environment()
    notes = [
        "PyTorch deterministic algorithms enforced; nondeterministic kernels fail closed",
        "CUDA requires CUBLAS_WORKSPACE_CONFIG=:4096:8 and math-only scaled-dot-product attention",
    ]
    if not probe.available:
        return {
            "available": False,
            "unavailableReason": probe.probe_error or "CUDA is unavailable",
            "determinismNotes": notes,
        }
    required = {
        "deviceName": probe.name,
        "deviceUuid": os.environ.get("MARKETLAB_GPU_UUID"),
        "driverVersion": os.environ.get("MARKETLAB_NVIDIA_DRIVER_VERSION"),
        "runtimeVersion": probe.cuda_runtime,
        "totalMemoryBytes": probe.total_memory_bytes,
        "imageDigest": os.environ.get("MARKETLAB_WORKER_IMAGE_DIGEST"),
    }
    missing = [name for name, value in required.items() if value in (None, "")]
    if missing:
        raise ValueError("CUDA is available but deployment identity is incomplete: " + ", ".join(missing))
    return {"available": True, **required, "determinismNotes": notes}


def _model_configurations(model_id: str, count: int, seed: int, *, extra: bool = False) -> list[dict[str, Any]]:
    rng = random.Random(seed + sum(ord(value) for value in model_id) + (10_000 if extra else 0))
    values: list[dict[str, Any]] = []
    for _ in range(count):
        if model_id == "ridge":
            config = {"alpha": 10 ** rng.uniform(-6, 3)}
        elif model_id == "elastic_net":
            config = {"alpha": 10 ** rng.uniform(-7, -2), "l1_ratio": rng.choice((0.0, 0.1, 0.5, 0.9, 1.0))}
        elif model_id == "shallow_tree":
            config = {"max_depth": rng.choice((2, 3, 4, 6)), "min_samples_leaf": rng.choice((10, 20, 50))}
        elif model_id in {"random_forest", "extra_trees"}:
            config = {"n_estimators": rng.choice((100, 200, 400)), "max_depth": rng.choice((4, 8, 12, None)), "min_samples_leaf": rng.choice((5, 20, 50))}
        elif model_id == "hist_gradient_boosting":
            config = {"max_iter": rng.choice((100, 200, 400)), "learning_rate": 10 ** rng.uniform(-2.2, -0.4), "max_leaf_nodes": rng.choice((7, 15, 31)), "l2_regularization": 10 ** rng.uniform(-5, 1)}
        elif model_id == "gpu_xgboost":
            config = {"n_estimators": rng.choice((200, 400, 800)), "learning_rate": 10 ** rng.uniform(-2.2, -0.5), "max_depth": rng.choice((3, 5, 7)), "subsample": rng.choice((0.7, 0.85, 1.0)), "colsample_bytree": rng.choice((0.7, 0.85, 1.0))}
        else:
            config = {
                "hidden_size": rng.choice((16, 32, 64, 96)),
                "dropout": rng.choice((0.0, 0.1, 0.25)),
                "learning_rate": 10 ** rng.uniform(-4.2, -2.4),
                "weight_decay": 10 ** rng.uniform(-6, -2),
                "epochs": rng.choice((8, 16, 32)),
            }
            if model_id in {"mlp", "ft_transformer", "tcn", "gru", "lstm", "causal_transformer"}:
                config["layers"] = rng.choice((1, 2, 3))
            if model_id in {"ft_transformer", "causal_transformer"}:
                config["heads"] = rng.choice((1, 2, 4))
            if model_id in {"tcn", "gru", "lstm", "causal_transformer"}:
                config["lookback"] = rng.choice((16, 32, 64, 96))
        values.append(config)
    return values


def _feature_names(rows: Sequence[Mapping[str, Any]], mechanism: str) -> list[str]:
    common = set(rows[0]["features"])
    for row in rows[1:]:
        common.intersection_update(row["features"])
    market = {name for name in common if name.startswith(MARKET_PREFIXES)}
    prefixes = MECHANISM_PREFIXES.get(mechanism, (mechanism.replace("-", "_"),))
    mechanism_names = {name for name in common if name.startswith(prefixes)}
    selected = sorted(market | mechanism_names)
    if mechanism != "market-state" and not (mechanism_names - market):
        return []
    return selected


def _panel_dataset(
    rows: Sequence[Mapping[str, Any]],
    feature_names: Sequence[str],
    horizon: str,
    model_id: str,
    lookback: int,
    symbol_vocabulary: Sequence[str] | None = None,
) -> tuple[Any, list[Mapping[str, Any]]]:
    import numpy as np
    from .models import InputKind, model_registry

    eligible = [row for row in rows if row.get("targets", {}).get(horizon) is not None]
    if model_registry()[model_id].input_kind is InputKind.TEMPORAL:
        tensor, indices = temporal_windows(eligible, feature_names, lookback)
        return np.asarray(tensor, dtype=np.float32), [eligible[index] for index in indices]
    symbols = (
        sorted({str(row["symbol"]) for row in eligible})
        if symbol_vocabulary is None
        else [str(symbol) for symbol in symbol_vocabulary]
    )
    if len(symbols) != len(set(symbols)):
        raise ValueError("symbol vocabulary contains duplicates")
    known = set(symbols)
    matrix = [
        [float(row["features"][name]) for name in feature_names]
        + [1.0 if row["symbol"] == symbol else 0.0 for symbol in symbols]
        + [1.0 if str(row["symbol"]) not in known else 0.0]
        for row in eligible
    ]
    return np.asarray(matrix, dtype=np.float64), eligible


def _factor_series(rows: Sequence[Mapping[str, Any]], y: Any, factor: str, train_indices: Sequence[int]) -> tuple[Any, dict[str, Any]]:
    import numpy as np

    if factor == "none":
        return np.zeros(len(rows)), {"kind": "none"}
    by_time: dict[int, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        by_time[int(row["decisionTimeEpochMillis"])].append(index)
    factor_values = np.zeros(len(rows), dtype=np.float64)
    if factor in {"btc", "eth"}:
        wanted = factor.upper()
        for indices in by_time.values():
            lookup = {str(rows[index]["symbol"]): float(y[index]) for index in indices}
            if wanted not in lookup:
                raise ValueError(f"mandatory factor asset {wanted} is missing at a decision time")
            value = lookup[wanted]
            factor_values[indices] = value
        return factor_values, {"kind": factor}
    if factor == "equal-weight-basket":
        for indices in by_time.values():
            factor_values[indices] = sum(float(y[index]) for index in indices) / len(indices)
        return factor_values, {"kind": factor}
    if factor != "first-principal-component":
        raise ValueError(f"unsupported factor representation {factor}")
    symbol_sets = [
        {str(rows[index]["symbol"]) for index in indices}
        for indices in by_time.values()
    ]
    symbols = sorted(set.intersection(*symbol_sets))
    if len(symbols) < 2:
        raise ValueError("PCA factor requires at least two assets present at every development time")
    symbol_index = {symbol: index for index, symbol in enumerate(symbols)}
    train_set = set(train_indices)
    vectors = []
    for indices in by_time.values():
        if not any(index in train_set for index in indices):
            continue
        vector = np.zeros(len(symbols))
        for index in indices:
            symbol = str(rows[index]["symbol"])
            if symbol in symbol_index:
                vector[symbol_index[symbol]] = float(y[index])
        vectors.append(vector)
    if len(vectors) < 2:
        raise ValueError("PCA factor requires at least two complete training times")
    matrix = np.asarray(vectors)
    center = matrix.mean(axis=0)
    _, _, right = np.linalg.svd(matrix - center, full_matrices=False)
    weights = right[0]
    if weights[symbol_index.get("BTC", 0)] < 0:
        weights = -weights
    for indices in by_time.values():
        vector = np.zeros(len(symbols))
        for index in indices:
            symbol = str(rows[index]["symbol"])
            if symbol in symbol_index:
                vector[symbol_index[symbol]] = float(y[index])
        value = float((vector - center) @ weights)
        factor_values[indices] = value
    return factor_values, {"kind": factor, "symbols": symbols, "center": center.tolist(), "weights": weights.tolist()}


def _target_values(
    rows: Sequence[Mapping[str, Any]], horizon: str, target: str, factor: str, train_indices: Sequence[int]
) -> tuple[Any, dict[str, Any]]:
    import numpy as np

    y = np.asarray([float(row["targets"][horizon]) for row in rows], dtype=np.float64)
    if target == "outright-return":
        return y, {"target": target, "factor": {"kind": factor}, "betas": {}}
    if target != "factor-residual-return":
        raise ValueError(f"unsupported target {target}")
    factor_values, factor_state = _factor_series(rows, y, factor, train_indices)
    betas: dict[str, float] = {}
    residual = y.copy()
    for symbol in sorted({str(row["symbol"]) for row in rows}):
        selected = [index for index in train_indices if str(rows[index]["symbol"]) == symbol]
        denominator = sum(float(factor_values[index]) ** 2 for index in selected)
        beta = 0.0 if denominator <= 1e-18 else sum(float(factor_values[index]) * float(y[index]) for index in selected) / denominator
        betas[symbol] = beta
        for index, row in enumerate(rows):
            if str(row["symbol"]) == symbol:
                residual[index] = y[index] - beta * factor_values[index]
    return residual, {"target": target, "factor": factor_state, "betas": betas}


def _inner_fold(times: Sequence[int], train_indices: Sequence[int], horizon_ms: int) -> tuple[list[int], list[int]]:
    ordered_times = sorted({int(times[index]) for index in train_indices})
    if len(ordered_times) < 4:
        raise ValueError("insufficient times for nested development split")
    boundary = ordered_times[max(1, int(len(ordered_times) * 0.8))]
    inner_train = [index for index in train_indices if int(times[index]) < boundary - horizon_ms]
    inner_test = [index for index in train_indices if int(times[index]) >= boundary]
    if len(inner_train) < 2 or not inner_test:
        raise ValueError("purging leaves no nested development split")
    return inner_train, inner_test


def _fit_predict(model_id: str, configuration: Mapping[str, Any], seed: int, x: Any, y: Any, train: Sequence[int], test: Sequence[int]) -> Any:
    from .models import build_estimator

    config = dict(configuration)
    config.pop("lookback", None)
    estimator = build_estimator(model_id, seed=seed, input_channels=int(x.shape[-1]), **config)
    estimator.fit(x[list(train)], y[list(train)])
    return estimator, estimator.predict(x[list(test)])


def _evaluate_cell(
    rows: Sequence[Mapping[str, Any]], feature_names: Sequence[str], horizon: str, target: str,
    factor: str, model_id: str, configurations: Sequence[Mapping[str, Any]], seed: int, outer_folds: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    import numpy as np

    lookback = max(int(config.get("lookback", 2)) for config in configurations)
    x, aligned = _panel_dataset(rows, feature_names, horizon, model_id, lookback)
    times = [int(row["decisionTimeEpochMillis"]) for row in aligned]
    if len(aligned) < 80:
        raise ValueError("fewer than 80 eligible labeled rows")
    folds = chronological_folds(times, HORIZON_MILLIS[horizon], outer_folds, max(20, len(aligned) // 10))
    outer_improvements: list[float] = []
    chosen_configs: list[int] = []
    outer_candidate_losses: list[float] = []
    fit_count = 0
    for fold in folds:
        if len(configurations) == 1:
            chosen = 0
        else:
            inner_train, inner_test = _inner_fold(times, fold.train, HORIZON_MILLIS[horizon])
            inner_scores = []
            for config in configurations:
                y_inner, inner_state = _target_values(aligned, horizon, target, factor, inner_train)
                _, prediction = _fit_predict(model_id, config, seed, x, y_inner, inner_train, inner_test)
                fit_count += 1
                inner_rows = [aligned[index] for index in inner_test]
                inner_persistence = _apply_target_state_to_values(
                    inner_rows,
                    [float(row["features"][f"persistence_return_{horizon}"]) for row in inner_rows],
                    inner_state,
                )
                controls = baseline_predictions(
                    y_inner[list(inner_train)],
                    inner_persistence,
                )
                improvement, _ = strongest_baseline_improvement(y_inner[list(inner_test)], prediction, controls)
                inner_scores.append(improvement)
            chosen = max(range(len(configurations)), key=lambda index: (inner_scores[index], -index))
        chosen_configs.append(chosen)
        y_outer, outer_state = _target_values(aligned, horizon, target, factor, fold.train)
        _, prediction = _fit_predict(model_id, configurations[chosen], seed, x, y_outer, fold.train, fold.test)
        fit_count += 1
        outer_rows = [aligned[index] for index in fold.test]
        outer_persistence = _apply_target_state_to_values(
            outer_rows,
            [float(row["features"][f"persistence_return_{horizon}"]) for row in outer_rows],
            outer_state,
        )
        controls = baseline_predictions(
            y_outer[list(fold.train)],
            outer_persistence,
        )
        improvement, _ = strongest_baseline_improvement(y_outer[list(fold.test)], prediction, controls)
        outer_improvements.append(float(improvement))
        outer_candidate_losses.append(_mean(squared_losses(y_outer[list(fold.test)], prediction)))
    chosen = Counter(chosen_configs).most_common(1)[0][0]
    all_indices = tuple(range(len(aligned)))
    y_all, target_state = _target_values(aligned, horizon, target, factor, all_indices)
    estimator, _ = _fit_predict(model_id, configurations[chosen], seed, x, y_all, all_indices, all_indices[:1])
    fit_count += 1
    training_metadata = (
        estimator.training_metadata()
        if hasattr(estimator, "training_metadata")
        else {"modelId": model_id, "inputKind": "classical", "measuredGpuMemoryBytes": 0}
    )
    bundle = {
        "estimator": estimator,
        "featureNames": list(feature_names),
        "modelId": model_id,
        "configuration": dict(configurations[chosen]),
        "lookback": lookback,
        "horizon": horizon,
        "target": target,
        "factor": factor,
        "targetState": target_state,
        "historicalMean": float(np.mean(y_all)),
        "symbols": sorted({str(row["symbol"]) for row in aligned}),
        "unknownSymbolPolicy": "other-bucket",
        "trainingMetadata": training_metadata,
    }
    return {
        "outerFoldImprovements": outer_improvements,
        "meanOuterImprovement": _mean(outer_improvements),
        "positiveOuterFolds": sum(value > 0 for value in outer_improvements),
        "configurationIndex": chosen,
        "outerFoldCandidateLosses": outer_candidate_losses,
        "fitCount": fit_count,
        "trainingMetadata": training_metadata,
    }, bundle


def _evaluate_with_market_baseline(
    rows: Sequence[Mapping[str, Any]],
    feature_names: Sequence[str],
    horizon: str,
    target: str,
    factor: str,
    model_id: str,
    configuration: Mapping[str, Any],
    seed: int,
    outer_folds: int,
) -> tuple[dict[str, Any], dict[str, Any]]:
    """Evaluate one declared configuration against naive and learned market controls."""
    candidate_metrics, candidate_bundle = _evaluate_cell(
        rows, feature_names, horizon, target, factor, model_id, [configuration], seed, outer_folds
    )
    market_features = _feature_names(rows, "market-state")
    if not market_features:
        raise ValueError("strongest-market-only baseline has no causal market-state fields")
    market_metrics, market_bundle = _evaluate_cell(
        rows,
        market_features,
        horizon,
        target,
        factor,
        "ridge",
        [{"alpha": 1.0}],
        seed,
        outer_folds,
    )
    naive = [float(value) for value in candidate_metrics["outerFoldImprovements"]]
    market = [
        float(baseline) - float(candidate)
        for baseline, candidate in zip(
            market_metrics["outerFoldCandidateLosses"],
            candidate_metrics["outerFoldCandidateLosses"],
        )
    ]
    combined = [min(naive_value, market_value) for naive_value, market_value in zip(naive, market)]
    candidate_metrics.update({
        "outerFoldNaiveImprovements": naive,
        "outerFoldMarketOnlyImprovements": market,
        "outerFoldImprovements": combined,
        "meanOuterImprovement": _mean(combined),
        "positiveOuterFolds": sum(value > 0 for value in combined),
        "fitCount": int(candidate_metrics["fitCount"]) + int(market_metrics["fitCount"]),
        "trainingMetadata": {
            "candidate": candidate_metrics["trainingMetadata"],
            "marketBaseline": market_metrics["trainingMetadata"],
        },
    })
    candidate_bundle["marketBaseline"] = market_bundle
    candidate_bundle["baselineDefinition"] = {
        "name": "strongest-market-only",
        "modelId": "ridge",
        "configuration": {"alpha": 1.0},
        "featureNames": market_features,
    }
    return candidate_metrics, candidate_bundle


def _confirmation_family_manifest(
    campaign: Mapping[str, Any],
    candidates: Sequence[Mapping[str, Any]],
    campaign_lock_file_sha256: str,
    development_panel_sha256s: Sequence[str],
    confirmation_ledger_root: Path,
) -> dict[str, Any]:
    family = {
        "schemaVersion": FAMILY_SCHEMA,
        "campaignId": campaign["campaignId"],
        "createdAt": _utc_now(),
        "campaignLockFileSha256": campaign_lock_file_sha256,
        "developmentPanelSha256s": sorted(set(development_panel_sha256s)),
        "confirmationLedgerRoot": str(confirmation_ledger_root.resolve()),
        "multiplicity": campaign["validation"]["multiplicity"],
        "selectedCandidates": sorted(
            [
                {
                    "candidateId": candidate["candidateId"],
                    "trialId": candidate["trialId"],
                    "searchManifestSha256": canonical_sha256(candidate["searchManifest"]),
                }
                for candidate in candidates
            ],
            key=lambda item: (item["candidateId"], item["trialId"]),
        ),
    }
    return validate_confirmation_family_manifest(family, campaign)


def _panel_manifest_for_period(panel_path: Path, start: str, end: str) -> dict[str, Any]:
    manifest_path = panel_path.with_suffix(panel_path.suffix + ".manifest.json")
    if not manifest_path.is_file():
        raise ValueError("panel requires an immutable directional-panel manifest before labels are opened")
    manifest = read_json(manifest_path)
    if manifest.get("schemaVersion") != "marketlab.directional-panel-manifest.v1":
        raise ValueError("unsupported directional panel manifest")
    if manifest.get("panelSha256") != sha256_file(panel_path):
        raise ValueError("panel differs from its directional-panel manifest")
    period = manifest.get("outcomePeriod", {})
    if period.get("startInclusive") != start or period.get("endExclusive") != end:
        raise ValueError("panel outcome period does not exactly match the locked campaign period")
    basket_size = int(manifest.get("basketSize", 0))
    if basket_size < 2:
        raise ValueError("panel manifest has no valid basket size")
    return manifest


def _stage_a_cells(campaign: Mapping[str, Any], model_index: int, count: int) -> list[tuple[str, str, str]]:
    dimensions = campaign["searchDimensions"]
    horizons = list(dimensions["horizons"])
    factors = list(dimensions["factorRepresentations"])
    targets = list(dimensions["targets"])
    if not horizons or not targets or not factors:
        raise ValueError("campaign has no valid horizon/target/factor search cells")
    residual_factors = [factor for factor in factors if factor != "none"] or factors
    result: list[tuple[str, str, str]] = []
    for index in range(count):
        horizon_index = index % len(horizons)
        cycle = index // len(horizons)
        if cycle == 0 and "outright-return" in targets:
            factor = "none" if "none" in factors else factors[0]
            result.append((horizons[horizon_index], "outright-return", factor))
        elif "factor-residual-return" in targets:
            factor = residual_factors[(model_index + horizon_index + max(0, cycle - 1)) % len(residual_factors)]
            result.append((horizons[horizon_index], "factor-residual-return", factor))
        else:
            factor = "none" if "none" in factors else factors[0]
            result.append((horizons[horizon_index], targets[0], factor))
    return result


def run_development_search(
    panel_path: Path, lock_path: Path, output_directory: Path,
    confirmation_ledger_root: Path,
    maximum_trials: int | None = None, test_mode: bool = False,
) -> dict[str, Any]:
    """Run a selection-aware development search without reading confirmation labels."""
    campaign = validate_campaign_lock(read_json(lock_path))
    if campaign.get("iterativeSearch") and not test_mode:
        return _run_iterative_development_search(
            panel_path,
            lock_path,
            output_directory,
            confirmation_ledger_root,
            campaign,
        )
    development_start = _instant_ms(campaign["developmentPeriod"]["startInclusive"])
    development_end = _instant_ms(campaign["developmentPeriod"]["endExclusive"])
    panel_manifest = _panel_manifest_for_period(
        panel_path,
        campaign["developmentPeriod"]["startInclusive"],
        campaign["developmentPeriod"]["endExclusive"],
    )
    basket_size = int(panel_manifest["basketSize"])
    if basket_size not in campaign["searchDimensions"]["basketSizes"]:
        raise ValueError("panel basket size is outside the locked campaign")
    rows = list(iter_jsonl(panel_path))
    if any(
        not development_start <= int(row["decisionTimeEpochMillis"]) < development_end
        for row in rows
    ):
        raise ValueError("development panel contains an origin outside its locked outcome period")
    if not rows:
        raise ValueError("panel has no rows inside the locked development period")
    output_directory.mkdir(parents=True, exist_ok=True)
    result_path = output_directory / "search-result.json"
    if result_path.exists():
        raise FileExistsError(f"development output already exists: {result_path}")
    gpu_identity = _gpu_identity()
    budgets = campaign["searchBudget"]
    stage_a_count = 1 if test_mode else budgets["stageATrialsPerFamily"]
    if maximum_trials is not None:
        if not test_mode and maximum_trials != stage_a_count:
            raise ValueError("production search must use the locked Stage-A per-family trial count")
        stage_a_count = min(stage_a_count, maximum_trials)
    all_feature_names = sorted(set.intersection(*(set(row["features"]) for row in rows)))
    feature_schema_hash = canonical_sha256(all_feature_names)
    trials: list[dict[str, Any]] = []
    bundles: dict[str, dict[str, Any]] = {}
    manifests: dict[str, dict[str, Any]] = {}
    blocked: list[dict[str, str]] = []
    started = _utc_now()
    for mechanism in campaign["mechanisms"]:
        features = _feature_names(rows, mechanism)
        candidate_id = f"{mechanism}-direction-v1"
        if not features:
            blocked.append({"mechanism": mechanism, "candidateId": candidate_id, "reason": "no mechanism-specific causal fields in panel"})
            continue
        dimensions = {
            "assets": ["dynamic-basket"],
            "basketSizes": [basket_size],
            "factorRepresentations": campaign["searchDimensions"]["factorRepresentations"],
            "horizons": campaign["searchDimensions"]["horizons"],
            "targets": campaign["searchDimensions"]["targets"],
            "informationSets": [mechanism],
            "modelFamilies": campaign["searchDimensions"]["modelFamilies"],
            "variants": ["unrestricted-sign", "time-shift-placebo"],
        }
        maximum_trials_for_mechanism = (
            stage_a_count * len(dimensions["modelFamilies"])
            + (
                0
                if test_mode
                else budgets["stageBAdditionalTrialsPerSurvivor"]
                * min(budgets["stageBMaxFamiliesPerMechanism"], len(dimensions["modelFamilies"]))
                * len(budgets["stageBSeeds"])
            )
        )
        manifest = {
            "schemaVersion": SEARCH_SCHEMA,
            "campaignId": campaign["campaignId"],
            "candidateId": candidate_id,
            "mechanism": mechanism,
            "stage": "EXPLORATORY",
            "createdAt": started,
            "userConstraints": campaign["userConstraints"],
            "designConventions": campaign["designConventions"],
            "empiricalClaims": [f"{mechanism} fields may or may not add directional forecast value"],
            "searchDimensions": dimensions,
            "maximumTrials": maximum_trials_for_mechanism,
            "gpuIdentity": gpu_identity,
            "openedOutcomePeriods": [],
            "limitations": {
                "survivorship": "Confirmation is prohibited where historical membership cannot be reconstructed",
                "sourceTransfer": "Archive-source evidence does not establish transfer to Hyperliquid or another venue",
            },
            "artifacts": [],
        }
        validate_candidate_search_manifest(manifest, campaign)
        manifests[candidate_id] = manifest
        for model_index, model_id in enumerate(dimensions["modelFamilies"]):
            configurations = _model_configurations(model_id, stage_a_count, budgets["stageASeeds"][0])
            cells = _stage_a_cells(campaign, model_index, stage_a_count)
            for config_index, (configuration, cell) in enumerate(zip(configurations, cells)):
                horizon, target, factor = cell
                trial_id = (
                    f"{candidate_id}-{basket_size}-{model_id}-stage-a-{config_index}-seed-{budgets['stageASeeds'][0]}"
                    .replace("_", "-")
                )
                selection = {
                    "assets": "dynamic-basket", "basketSizes": basket_size,
                    "factorRepresentations": factor, "horizons": horizon,
                    "targets": target, "informationSets": mechanism,
                    "modelFamilies": model_id, "variants": "unrestricted-sign",
                }
                entry = {
                    "schemaVersion": TRIAL_SCHEMA, "campaignId": campaign["campaignId"],
                    "candidateId": candidate_id, "trialId": trial_id,
                    "startedAt": started, "completedAt": _utc_now(),
                    "seed": budgets["stageASeeds"][0], "selection": selection,
                    "artifactHashes": [], "configuration": dict(configuration), "searchStage": "A",
                }
                try:
                    horizon_rows = _rows_with_targets_inside_period(rows, development_start, development_end, horizon)
                    metrics, bundle = _evaluate_with_market_baseline(
                        horizon_rows, features, horizon, target, factor, model_id, configuration,
                        budgets["stageASeeds"][0], 3 if test_mode else 5,
                    )
                    entry.update({
                        "completedAt": _utc_now(), "status": "COMPLETED",
                        "runtime": metrics["trainingMetadata"],
                        "metrics": {
                            "meanOuterImprovement": metrics["meanOuterImprovement"],
                            "positiveOuterFolds": metrics["positiveOuterFolds"],
                            "fitCount": metrics["fitCount"],
                        },
                    })
                    validate_trial_ledger_entry(entry, manifest)
                    trials.append({**entry, **metrics, "mechanism": mechanism})
                    bundles[trial_id] = bundle
                except Exception as error:
                    entry.update({"completedAt": _utc_now(), "status": "FAILED", "metrics": {}})
                    validate_trial_ledger_entry(entry, manifest)
                    trials.append({**entry, "mechanism": mechanism, "error": f"{type(error).__name__}: {error}"})
    stage_b_groups: list[dict[str, Any]] = []
    if not test_mode:
        completed = [
            trial for trial in trials
            if trial["status"] == "COMPLETED" and trial.get("searchStage") == "A"
        ]
        for mechanism in campaign["mechanisms"]:
            mechanism_trials = [trial for trial in completed if trial["mechanism"] == mechanism]
            best_by_family: dict[str, dict[str, Any]] = {}
            for trial in mechanism_trials:
                family = trial["selection"]["modelFamilies"]
                if family not in best_by_family or trial["meanOuterImprovement"] > best_by_family[family]["meanOuterImprovement"]:
                    best_by_family[family] = trial
            survivors = sorted(
                best_by_family.values(),
                key=lambda trial: (-trial["positiveOuterFolds"], -trial["meanOuterImprovement"], trial["trialId"]),
            )[: budgets["stageBMaxFamiliesPerMechanism"]]
            features = _feature_names(rows, mechanism)
            for survivor in survivors:
                selection = survivor["selection"]
                model_id = selection["modelFamilies"]
                configurations = _model_configurations(
                    model_id,
                    budgets["stageBAdditionalTrialsPerSurvivor"],
                    budgets["stageBSeeds"][0],
                    extra=True,
                )
                for config_index, configuration in enumerate(configurations):
                    seed_results: list[dict[str, Any]] = []
                    canonical_bundle = None
                    for seed in budgets["stageBSeeds"]:
                        trial_id = (
                            f"{survivor['trialId']}-stage-b-{config_index}-seed-{seed}".replace("_", "-")
                        )
                        entry = {
                            "schemaVersion": TRIAL_SCHEMA, "campaignId": campaign["campaignId"],
                            "candidateId": survivor["candidateId"], "trialId": trial_id,
                            "startedAt": started, "completedAt": _utc_now(),
                            "seed": seed, "selection": selection, "artifactHashes": [],
                            "configuration": dict(configuration), "searchStage": "B",
                        }
                        try:
                            horizon_rows = _rows_with_targets_inside_period(
                                rows, development_start, development_end, selection["horizons"]
                            )
                            metrics, bundle = _evaluate_with_market_baseline(
                                horizon_rows, features, selection["horizons"], selection["targets"],
                                selection["factorRepresentations"], model_id, configuration, seed, 5,
                            )
                            entry.update({
                                "completedAt": _utc_now(), "status": "COMPLETED",
                                "runtime": metrics["trainingMetadata"],
                                "metrics": {
                                    "meanOuterImprovement": metrics["meanOuterImprovement"],
                                    "positiveOuterFolds": metrics["positiveOuterFolds"],
                                    "fitCount": metrics["fitCount"],
                                },
                            })
                            validate_trial_ledger_entry(entry, manifests[survivor["candidateId"]])
                            trials.append({**entry, **metrics, "mechanism": mechanism})
                            seed_results.append(metrics)
                            if seed == budgets["stageBSeeds"][0]:
                                canonical_bundle = bundle
                        except Exception as error:
                            entry.update({"completedAt": _utc_now(), "status": "FAILED", "metrics": {}})
                            validate_trial_ledger_entry(entry, manifests[survivor["candidateId"]])
                            trials.append({**entry, "mechanism": mechanism, "error": f"{type(error).__name__}: {error}"})
                    if len(seed_results) == len(budgets["stageBSeeds"]):
                        outer = [value for metrics in seed_results for value in metrics["outerFoldImprovements"]]
                        group_id = f"{survivor['trialId']}-stage-b-{config_index}-group".replace("_", "-")
                        group = {
                            "campaignId": campaign["campaignId"], "candidateId": survivor["candidateId"],
                            "trialId": group_id, "selection": selection, "configuration": dict(configuration),
                            "canonicalSeed": budgets["stageBSeeds"][0], "mechanism": mechanism,
                            "outerFoldImprovements": outer, "meanOuterImprovement": _mean(outer),
                            "positiveOuterFolds": sum(value > 0 for value in outer),
                            "seedMetrics": seed_results, "status": "COMPLETED",
                        }
                        stage_b_groups.append(group)
                        bundles[group_id] = canonical_bundle
    winner_pool = [trial for trial in stage_b_groups if trial.get("status") == "COMPLETED"]
    if not winner_pool:
        winner_pool = [
            trial for trial in trials
            if trial["status"] == "COMPLETED" and trial.get("searchStage") == "A"
        ]
    winners = select_development_winners(winner_pool, maximum_total=len(campaign["mechanisms"]))
    development_candidates = []
    for winner in winners:
        bundle = bundles[winner["trialId"]]
        winner_rows = _rows_with_targets_inside_period(
            rows, development_start, development_end, winner["selection"]["horizons"]
        )
        placebo_rows = _time_shift_placebo(
            winner_rows, winner["selection"]["horizons"], HORIZON_MILLIS["7d"]
        )
        try:
            placebo_metrics, _ = _evaluate_with_market_baseline(
                placebo_rows,
                _feature_names(placebo_rows, winner["mechanism"]),
                winner["selection"]["horizons"], winner["selection"]["targets"],
                winner["selection"]["factorRepresentations"], winner["selection"]["modelFamilies"],
                bundle["configuration"],
                int(winner.get("canonicalSeed", winner.get("seed", budgets["stageASeeds"][0]))),
                3 if test_mode else 5,
            )
            placebo = {**placebo_metrics, "status": "COMPLETED"}
        except Exception as error:
            placebo = {"status": "FAILED", "error": f"{type(error).__name__}: {error}"}
        suspicious = (
            placebo.get("status") == "COMPLETED"
            and int(placebo["positiveOuterFolds"]) > len(placebo["outerFoldImprovements"]) // 2
            and float(placebo["meanOuterImprovement"]) > 0.0
        )
        if (placebo.get("status") == "FAILED" or suspicious) and not test_mode:
            blocked.append({
                "mechanism": winner["mechanism"], "candidateId": winner["candidateId"],
                "reason": "time-shift integrity audit failed or retained predictive structure",
            })
            continue
        model_path = output_directory / "models" / f"{winner['trialId']}.pickle"
        model_hash = write_once_bytes(model_path, pickle.dumps(bundle, protocol=5))
        exact_feature_schema = canonical_sha256({
            "featureNames": bundle["featureNames"],
            "marketBaselineFeatureNames": bundle["marketBaseline"]["featureNames"],
            "symbols": bundle["symbols"],
            "unknownSymbolPolicy": bundle["unknownSymbolPolicy"],
        })
        development_candidates.append({
            "candidateId": winner["candidateId"], "trialId": winner["trialId"],
            "mechanism": winner["mechanism"], "selection": winner["selection"],
            "configuration": dict(bundle["configuration"]),
            "seed": int(winner.get("canonicalSeed", winner.get("seed", budgets["stageASeeds"][0]))),
            "outerFoldImprovements": winner["outerFoldImprovements"],
            "featureSchemaSha256": exact_feature_schema,
            "searchManifest": manifests[winner["candidateId"]],
            "modelArtifactPath": str(model_path.absolute()), "modelArtifactSha256": model_hash,
            "panelSha256": sha256_file(panel_path), "timeShiftPlacebo": placebo,
        })
    selected = development_candidates[: budgets["maxFrozenOverall"]]
    confirmation_family = _confirmation_family_manifest(
        campaign, selected, sha256_file(lock_path), [sha256_file(panel_path)], confirmation_ledger_root
    )
    result = {
        "schemaVersion": "marketlab.alpha-development-search-result.v1",
        "campaignId": campaign["campaignId"], "campaignLockSha256": sha256_file(lock_path),
        "panelSha256": sha256_file(panel_path), "basketSize": basket_size,
        "featureSchemaSha256": feature_schema_hash,
        "trialLedger": trials, "developmentCandidates": development_candidates,
        "selected": selected, "blockedMechanisms": blocked,
        "trialAccounting": {
            "ledgerEntries": len(trials),
            "completed": sum(trial["status"] == "COMPLETED" for trial in trials),
            "failed": sum(trial["status"] != "COMPLETED" for trial in trials),
            "declaredPerActiveMechanism": next(iter(manifests.values()))["maximumTrials"] if manifests else 0,
            "budgetUnit": "one exact configuration and seed; fold fits are reported by fitCount",
        },
        "confirmationFamily": confirmation_family,
        "openedOutcomePeriods": [],
    }
    result["artifactSha256"] = write_once_json(result_path, result)
    return result


def run_development_search_step(
    panel_path: Path,
    lock_path: Path,
    output_directory: Path,
    confirmation_ledger_root: Path,
) -> dict[str, Any]:
    """Execute at most one missing iterative-search trial in this process."""
    campaign = validate_campaign_lock(read_json(lock_path))
    if not campaign.get("iterativeSearch"):
        raise ValueError("search-step requires an iterative campaign lock")
    global _SEARCH_STEP_ACTIVE, _SEARCH_STEP_REMAINING, _PARALLEL_ROW_SETS
    if _SEARCH_STEP_ACTIVE:
        raise RuntimeError("nested search-step execution is prohibited")
    _SEARCH_STEP_ACTIVE = True
    _SEARCH_STEP_REMAINING = 1
    completed_result: dict[str, Any] | None = None
    try:
        completed_result = run_development_search(
            panel_path, lock_path, output_directory, confirmation_ledger_root
        )
    except _SearchStepBoundary:
        pass
    finally:
        _SEARCH_STEP_ACTIVE = False
        _SEARCH_STEP_REMAINING = None
        _PARALLEL_ROW_SETS = None
    records = list((output_directory / "checkpoints" / "trials").glob("*/trial.json"))
    active_path = output_directory / "operations" / "active.json"
    active = read_json(active_path) if active_path.is_file() else None
    return {
        "state": "COMPLETE" if completed_result is not None else "PROGRESSED",
        "epistemicStage": "EXPLORATORY",
        "durableTrialCount": len(records),
        "active": active,
        "artifactSha256": completed_result.get("artifactSha256") if completed_result else None,
    }


def _time_stratified_panel_rows(panel_path: Path, manifest: Mapping[str, Any], maximum_rows: int) -> list[dict[str, Any]]:
    """Retain complete chronological blocks spread across the locked period."""
    total_rows = int(manifest.get("rows", 0))
    if maximum_rows < 80:
        raise ValueError("a development rung must permit at least 80 rows")
    if total_rows and total_rows <= maximum_rows:
        return list(iter_jsonl(panel_path))
    basket_size = int(manifest["basketSize"])
    interval = int(manifest["baseIntervalMillis"])
    period = manifest["outcomePeriod"]
    start = _instant_ms(period["startInclusive"])
    end = _instant_ms(period["endExclusive"])
    block_count = 8
    maximum_times = max(1, maximum_rows // basket_size)
    block_times = max(1, maximum_times // block_count)
    duration = block_times * interval
    span = end - start
    intervals = []
    for index in range(block_count):
        center = start + int(span * (index + 0.5) / block_count)
        left = max(start, min(end - duration, center - duration // 2))
        intervals.append((left, min(end, left + duration)))
    rows = [
        row for row in iter_jsonl(panel_path)
        if any(left <= int(row["decisionTimeEpochMillis"]) < right for left, right in intervals)
    ]
    if len(rows) > maximum_rows:
        rows = rows[: maximum_rows - (maximum_rows % basket_size)]
    if len(rows) < 80:
        raise ValueError("time-stratified sampling retained fewer than 80 rows")
    return rows


def _breadth_cells(campaign: Mapping[str, Any], model_index: int) -> list[tuple[str, str, str]]:
    dimensions = campaign["searchDimensions"]
    horizons = list(dimensions["horizons"])
    factors = [value for value in dimensions["factorRepresentations"] if value != "none"]
    if len(horizons) != int(campaign["iterativeSearch"]["breadth"]["trialsPerFamily"]):
        raise ValueError("breadth rung must cover every frozen horizon exactly once")
    return [
        (horizon, "outright-return", "none")
        if index % 2 == 0
        else (horizon, "factor-residual-return", factors[(model_index + index // 2) % len(factors)])
        for index, horizon in enumerate(horizons)
    ]


def _low_fidelity_configuration(model_id: str, configuration: Mapping[str, Any]) -> dict[str, Any]:
    value = dict(configuration)
    if "epochs" in value:
        value["epochs"] = min(4, int(value["epochs"]))
    if "n_estimators" in value:
        value["n_estimators"] = min(100, int(value["n_estimators"]))
    if "max_iter" in value:
        value["max_iter"] = min(50, int(value["max_iter"]))
    return value


def _write_search_status(path: Path, value: Mapping[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            from .artifacts import canonical_json_bytes

            handle.write(canonical_json_bytes(value))
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary_name, path)
    finally:
        Path(temporary_name).unlink(missing_ok=True)


def _checkpointed_trial(
    *,
    output_directory: Path,
    manifest: Mapping[str, Any],
    rows: Sequence[Mapping[str, Any]],
    features: Sequence[str],
    horizon: str,
    target: str,
    factor: str,
    model_id: str,
    configuration: Mapping[str, Any],
    seed: int,
    outer_folds: int,
    rung: str,
    trial_id: str,
    mechanism: str,
    load_bundle: bool = False,
    execution_workers: int = 1,
) -> tuple[dict[str, Any], dict[str, Any] | None]:
    trial_root = output_directory / "checkpoints" / "trials" / trial_id
    trial_path = trial_root / "trial.json"
    bundle_path = trial_root / "bundle.pickle"
    if trial_root.exists():
        if not trial_path.is_file():
            raise ValueError(f"incomplete immutable trial checkpoint: {trial_root}")
        trial = read_json(trial_path)
        validate_trial_ledger_entry(trial, manifest)
        bundle = None
        if trial["status"] == "COMPLETED":
            if not bundle_path.is_file():
                raise ValueError(f"completed checkpoint has no model bundle: {trial_root}")
            artifacts = {item["path"]: item for item in trial["artifactHashes"]}
            relative = str(bundle_path.relative_to(output_directory))
            artifact = artifacts.get(relative, {})
            expected = artifact.get("sha256")
            expected_size = artifact.get("sizeBytes")
            if not expected or (expected_size is not None and bundle_path.stat().st_size != expected_size):
                raise ValueError(f"trial checkpoint bundle identity mismatch: {trial_root}")
            if (not _SEARCH_STEP_ACTIVE or load_bundle) and expected != sha256_file(bundle_path):
                raise ValueError(f"trial checkpoint bundle hash mismatch: {trial_root}")
            if load_bundle:
                with bundle_path.open("rb") as handle:
                    bundle = pickle.load(handle)
        return trial, bundle

    active_path = output_directory / "operations" / "active.json"
    if _SEARCH_STEP_ACTIVE:
        global _SEARCH_STEP_REMAINING
        active = {
            "schemaVersion": "marketlab.alpha-active-trial.v1",
            "campaignId": manifest["campaignId"],
            "candidateId": manifest["candidateId"],
            "trialId": trial_id,
            "rung": rung,
            "modelId": model_id,
            "epistemicStage": "EXPLORATORY",
            "memoryProfileGiB": int(os.environ.get("MARKETLAB_TRIAL_MEMORY_GIB", "0")),
            "state": "PENDING" if (_SEARCH_STEP_REMAINING or 0) < 1 else "RUNNING",
        }
        _write_search_status(active_path, active)
        if (_SEARCH_STEP_REMAINING or 0) < 1:
            raise _SearchStepBoundary(trial_id)
        _SEARCH_STEP_REMAINING -= 1

    if os.environ.get("MARKETLAB_EXPERIMENT_ID"):
        from .budget import require_budget
        require_budget(model=True)
        from experiment import record_trial
        record_trial()
    started = _utc_now()
    selection = {
        "assets": "dynamic-basket",
        "basketSizes": int(manifest["searchDimensions"]["basketSizes"][0]),
        "factorRepresentations": factor,
        "horizons": horizon,
        "targets": target,
        "informationSets": mechanism,
        "modelFamilies": model_id,
        "variants": "unrestricted-sign",
    }
    entry: dict[str, Any] = {
        "schemaVersion": TRIAL_SCHEMA,
        "campaignId": manifest["campaignId"],
        "candidateId": manifest["candidateId"],
        "trialId": trial_id,
        "startedAt": started,
        "completedAt": started,
        "status": "FAILED",
        "seed": seed,
        "selection": selection,
        "metrics": {},
        "artifactHashes": [],
        "configuration": dict(configuration),
        "searchStage": rung,
        "mechanism": mechanism,
        "execution": {
            "scheduler": "disposable-trial-worker-v1" if _SEARCH_STEP_ACTIVE else "family-memory-adaptive-v1",
            "parallelWorkers": int(execution_workers),
            "workerImageDigest": os.environ.get("MARKETLAB_WORKER_IMAGE_DIGEST", "unrecorded"),
        },
    }
    bundle = None
    try:
        timeout_seconds = int(manifest.get("trialTimeoutSeconds", 86400))
        deadline = os.environ.get("MARKETLAB_EXPERIMENT_WORK_DEADLINE")
        if deadline is not None:
            import time
            remaining_seconds = int(float(deadline) - time.time())
            if remaining_seconds <= 0:
                raise TimeoutError("experiment work deadline expired")
            timeout_seconds = min(timeout_seconds, remaining_seconds)
        prior_handler = signal.getsignal(signal.SIGALRM)

        def timed_out(_signum: int, _frame: Any) -> None:
            raise TimeoutError(f"trial exceeded frozen {timeout_seconds}-second timeout")

        signal.signal(signal.SIGALRM, timed_out)
        signal.alarm(timeout_seconds)
        try:
            metrics, bundle = _evaluate_with_market_baseline(
                rows, features, horizon, target, factor, model_id, configuration, seed, outer_folds
            )
        finally:
            signal.alarm(0)
            signal.signal(signal.SIGALRM, prior_handler)
        entry.update(metrics)
        entry.update({
            "completedAt": _utc_now(),
            "status": "COMPLETED",
            "metrics": {
                "meanOuterImprovement": metrics["meanOuterImprovement"],
                "positiveOuterFolds": metrics["positiveOuterFolds"],
                "fitCount": metrics["fitCount"],
            },
        })
    except Exception as error:
        entry.update({"completedAt": _utc_now(), "error": f"{type(error).__name__}: {error}"})

    trial_parent = trial_root.parent
    trial_parent.mkdir(parents=True, exist_ok=True)
    temporary = Path(tempfile.mkdtemp(prefix=f".{trial_id}.", dir=trial_parent))
    try:
        if entry["status"] == "COMPLETED":
            payload = pickle.dumps(bundle, protocol=5)
            temporary_bundle = temporary / "bundle.pickle"
            temporary_bundle.write_bytes(payload)
            relative = str(bundle_path.relative_to(output_directory))
            from .artifacts import sha256_bytes, canonical_json_bytes

            entry["artifactHashes"] = [{"path": relative, "sha256": sha256_bytes(payload), "sizeBytes": len(payload)}]
        validate_trial_ledger_entry(entry, manifest)
        from .artifacts import canonical_json_bytes

        (temporary / "trial.json").write_bytes(canonical_json_bytes(entry))
        os.rename(temporary, trial_root)
    finally:
        if temporary.exists():
            shutil.rmtree(temporary)
    if _SEARCH_STEP_ACTIVE:
        active_path.unlink(missing_ok=True)
    return entry, bundle if load_bundle else None


def _load_checkpoint_bundle(output_directory: Path, trial_id: str) -> dict[str, Any]:
    trial_root = output_directory / "checkpoints" / "trials" / trial_id
    trial = read_json(trial_root / "trial.json")
    if trial.get("status") != "COMPLETED":
        raise ValueError(f"selected checkpoint is not completed: {trial_id}")
    bundle_path = trial_root / "bundle.pickle"
    relative = str(bundle_path.relative_to(output_directory))
    artifacts = {item["path"]: item for item in trial.get("artifactHashes", [])}
    artifact = artifacts.get(relative, {})
    if not bundle_path.is_file() or artifact.get("sizeBytes") != bundle_path.stat().st_size:
        raise ValueError(f"selected checkpoint bundle identity mismatch: {trial_id}")
    if artifact.get("sha256") != sha256_file(bundle_path):
        raise ValueError(f"selected checkpoint bundle hash mismatch: {trial_id}")
    with bundle_path.open("rb") as handle:
        return pickle.load(handle)


def _audit_checkpoint_bundles(output_directory: Path, trials: Sequence[Mapping[str, Any]]) -> int:
    audited = 0
    for trial in trials:
        if trial.get("status") != "COMPLETED":
            continue
        trial_id = str(trial["trialId"])
        trial_root = output_directory / "checkpoints" / "trials" / trial_id
        bundle_path = trial_root / "bundle.pickle"
        relative = str(bundle_path.relative_to(output_directory))
        artifacts = {item["path"]: item for item in trial.get("artifactHashes", [])}
        artifact = artifacts.get(relative, {})
        if not bundle_path.is_file() or artifact.get("sizeBytes") != bundle_path.stat().st_size:
            raise ValueError(f"trial checkpoint bundle identity mismatch: {trial_id}")
        if artifact.get("sha256") != sha256_file(bundle_path):
            raise ValueError(f"trial checkpoint bundle hash mismatch: {trial_id}")
        audited += 1
    return audited


def _run_iterative_development_search(
    panel_path: Path,
    lock_path: Path,
    output_directory: Path,
    confirmation_ledger_root: Path,
    campaign: Mapping[str, Any],
) -> dict[str, Any]:
    """Run the v2 automatic expansion plan with immutable per-trial checkpoints."""
    result_path = output_directory / "search-result.json"
    if result_path.exists():
        result = read_json(result_path)
        if result.get("artifactSha256") not in (None, sha256_file(result_path)):
            raise ValueError("completed search result has an invalid identity")
        result["artifactSha256"] = sha256_file(result_path)
        return result
    output_directory.mkdir(parents=True, exist_ok=True)
    development = campaign["developmentPeriod"]
    panel_manifest = _panel_manifest_for_period(
        panel_path, development["startInclusive"], development["endExclusive"]
    )
    basket_size = int(panel_manifest["basketSize"])
    if basket_size not in campaign["searchDimensions"]["basketSizes"]:
        raise ValueError("panel basket size is outside the iterative campaign")
    iterative = campaign["iterativeSearch"]
    full_maximum = int(iterative["fullDevelopment"]["maximumRows"])
    breadth_maximum = int(iterative["breadth"]["maximumRows"])
    load_started = time.monotonic()
    full_rows = _time_stratified_panel_rows(panel_path, panel_manifest, full_maximum)
    breadth_rows = _time_stratified_panel_rows(panel_path, panel_manifest, breadth_maximum)
    trial_runner = _AdaptiveTrialRunner(breadth_rows, full_rows)
    gpu_identity = _gpu_identity()
    engineering_path = output_directory / "checkpoints" / "engineering.json"
    if not engineering_path.exists():
        from .models import model_availability

        engineering = {
            "schemaVersion": "marketlab.alpha-engineering-checkpoint.v1",
            "campaignId": campaign["campaignId"],
            "createdAt": _utc_now(),
            "predictiveScoresSuppressed": True,
            "promotionUseProhibited": True,
            "breadthRows": len(breadth_rows),
            "fullDevelopmentRows": len(full_rows),
            "panelLoadSeconds": time.monotonic() - load_started,
            "gpuIdentity": gpu_identity,
            "modelAvailability": model_availability(),
        }
        write_once_json(engineering_path, engineering)

    all_feature_names = sorted(set.intersection(*(set(row["features"]) for row in full_rows)))
    feature_schema_hash = canonical_sha256(all_feature_names)
    trials: list[dict[str, Any]] = []
    bundle_references: dict[str, str] = {}
    manifests: dict[str, dict[str, Any]] = {}
    blocked: list[dict[str, str]] = []
    started = _utc_now()
    model_families = list(campaign["searchDimensions"]["modelFamilies"])
    maximum_trials = (
        campaign["searchBudget"]["stageATrialsPerFamily"] * len(model_families)
        + campaign["searchBudget"]["stageBAdditionalTrialsPerSurvivor"]
        * campaign["searchBudget"]["stageBMaxFamiliesPerMechanism"]
        * len(campaign["searchBudget"]["stageBSeeds"])
    )
    active_mechanism_count = sum(
        bool(_feature_names(full_rows, mechanism)) for mechanism in campaign["mechanisms"]
    )
    declared_trial_ceiling = active_mechanism_count * maximum_trials
    completed_counter = 0
    active_candidate_id: str | None = None

    checkpoint_records: dict[str, dict[str, Any]] = {}
    for path in (output_directory / "checkpoints" / "trials").glob("*/trial.json"):
        try:
            record = read_json(path)
            checkpoint_records[str(record["trialId"])] = record
        except (KeyError, OSError, ValueError):
            continue
    initial_checkpoint_count = len(checkpoint_records)

    def checkpoint_health(updated_trial: dict[str, Any] | None = None) -> dict[str, Any]:
        if updated_trial is not None:
            checkpoint_records[str(updated_trial["trialId"])] = updated_trial
        records = list(checkpoint_records.values())
        latest = max(records, key=lambda value: value.get("completedAt", ""), default={})
        latest_selection = latest.get("selection", {})
        latest_execution = latest.get("execution", {})
        return {
            "durableTrialCount": len(records),
            "successfulTrialCount": sum(value.get("status") == "COMPLETED" for value in records),
            "failedTrialCount": sum(value.get("status") == "FAILED" for value in records),
            "latestCheckpointAt": latest.get("completedAt"),
            "latestCheckpointTrialId": latest.get("trialId"),
            "latestCheckpointCandidateId": latest.get("candidateId"),
            "latestCheckpointRung": latest.get("rung") or latest.get("searchStage"),
            "latestCheckpointModelId": (
                latest.get("modelId") or latest_selection.get("modelFamilies")
            ),
            "latestCheckpointWorkerCount": latest_execution.get("parallelWorkers"),
        }

    def publish_status(
        rung: str,
        model_id: str | None = None,
        checkpoint: dict[str, Any] | None = None,
    ) -> None:
        elapsed = max(0.001, time.monotonic() - load_started)
        health = checkpoint_health(checkpoint)
        durable = int(health["durableTrialCount"])
        new_durable = max(0, durable - initial_checkpoint_count)
        _write_search_status(output_directory / "status.json", {
            "schemaVersion": "marketlab.alpha-search-operational-status.v1",
            "campaignId": campaign["campaignId"],
            "basketSize": basket_size,
            "rung": rung,
            "completedTrials": durable,
            "declaredTrialCeiling": declared_trial_ceiling,
            "remainingTrialCeiling": max(0, declared_trial_ceiling - durable),
            "elapsedSeconds": elapsed,
            "meanSecondsPerTrial": elapsed / new_durable if new_durable else None,
            "projectedUpperBoundSeconds": (
                max(0, declared_trial_ceiling - durable)
                * elapsed / new_durable
                if new_durable else None
            ),
            **health,
            "processedTrialsThisRun": new_durable,
            "activeCandidateId": active_candidate_id,
            "activeModelId": model_id,
            "inFlightTrialIds": list(trial_runner.in_flight_trial_ids),
            "inFlightTrialCount": len(trial_runner.in_flight_trial_ids),
            "confirmationOpened": False,
            "scheduler": "disposable-trial-worker-v1" if _SEARCH_STEP_ACTIVE else "bounded-streaming-family-memory-adaptive-v2",
            "configuredWorkerCeiling": trial_runner.maximum_workers,
            "lastSelectedWorkerCount": trial_runner.last_worker_count,
            "workerImageDigest": os.environ.get("MARKETLAB_WORKER_IMAGE_DIGEST", "unrecorded"),
            "updatedAt": _utc_now(),
        })

    for mechanism in campaign["mechanisms"]:
        features = _feature_names(full_rows, mechanism)
        candidate_id = f"{mechanism}-direction-v1"
        active_candidate_id = candidate_id
        if not features:
            blocked.append({"mechanism": mechanism, "candidateId": candidate_id, "reason": "no mechanism-specific causal fields in panel"})
            continue
        dimensions = {
            "assets": ["dynamic-basket"],
            "basketSizes": [basket_size],
            "factorRepresentations": campaign["searchDimensions"]["factorRepresentations"],
            "horizons": campaign["searchDimensions"]["horizons"],
            "targets": campaign["searchDimensions"]["targets"],
            "informationSets": [mechanism],
            "modelFamilies": model_families,
            "variants": ["unrestricted-sign", "time-shift-placebo"],
        }
        manifest = {
            "schemaVersion": SEARCH_SCHEMA,
            "campaignId": campaign["campaignId"],
            "candidateId": candidate_id,
            "mechanism": mechanism,
            "stage": "EXPLORATORY",
            "createdAt": started,
            "userConstraints": campaign["userConstraints"],
            "designConventions": campaign["designConventions"],
            "empiricalClaims": [f"{mechanism} fields may or may not add directional forecast value"],
            "searchDimensions": dimensions,
            "maximumTrials": maximum_trials,
            "trialTimeoutSeconds": int(iterative["trialTimeoutSeconds"]),
            "gpuIdentity": gpu_identity,
            "openedOutcomePeriods": [],
            "limitations": {
                "survivorship": "Confirmation is prohibited where historical membership cannot be reconstructed",
                "sourceTransfer": "Archive-source evidence does not establish transfer to Hyperliquid or another venue",
            },
            "artifacts": [],
        }
        validate_candidate_search_manifest(manifest, campaign)
        manifests[candidate_id] = manifest

        breadth_by_family: dict[str, list[dict[str, Any]]] = defaultdict(list)
        for model_index, model_id in enumerate(model_families):
            configurations = _model_configurations(
                model_id,
                int(iterative["breadth"]["trialsPerFamily"]),
                campaign["searchBudget"]["stageASeeds"][0],
            )
            tasks = []
            for index, (configuration, cell) in enumerate(zip(configurations, _breadth_cells(campaign, model_index))):
                configuration = _low_fidelity_configuration(model_id, configuration)
                trial_id = f"{candidate_id}-{basket_size}-{model_id}-breadth-{index}-seed-{campaign['searchBudget']['stageASeeds'][0]}".replace("_", "-")
                tasks.append({
                    "row_set": "breadth", "output_directory": output_directory,
                    "manifest": manifest, "features": features, "horizon": cell[0],
                    "target": cell[1], "factor": cell[2], "model_id": model_id,
                    "configuration": configuration, "seed": campaign["searchBudget"]["stageASeeds"][0],
                    "outer_folds": int(iterative["breadth"]["outerFolds"]), "rung": "BREADTH",
                    "trial_id": trial_id, "mechanism": mechanism,
                })
            for trial, bundle in trial_runner.run(
                model_id, tasks, on_checkpoint=lambda trial, rung="BREADTH", model=model_id: publish_status(rung, model, trial)
            ):
                trials.append(trial)
                breadth_by_family[model_id].append(trial)
                completed_counter += 1
                publish_status("BREADTH", model_id)

        family_best = []
        for family, values in breadth_by_family.items():
            completed = [value for value in values if value["status"] == "COMPLETED"]
            if not completed:
                continue
            best = max(completed, key=lambda value: (value["positiveOuterFolds"], value["meanOuterImprovement"], value["trialId"]))
            family_best.append((family, best))
        family_best.sort(key=lambda item: (-item[1]["positiveOuterFolds"], -item[1]["meanOuterImprovement"], item[0]))
        promotion = iterative["breadth"]["automaticPromotion"]
        promoted = [family for family, _ in family_best[: int(promotion["rankedFamilies"])]]
        for family, best in family_best:
            improvements = best["outerFoldImprovements"]
            strong = (
                best["meanOuterImprovement"] > float(promotion["strongMeanImprovementAbove"])
                and sum(value > 0 for value in improvements) / len(improvements) >= float(promotion["strongPositiveFoldFraction"])
            )
            if strong and family not in promoted:
                promoted.append(family)
        promoted = promoted[: int(promotion["maximumFamilies"])]

        full_best: list[dict[str, Any]] = []
        for model_id in promoted:
            promotion_record = next(value for family, value in family_best if family == model_id)
            model_index = model_families.index(model_id)
            configurations = _model_configurations(
                model_id,
                int(iterative["fullDevelopment"]["trialsPerFamily"]),
                campaign["searchBudget"]["stageASeeds"][0],
            )
            values = []
            tasks = []
            for index, (configuration, cell) in enumerate(zip(configurations, _stage_a_cells(campaign, model_index, len(configurations)))):
                trial_id = f"{candidate_id}-{basket_size}-{model_id}-full-{index}-seed-{campaign['searchBudget']['stageASeeds'][0]}".replace("_", "-")
                tasks.append({
                    "row_set": "full", "output_directory": output_directory,
                    "manifest": manifest, "features": features, "horizon": cell[0],
                    "target": cell[1], "factor": cell[2], "model_id": model_id,
                    "configuration": configuration, "seed": campaign["searchBudget"]["stageASeeds"][0],
                    "outer_folds": int(iterative["fullDevelopment"]["outerFolds"]),
                    "rung": "FULL_DEVELOPMENT", "trial_id": trial_id, "mechanism": mechanism,
                    "promotion_source_hashes": [canonical_sha256(promotion_record)],
                })
            for trial, bundle in trial_runner.run(
                model_id, tasks, on_checkpoint=lambda trial, rung="FULL_DEVELOPMENT", model=model_id: publish_status(rung, model, trial)
            ):
                trials.append(trial)
                values.append(trial)
                completed_counter += 1
                publish_status("FULL_DEVELOPMENT", model_id)
            completed = [value for value in values if value["status"] == "COMPLETED"]
            if completed:
                full_best.append(max(completed, key=lambda value: (value["positiveOuterFolds"], value["meanOuterImprovement"], value["trialId"])))

        full_gate = iterative["fullDevelopment"]["automaticPromotion"]
        eligible = [
            value for value in full_best
            if value["meanOuterImprovement"] > float(full_gate["meanImprovementAbove"])
            and value["positiveOuterFolds"] >= int(full_gate["minimumPositiveFolds"])
        ]
        eligible.sort(key=lambda value: (-value["positiveOuterFolds"], -value["meanOuterImprovement"], value["trialId"]))
        eligible = eligible[: int(full_gate["maximumFamilies"])]

        robustness_groups = []
        for survivor in eligible:
            selection = survivor["selection"]
            model_id = selection["modelFamilies"]
            configurations = _model_configurations(
                model_id,
                int(iterative["robustness"]["additionalConfigurationsPerFamily"]),
                int(iterative["robustness"]["seeds"][0]),
                extra=True,
            )
            for config_index, configuration in enumerate(configurations):
                seed_results = []
                seeds = [int(seed) for seed in iterative["robustness"]["seeds"]]
                tasks = [
                    {
                        "row_set": "full", "output_directory": output_directory,
                        "manifest": manifest, "features": features,
                        "horizon": selection["horizons"], "target": selection["targets"],
                        "factor": selection["factorRepresentations"], "model_id": model_id,
                        "configuration": configuration, "seed": seed,
                        "outer_folds": int(iterative["robustness"]["outerFolds"]),
                        "rung": "ROBUSTNESS",
                        "trial_id": f"{candidate_id}-{basket_size}-{model_id}-robust-{config_index}-seed-{seed}".replace("_", "-"),
                        "mechanism": mechanism, "load_bundle": False,
                        "promotion_source_hashes": [canonical_sha256(survivor)],
                    }
                    for seed in seeds
                ]
                for (trial, bundle), seed in zip(
                    trial_runner.run(
                        model_id, tasks, on_checkpoint=lambda trial, rung="ROBUSTNESS", model=model_id: publish_status(rung, model, trial)
                    ),
                    seeds,
                ):
                    trials.append(trial)
                    if trial["status"] == "COMPLETED":
                        seed_results.append(trial)
                    completed_counter += 1
                    publish_status("ROBUSTNESS", model_id)
                if len(seed_results) == len(iterative["robustness"]["seeds"]):
                    improvements = [value for trial in seed_results for value in trial["outerFoldImprovements"]]
                    group_id = f"{candidate_id}-{basket_size}-{model_id}-robust-{config_index}-group".replace("_", "-")
                    group = {
                        "campaignId": campaign["campaignId"], "candidateId": candidate_id,
                        "trialId": group_id, "selection": selection,
                        "configuration": dict(configuration), "canonicalSeed": int(iterative["robustness"]["seeds"][0]),
                        "mechanism": mechanism, "outerFoldImprovements": improvements,
                        "meanOuterImprovement": _mean(improvements),
                        "positiveOuterFolds": sum(value > 0 for value in improvements),
                        "status": "COMPLETED",
                    }
                    robustness_groups.append(group)
                    bundle_references[group_id] = str(tasks[0]["trial_id"])

        winners = select_development_winners(robustness_groups, maximum_total=1)
        for winner in winners:
            if _SEARCH_STEP_ACTIVE:
                _write_search_status(output_directory / "operations" / "active.json", {
                    "schemaVersion": "marketlab.alpha-active-trial.v1",
                    "campaignId": campaign["campaignId"],
                    "candidateId": candidate_id,
                    "trialId": f"finalize-{winner['trialId']}",
                    "rung": "FINALIZATION",
                    "modelId": winner["selection"]["modelFamilies"],
                    "epistemicStage": "EXPLORATORY",
                    "memoryProfileGiB": int(os.environ.get("MARKETLAB_TRIAL_MEMORY_GIB", "0")),
                    "state": "RUNNING",
                })
            bundle = _load_checkpoint_bundle(output_directory, bundle_references[winner["trialId"]])
            winner_rows = _rows_with_targets_inside_period(
                full_rows,
                _instant_ms(development["startInclusive"]),
                _instant_ms(development["endExclusive"]),
                winner["selection"]["horizons"],
            )
            placebo_rows = _time_shift_placebo(winner_rows, winner["selection"]["horizons"], HORIZON_MILLIS["7d"])
            try:
                placebo_metrics, _ = _evaluate_with_market_baseline(
                    placebo_rows, _feature_names(placebo_rows, mechanism),
                    winner["selection"]["horizons"], winner["selection"]["targets"],
                    winner["selection"]["factorRepresentations"], winner["selection"]["modelFamilies"],
                    bundle["configuration"], int(winner["canonicalSeed"]), 5,
                )
                placebo = {**placebo_metrics, "status": "COMPLETED"}
            except Exception as error:
                placebo = {"status": "FAILED", "error": f"{type(error).__name__}: {error}"}
            suspicious = (
                placebo.get("status") == "COMPLETED"
                and placebo["positiveOuterFolds"] > len(placebo["outerFoldImprovements"]) // 2
                and placebo["meanOuterImprovement"] > 0.0
            )
            if placebo.get("status") == "FAILED" or suspicious:
                blocked.append({"mechanism": mechanism, "candidateId": candidate_id, "reason": "time-shift integrity audit failed or retained predictive structure"})
                continue
            model_path = output_directory / "models" / f"{winner['trialId']}.pickle"
            model_payload = pickle.dumps(bundle, protocol=5)
            expected_model_hash = sha256_bytes(model_payload)
            if model_path.exists():
                model_hash = sha256_file(model_path)
                if model_hash != expected_model_hash:
                    try:
                        existing_bundle = pickle.loads(model_path.read_bytes())
                    except (OSError, EOFError, pickle.UnpicklingError) as error:
                        raise ValueError(f"existing model artifact hash mismatch: {model_path}") from error
                    compatible = all(
                        existing_bundle.get(key) == bundle.get(key)
                        for key in ("configuration", "featureNames", "symbols", "unknownSymbolPolicy")
                    ) and (
                        existing_bundle.get("marketBaseline", {}).get("featureNames")
                        == bundle.get("marketBaseline", {}).get("featureNames")
                    )
                    if not compatible:
                        raise ValueError(f"existing model artifact hash mismatch: {model_path}")
            else:
                model_hash = write_once_bytes(model_path, model_payload)
            exact_schema = canonical_sha256({
                "featureNames": bundle["featureNames"],
                "marketBaselineFeatureNames": bundle["marketBaseline"]["featureNames"],
                "symbols": bundle["symbols"],
                "unknownSymbolPolicy": bundle["unknownSymbolPolicy"],
            })
            winner["developmentCandidate"] = {
                "candidateId": candidate_id, "trialId": winner["trialId"], "mechanism": mechanism,
                "selection": winner["selection"], "configuration": dict(bundle["configuration"]),
                "seed": int(winner["canonicalSeed"]), "outerFoldImprovements": winner["outerFoldImprovements"],
                "featureSchemaSha256": exact_schema, "searchManifest": manifest,
                "modelArtifactPath": str(model_path.absolute()), "modelArtifactSha256": model_hash,
                "panelSha256": sha256_file(panel_path), "timeShiftPlacebo": placebo,
            }
        manifests[candidate_id]["developmentCandidates"] = [
            winner["developmentCandidate"] for winner in winners if "developmentCandidate" in winner
        ]

    development_candidates = [
        candidate
        for manifest in manifests.values()
        for candidate in manifest.pop("developmentCandidates", [])
    ]
    ranked = select_development_winners(
        [{**candidate, "mechanism": candidate["mechanism"]} for candidate in development_candidates],
        maximum_total=campaign["searchBudget"]["maxFrozenOverall"],
    )
    confirmation_family = _confirmation_family_manifest(
        campaign, ranked, sha256_file(lock_path), [sha256_file(panel_path)], confirmation_ledger_root
    )
    result = {
        "schemaVersion": "marketlab.alpha-development-search-result.v1",
        "campaignId": campaign["campaignId"], "campaignLockSha256": sha256_file(lock_path),
        "panelSha256": sha256_file(panel_path), "basketSize": basket_size,
        "featureSchemaSha256": feature_schema_hash,
        "trialLedger": trials, "developmentCandidates": development_candidates,
        "selected": ranked, "blockedMechanisms": blocked,
        "trialAccounting": {
            "ledgerEntries": len(trials),
            "completed": sum(trial["status"] == "COMPLETED" for trial in trials),
            "failed": sum(trial["status"] != "COMPLETED" for trial in trials),
            "maximumPerActiveMechanism": maximum_trials,
            "budgetUnit": campaign["searchBudget"]["budgetUnit"],
        },
        "confirmationFamily": confirmation_family,
        "openedOutcomePeriods": [],
        "completionState": "AWAITING_CONFIRMATION_REVIEW",
    }
    if _SEARCH_STEP_ACTIVE:
        _write_search_status(output_directory / "operations" / "active.json", {
            "schemaVersion": "marketlab.alpha-active-trial.v1",
            "campaignId": campaign["campaignId"],
            "candidateId": active_candidate_id,
            "trialId": f"audit-search-{basket_size}",
            "rung": "ARTIFACT_AUDIT",
            "modelId": None,
            "epistemicStage": "EXPLORATORY",
            "memoryProfileGiB": int(os.environ.get("MARKETLAB_TRIAL_MEMORY_GIB", "0")),
            "state": "RUNNING",
        })
    result["checkpointArtifactAudit"] = {
        "completedBundlesVerified": _audit_checkpoint_bundles(output_directory, trials)
    }
    result["artifactSha256"] = write_once_json(result_path, result)
    if _SEARCH_STEP_ACTIVE:
        (output_directory / "operations" / "active.json").unlink(missing_ok=True)
    publish_status("AWAITING_CONFIRMATION_REVIEW")
    trial_runner.close()
    return result


def merge_development_searches(search_paths: Sequence[Path], lock_path: Path, output_path: Path) -> dict[str, Any]:
    """Select one frozen family across independently materialized basket sizes."""
    campaign = validate_campaign_lock(read_json(lock_path))
    expected_baskets = set(campaign["searchDimensions"]["basketSizes"])
    if len(search_paths) != len(expected_baskets):
        raise ValueError("basket comparison requires exactly one search artifact per locked basket size")
    inputs = [read_json(path) for path in search_paths]
    if any(value["campaignId"] != campaign["campaignId"] for value in inputs):
        raise ValueError("all development searches must belong to the locked campaign")
    campaign_lock_hash = sha256_file(lock_path)
    if any(value["campaignLockSha256"] != campaign_lock_hash for value in inputs):
        raise ValueError("a development search is bound to a different campaign lock artifact")
    ledger_roots = {
        str(value["confirmationFamily"].get("confirmationLedgerRoot", "")) for value in inputs
    }
    if len(ledger_roots) != 1:
        raise ValueError("development searches must share one preregistered confirmation ledger root")
    observed_baskets = [int(value.get("basketSize", 0)) for value in inputs]
    if set(observed_baskets) != expected_baskets or len(observed_baskets) != len(set(observed_baskets)):
        raise ValueError("development searches do not cover each locked basket size exactly once")
    candidates = [candidate for value in inputs for candidate in value.get("developmentCandidates", [])]
    ranked = select_development_winners(
        [
            {
                **candidate,
                "meanOuterImprovement": _mean(candidate["outerFoldImprovements"]),
                "positiveOuterFolds": sum(value > 0 for value in candidate["outerFoldImprovements"]),
            }
            for candidate in candidates
        ],
        maximum_total=campaign["searchBudget"]["maxFrozenOverall"],
    )
    confirmation_family = _confirmation_family_manifest(
        campaign,
        ranked,
        campaign_lock_hash,
        [value["panelSha256"] for value in inputs],
        Path(inputs[0]["confirmationFamily"]["confirmationLedgerRoot"]),
    )
    merged = {
        "schemaVersion": "marketlab.alpha-development-search-family.v1",
        "campaignId": campaign["campaignId"],
        "campaignLockSha256": sha256_file(lock_path),
        "sourceSearches": [{"path": str(path.absolute()), "sha256": sha256_file(path)} for path in search_paths],
        "selected": ranked,
        "confirmationFamily": confirmation_family,
        "openedOutcomePeriods": [],
    }
    merged["artifactSha256"] = write_once_json(output_path, merged)
    return merged


def _time_shift_placebo(rows: Sequence[Mapping[str, Any]], horizon: str, shift_ms: int) -> list[dict[str, Any]]:
    lookup = {
        (str(row["symbol"]), int(row["decisionTimeEpochMillis"])): row.get("targets", {}).get(horizon)
        for row in rows
    }
    result = []
    for row in rows:
        shifted = lookup.get((str(row["symbol"]), int(row["decisionTimeEpochMillis"]) + shift_ms))
        if shifted is None:
            continue
        value = dict(row)
        value["features"] = dict(row["features"])
        value["targets"] = dict(row["targets"])
        value["targets"][horizon] = shifted
        result.append(value)
    return result


def _atomic_confirmation_marker(path: Path, payload: Mapping[str, Any]) -> None:
    from .artifacts import canonical_json_bytes

    write_once_bytes(path, canonical_json_bytes(payload))


def _confirmation_marker_root(frozen: Mapping[str, Any]) -> Path:
    """Return the stable marker namespace derived from the campaign lock."""

    return Path(frozen["confirmationFamily"]["confirmationLedgerRoot"]).resolve()


def _coverage(rows: Sequence[Mapping[str, Any]], horizon: str) -> dict[str, float | int]:
    horizon_ms = HORIZON_MILLIS[horizon]
    times = sorted({
        int(row["decisionTimeEpochMillis"])
        for row in rows
        if row.get("targets", {}).get(horizon) is not None
    })
    if not times:
        return {"calendarDays": 0.0, "nonOverlappingTargets": 0, "distinctDecisionTimes": 0}
    non_overlapping = 0
    next_allowed = -1
    for decision_time in times:
        if decision_time >= next_allowed:
            non_overlapping += 1
            next_allowed = decision_time + horizon_ms
    return {
        "calendarDays": (times[-1] + horizon_ms - times[0]) / 86_400_000,
        "nonOverlappingTargets": non_overlapping,
        "distinctDecisionTimes": len(times),
    }


def run_confirmation(
    panel_path: Path, frozen_path: Path, expected_frozen_sha256: str, output_path: Path
) -> dict[str, Any]:
    """Atomically open one confirmation period, then evaluate its frozen model."""
    from .artifact_commands import verify_frozen_inputs

    if sha256_file(frozen_path) != expected_frozen_sha256:
        raise ValueError("frozen candidate file differs from its externally registered SHA-256")
    frozen = verify_frozen_inputs(frozen_path)
    campaign = read_json(Path(frozen["campaignLockPath"]))
    validate_frozen_candidate_lock(frozen, campaign, frozen["searchManifest"])
    if panel_path.resolve() != Path(frozen["confirmationPanelPath"]).resolve():
        raise ValueError("confirmation must use the exact panel path frozen before outcome access")
    if sha256_file(panel_path) != frozen["confirmationPanelSha256"]:
        raise ValueError("confirmation panel hash differs from the frozen lock")
    root = _confirmation_marker_root(frozen)
    frozen_hash = canonical_sha256(frozen)
    marker_path = confirmation_marker_path(frozen, root)
    if marker_path.is_symlink():
        raise ValueError("confirmation marker must not be a symbolic link")
    if marker_path.exists():
        marker = validate_confirmation_marker(read_json(marker_path), frozen, frozen_hash)
        opened_at = marker["openedAt"]
    else:
        marker_path = validate_confirmation_preflight(frozen, root)
        opened_at = _utc_now()
        marker = confirmation_marker_payload(frozen, frozen_hash, opened_at)
        _atomic_confirmation_marker(marker_path, marker)
        marker = validate_confirmation_marker(read_json(marker_path), frozen, frozen_hash)
    # The marker now permanently records that these outcomes were opened, even if evaluation fails.
    with Path(frozen["modelArtifactPath"]).open("rb") as handle:
        bundle = pickle.load(handle)
    rows = list(iter_jsonl(panel_path))
    start = _instant_ms(frozen["confirmationPeriod"]["startInclusive"])
    end = _instant_ms(frozen["confirmationPeriod"]["endExclusive"])
    rows = _rows_with_targets_inside_period(rows, start, end, bundle["horizon"])
    minimum_days = int(campaign["confirmation"]["minimumCalendarDays"])
    minimum_targets = int(campaign["confirmation"]["minimumNonOverlappingTargets"])
    coverage = _coverage(rows, bundle["horizon"])
    result_base = {
        "schemaVersion": RESULT_SCHEMA,
        "campaignId": frozen["campaignId"],
        "candidateId": frozen["candidateId"],
        "stage": "INCONCLUSIVE",
        "completedAt": _utc_now(),
        "frozenLockSha256": frozen_hash,
        "confirmationId": marker["confirmationId"],
        "selectedTrialId": frozen["selectedTrialId"],
        "confirmationPanelSha256": frozen["confirmationPanelSha256"],
        "confirmationFamilySha256": frozen["confirmationFamilySha256"],
        "openedOutcomePeriods": [{**frozen["confirmationPeriod"], "openedAt": opened_at}],
        "limitations": frozen["limitations"],
        "artifacts": [],
        "openedOutcomePeriod": f"{frozen['confirmationPeriod']['startInclusive']}/{frozen['confirmationPeriod']['endExclusive']}",
    }
    if coverage["calendarDays"] < minimum_days or coverage["nonOverlappingTargets"] < minimum_targets:
        result = {
            **result_base,
            "decision": "INSUFFICIENT_CONFIRMATION_COVERAGE",
            "primaryMetric": {"name": "mean squared return loss", "status": "NOT_EVALUATED"},
            "baselineMetrics": {},
            "inference": {
                "status": "INSUFFICIENT_CONFIRMATION_COVERAGE",
                "coverage": coverage,
                "minimumCalendarDays": minimum_days,
                "minimumNonOverlappingTargets": minimum_targets,
            },
            "strongestBaseline": "NOT_EVALUATED",
            "meanLossImprovement": 0.0,
        }
        validate_confirmation_result(result, frozen, frozen_hash, marker)
        result["artifactSha256"] = write_once_json(output_path, result)
        return result
    x, aligned = _panel_dataset(
        rows,
        bundle["featureNames"],
        bundle["horizon"],
        bundle["modelId"],
        bundle["lookback"],
        bundle.get("symbols"),
    )
    aligned_coverage = _coverage(aligned, bundle["horizon"])
    if aligned_coverage["calendarDays"] < minimum_days or aligned_coverage["nonOverlappingTargets"] < minimum_targets:
        result = {
            **result_base,
            "decision": "INSUFFICIENT_CONFIRMATION_COVERAGE",
            "primaryMetric": {"name": "mean squared return loss", "status": "NOT_EVALUATED"},
            "baselineMetrics": {},
            "inference": {
                "status": "INSUFFICIENT_CONFIRMATION_COVERAGE",
                "coverage": aligned_coverage,
                "minimumCalendarDays": minimum_days,
                "minimumNonOverlappingTargets": minimum_targets,
            },
            "strongestBaseline": "NOT_EVALUATED",
            "meanLossImprovement": 0.0,
        }
        validate_confirmation_result(result, frozen, frozen_hash, marker)
        result["artifactSha256"] = write_once_json(output_path, result)
        return result
    y = _apply_target_state(aligned, bundle["horizon"], bundle["targetState"])
    prediction = bundle["estimator"].predict(x)
    persistence = _apply_target_state_to_values(
        aligned,
        [float(row["features"][f"persistence_return_{bundle['horizon']}"]) for row in aligned],
        bundle["targetState"],
    )
    controls = {
        "zero-return": [0.0] * len(aligned),
        "historical-mean": [float(bundle["historicalMean"])] * len(aligned),
        "persistence": persistence,
        "reversal": [-float(value) for value in persistence],
    }
    market_bundle = bundle["marketBaseline"]
    market_x, market_aligned = _panel_dataset(
        rows,
        market_bundle["featureNames"],
        market_bundle["horizon"],
        market_bundle["modelId"],
        market_bundle["lookback"],
        market_bundle.get("symbols"),
    )
    aligned_ids = [(int(row["decisionTimeEpochMillis"]), str(row["symbol"])) for row in aligned]
    market_ids = [(int(row["decisionTimeEpochMillis"]), str(row["symbol"])) for row in market_aligned]
    if aligned_ids != market_ids:
        raise ValueError("frozen market-only baseline does not align with candidate confirmation rows")
    controls["strongest-market-only"] = market_bundle["estimator"].predict(market_x)
    improvement, strongest = strongest_baseline_improvement(y, prediction, controls)
    candidate_loss = squared_losses(y, prediction)
    baseline_loss = squared_losses(y, controls[strongest])
    differentials = [baseline - candidate for baseline, candidate in zip(baseline_loss, candidate_loss)]
    raw_p_value = _clustered_hac_p_value(
        [int(row["decisionTimeEpochMillis"]) for row in aligned], differentials, HORIZON_MILLIS[bundle["horizon"]]
    )
    inference = (
        {"status": "INVALID_HAC", "reason": "long-run variance is non-positive or insufficient"}
        if raw_p_value is None
        else {
            "rawPValue": raw_p_value,
            "adjustedPValue": 1.0,
            "status": "PENDING_CONFIRMATION_FAMILY_HOLM",
        }
    )
    decision = (
        "INVALID_CONFIRMATION_INFERENCE"
        if raw_p_value is None
        else ("PRIMARY_LOSS_IMPROVED" if improvement > 0 else "PRIMARY_LOSS_NOT_IMPROVED")
    )
    result = {
        **result_base,
        "decision": decision,
        "primaryMetric": {"name": "mean squared return loss", "value": _mean(squared_losses(y, prediction)), "baseline": _mean(squared_losses(y, controls[strongest])), "improvement": improvement},
        "baselineMetrics": {name: _mean(squared_losses(y, values)) for name, values in controls.items()},
        "inference": inference,
        "strongestBaseline": strongest, "meanLossImprovement": improvement,
    }
    validate_confirmation_result(result, frozen, frozen_hash, marker)
    result["artifactSha256"] = write_once_json(output_path, result)
    return result


def _apply_target_state(rows: Sequence[Mapping[str, Any]], horizon: str, state: Mapping[str, Any]) -> Any:
    """Apply development-fitted factor weights and betas without refitting on confirmation."""
    import numpy as np

    values = np.asarray([float(row["targets"][horizon]) for row in rows], dtype=np.float64)
    return _apply_target_state_to_values(rows, values, state)


def _apply_target_state_to_values(
    rows: Sequence[Mapping[str, Any]], values: Sequence[float], state: Mapping[str, Any]
) -> Any:
    """Apply one frozen target transform to labels or causal baseline returns."""
    import numpy as np

    y = np.asarray(values, dtype=np.float64)
    if len(rows) != len(y):
        raise ValueError("target transform row/value lengths differ")
    if state["target"] == "outright-return":
        return y
    factor_state = state["factor"]
    kind = factor_state["kind"]
    by_time: dict[int, list[int]] = defaultdict(list)
    for index, row in enumerate(rows):
        by_time[int(row["decisionTimeEpochMillis"])].append(index)
    factor = np.zeros(len(rows), dtype=np.float64)
    for indices in by_time.values():
        lookup = {str(rows[index]["symbol"]): float(y[index]) for index in indices}
        if kind == "none":
            value = 0.0
        elif kind in {"btc", "eth"}:
            if kind.upper() not in lookup:
                raise ValueError(f"mandatory factor asset {kind.upper()} is missing at a confirmation time")
            value = lookup[kind.upper()]
        elif kind == "equal-weight-basket":
            value = sum(lookup.values()) / len(lookup)
        elif kind == "first-principal-component":
            if not set(factor_state["symbols"]).issubset(set(lookup)):
                raise ValueError("frozen PCA factor requires every development factor asset")
            vector = np.zeros(len(factor_state["symbols"]))
            for position, symbol in enumerate(factor_state["symbols"]):
                vector[position] = lookup[symbol]
            value = float((vector - np.asarray(factor_state["center"])) @ np.asarray(factor_state["weights"]))
        else:
            raise ValueError(f"unsupported frozen factor {kind}")
        factor[indices] = value
    residual = y.copy()
    for index, row in enumerate(rows):
        residual[index] -= float(state["betas"].get(str(row["symbol"]), 0.0)) * factor[index]
    return residual


def _clustered_hac_p_value(times: Sequence[int], differentials: Sequence[float], horizon_ms: int) -> float | None:
    """One-sided test that mean baseline loss minus candidate loss is positive."""
    by_time: dict[int, list[float]] = defaultdict(list)
    for time, value in zip(times, differentials):
        by_time[int(time)].append(float(value))
    ordered = sorted((time, _mean(values)) for time, values in by_time.items())
    if len(ordered) < 3:
        return None
    values = [value for _, value in ordered]
    mean = _mean(values)
    spacing = min(right[0] - left[0] for left, right in zip(ordered, ordered[1:]) if right[0] > left[0])
    lag = min(len(values) - 2, max(1, math.ceil(horizon_ms / spacing)))
    centered = [value - mean for value in values]
    long_run = sum(value * value for value in centered) / len(values)
    for offset in range(1, lag + 1):
        covariance = sum(centered[index] * centered[index - offset] for index in range(offset, len(values))) / len(values)
        long_run += 2.0 * (1.0 - offset / (lag + 1.0)) * covariance
    scale = sum(value * value for value in values) / len(values)
    if long_run <= max(1e-30, scale * 1e-14):
        return None
    z = mean / math.sqrt(long_run / len(values))
    return min(1.0, max(0.0, 0.5 * math.erfc(z / math.sqrt(2.0))))


def finalize_confirmation_family(
    confirmation_paths: Sequence[Path],
    confirmation_sha256s: Sequence[str],
    family_manifest_path: Path,
    output_path: Path,
) -> dict[str, Any]:
    """Apply the locked Holm correction once every frozen result is available."""
    if len(confirmation_paths) != len(confirmation_sha256s):
        raise ValueError("every confirmation result requires its externally registered SHA-256")
    for path, expected_sha256 in zip(confirmation_paths, confirmation_sha256s):
        if sha256_file(path) != expected_sha256:
            raise ValueError(f"confirmation result differs from its externally registered SHA-256: {path}")
    family_source = read_json(family_manifest_path)
    family_manifest = validate_confirmation_family_manifest(
        family_source.get("confirmationFamily", family_source)
    )
    expected = {
        item["candidateId"]: item for item in family_manifest["selectedCandidates"]
    }
    if not expected:
        raise ValueError("confirmation family contains no frozen candidates")
    results = [read_json(path) for path in confirmation_paths]
    candidate_ids = [str(result.get("candidateId")) for result in results]
    if set(candidate_ids) != set(expected) or len(candidate_ids) != len(set(candidate_ids)):
        raise ValueError("confirmation results must exactly match the frozen family candidate set")
    family_hash = canonical_sha256(family_manifest)
    for result in results:
        if result.get("schemaVersion") != RESULT_SCHEMA:
            raise ValueError("confirmation family contains an unsupported result schema")
        if result.get("stage") != "INCONCLUSIVE":
            raise ValueError("individual confirmation results must remain INCONCLUSIVE until family finalization")
        if result.get("campaignId") != family_manifest["campaignId"]:
            raise ValueError("confirmation result campaign does not match family manifest")
        if result.get("selectedTrialId") != expected[result["candidateId"]]["trialId"]:
            raise ValueError("confirmation result trial does not match family manifest")
        if result.get("confirmationFamilySha256") != family_hash:
            raise ValueError("confirmation result is not bound to this family manifest")
    results.sort(key=lambda result: result["candidateId"])
    raw_values = [
        float(result["inference"]["rawPValue"])
        if result["inference"].get("status") == "PENDING_CONFIRMATION_FAMILY_HOLM"
        else 1.0
        for result in results
    ]
    adjusted = holm_adjust(raw_values)
    decisions = []
    for result, p_value in zip(results, adjusted):
        valid = result["inference"].get("status") == "PENDING_CONFIRMATION_FAMILY_HOLM"
        improvement = float(result["primaryMetric"].get("improvement", 0.0))
        passed = valid and improvement > 0 and p_value <= 0.05
        stage = "BLIND_VALIDATED" if passed else ("REJECTED" if valid else "INCONCLUSIVE")
        decisions.append({
            "candidateId": result["candidateId"],
            "stage": stage,
            "rawPValue": result["inference"].get("rawPValue"),
            "holmAdjustedPValue": p_value,
            "meanLossImprovement": improvement,
            "decision": (
                "PRIMARY_FORECAST_GATE_PASSED"
                if passed
                else ("PRIMARY_FORECAST_GATE_FAILED" if valid else result["decision"])
            ),
        })
    family = {
        "schemaVersion": "marketlab.alpha-confirmation-family-result.v1",
        "campaignId": family_manifest["campaignId"],
        "completedAt": _utc_now(),
        "multiplicity": family_manifest["multiplicity"],
        "confirmationFamilySha256": family_hash,
        "candidateResults": decisions,
        "familyManifest": {"path": str(family_manifest_path.absolute()), "sha256": sha256_file(family_manifest_path)},
        "sourceArtifacts": [
            {"path": str(path.absolute()), "sha256": sha256_file(path)} for path in confirmation_paths
        ],
    }
    family["artifactSha256"] = write_once_json(output_path, family)
    return family
