from __future__ import annotations

import argparse
import csv
import hashlib
import json
import math
import pickle
import random
import zipfile
from collections import defaultdict
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import numpy as np

from .binance_archive import ArchiveRequest, acquire_binance_archives

HOUR = 3_600_000
YEAR_HOURS = 8_760
ASSETS = ("BTC", "ETH", "HYPE", "LIT", "NEAR", "PUMP", "SOL", "WLD", "XRP", "ZEC")
SYMBOLS = {asset: f"{asset}USDT" for asset in ASSETS}
MARKET_FEATURES = {
    "latest_return", "btc_latest_return", "log_rv_1h", "log_rv_24h",
    "hour_sin", "hour_cos", "day_sin", "day_cos",
}


def sha256(path: Path) -> str:
    if path.is_dir():
        manifest = path / "manifest.json"
        path = manifest if manifest.exists() else path / "frozen-models.json"
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def read_json(path: Path) -> dict[str, Any]:
    value = json.loads(path.read_text())
    if not isinstance(value, dict):
        raise ValueError(f"expected JSON object: {path}")
    return value


def write_exclusive(path: Path, value: Any) -> str:
    path.parent.mkdir(parents=True, exist_ok=True)
    payload = (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()
    with path.open("xb") as handle:
        handle.write(payload)
    return hashlib.sha256(payload).hexdigest()


def model_bundle(frozen_path: Path) -> tuple[dict[str, Any], dict[str, Any], Path]:
    if frozen_path.is_dir():
        frozen_path = frozen_path / "frozen-models.json"
    manifest = read_json(frozen_path)
    selected = [item for item in manifest["models"] if item["target"] == "variance_1h"]
    if len(selected) != 1:
        raise ValueError("frozen model set must contain exactly one variance_1h model")
    info = selected[0]
    artifact = frozen_path.parent / info["modelArtifact"]
    if sha256(artifact) != info["modelArtifactSha256"]:
        raise ValueError("variance model artifact hash mismatch")
    stored = pickle.loads(artifact.read_bytes())
    return info, stored, artifact


def load_features(path: Path, stored: dict[str, Any]) -> tuple[list[dict[str, Any]], np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    if path.is_dir():
        manifest = read_json(path / "manifest.json")
        path = path.parents[1] / manifest["outputUri"]
    rows = [json.loads(line) for line in path.read_text().splitlines() if line]
    expected = list(stored["features"])
    numeric = [name for name in expected if not name.startswith("symbol_")]
    symbol_names = [name for name in expected if name.startswith("symbol_")]
    if any(set(row["features"]) != set(numeric) for row in rows):
        raise ValueError("feature schema mismatch")
    symbols = np.asarray([row["symbol"] for row in rows])
    if set(symbols) != set(ASSETS):
        raise ValueError("feature panel must contain the frozen ten-asset universe")
    x = np.asarray([
        [float(row["features"][name]) for name in numeric]
        + [1.0 if row["symbol"] == name.removeprefix("symbol_") else 0.0 for name in symbol_names]
        for row in rows
    ], dtype=np.float64)
    candidate = np.asarray(stored["model"].predict(x), dtype=np.float64)
    market_indices = [index for index, name in enumerate(expected) if name in MARKET_FEATURES]
    design = np.column_stack([x[:, market_indices], symbols])
    baseline = np.asarray(stored["baseline"].predict(design), dtype=np.float64)
    times = np.asarray([int(row["decisionTimeEpochMillis"]) for row in rows], dtype=np.int64)
    if not np.isfinite(candidate).all() or not np.isfinite(baseline).all():
        raise ValueError("model emitted non-finite variance forecasts")
    return rows, symbols, times, candidate, baseline


def hourly_forecasts(rows, symbols, times, candidate, baseline):
    result: dict[tuple[int, str], tuple[float, float]] = {}
    for row, symbol, at, social, market in zip(rows, symbols, times, candidate, baseline):
        if at % HOUR == 0:
            key = (int(at), str(symbol))
            if key in result:
                raise ValueError(f"duplicate hourly feature row {key}")
            result[key] = (float(social), float(market))
    return result


def hourly_returns(rows: list[dict[str, Any]]) -> dict[tuple[int, str], float]:
    returns = {(int(row["decisionTimeEpochMillis"]), row["symbol"]): row.get("next15mReturn") for row in rows}
    result = {}
    hourly_times = sorted({at for at, _ in returns if at % HOUR == 0})
    for at in hourly_times:
        for asset in ASSETS:
            values = [returns.get((at + offset * 15 * 60_000, asset)) for offset in range(4)]
            if any(value is None or not math.isfinite(float(value)) for value in values):
                raise ValueError(f"incomplete next-hour return at {asset} {at}")
            result[(at, asset)] = math.fsum(float(value) for value in values)
    return result


def funding_requests(month: str) -> list[ArchiveRequest]:
    return [ArchiveRequest("um", "fundingRate", SYMBOLS[asset], month) for asset in ASSETS]


def acquire_funding(frozen: Path, expected_sha: str, output: Path) -> dict[str, Any]:
    if sha256(frozen) != expected_sha:
        raise ValueError("freeze hash mismatch")
    freeze = read_json(frozen)
    requests = funding_requests(freeze["fundingMonth"])
    if [request.uri for request in requests] != freeze["fundingRequestUris"]:
        raise ValueError("funding request family differs from freeze")
    return acquire_binance_archives(requests, output, max_workers=2)


def parse_funding(manifest_path: Path, month: str) -> dict[str, list[tuple[int, float]]]:
    manifest = read_json(manifest_path)
    result: dict[str, list[tuple[int, float]]] = {}
    start = int(datetime.strptime(month, "%Y-%m").replace(tzinfo=timezone.utc).timestamp() * 1000)
    year, mon = map(int, month.split("-")); next_year, next_mon = (year + 1, 1) if mon == 12 else (year, mon + 1)
    end = int(datetime(next_year, next_mon, 1, tzinfo=timezone.utc).timestamp() * 1000)
    for entry in manifest["entries"]:
        if entry["status"] != "ACQUIRED":
            raise ValueError(f"funding archive unavailable: {entry['uri']}")
        symbol = entry["request"]["symbol"]
        asset_matches = [asset for asset, value in SYMBOLS.items() if value == symbol]
        if len(asset_matches) != 1:
            raise ValueError(f"unexpected funding symbol {symbol}")
        archive = manifest_path.parent / entry["archiveObject"]["path"]
        if sha256(archive) != entry["archiveObject"]["sha256"]:
            raise ValueError(f"funding archive hash mismatch: {symbol}")
        events = []
        with zipfile.ZipFile(archive) as zipped:
            names = [name for name in zipped.namelist() if not name.endswith("/")]
            if len(names) != 1:
                raise ValueError(f"funding archive must contain one CSV: {symbol}")
            with zipped.open(names[0]) as raw:
                lines = (line.decode("utf-8-sig") for line in raw)
                for row in csv.DictReader(lines):
                    timestamp = next((row.get(key) for key in ("calc_time", "fundingTime", "funding_time") if row.get(key)), None)
                    rate = next((row.get(key) for key in ("last_funding_rate", "fundingRate", "funding_rate") if row.get(key)), None)
                    if timestamp is None or rate is None:
                        raise ValueError(f"unsupported funding CSV schema: {symbol}")
                    at, value = int(timestamp), float(rate)
                    if start <= at < end:
                        events.append((at, value))
        events.sort()
        if not events or len({at for at, _ in events}) != len(events):
            raise ValueError(f"missing or duplicate funding events: {symbol}")
        if any(events[index][0] - events[index - 1][0] > 8 * HOUR + 1_000 for index in range(1, len(events))):
            raise ValueError(f"funding event gap exceeds eight hours: {symbol}")
        result[asset_matches[0]] = events
    if set(result) != set(ASSETS):
        raise ValueError("funding manifest does not cover all ten assets")
    return result


def metrics(values: np.ndarray) -> dict[str, float]:
    mean = float(np.mean(values)); variance = float(np.var(values)); std = math.sqrt(variance)
    cumulative = np.cumsum(values); peaks = np.maximum.accumulate(cumulative); drawdown = cumulative - peaks
    tail_count = max(1, math.ceil(len(values) * 0.05))
    return {
        "annualizedMeanLogReturn": YEAR_HOURS * mean,
        "annualizedVolatility": math.sqrt(YEAR_HOURS) * std,
        "annualizedSharpe": math.sqrt(YEAR_HOURS) * mean / std if std else 0.0,
        "annualizedCertaintyEquivalentGamma4": YEAR_HOURS * mean - 2.0 * YEAR_HOURS * variance,
        "expectedShortfall5PctHourly": float(np.mean(np.sort(values)[:tail_count])),
        "maximumLogDrawdown": float(np.min(drawdown)),
    }


def hac_standard_error(values: np.ndarray, lag: int = 24) -> float:
    centered = values - np.mean(values); n = len(values)
    long_run = float(np.dot(centered, centered) / n)
    for offset in range(1, min(lag, n - 1) + 1):
        covariance = float(np.dot(centered[offset:], centered[:-offset]) / n)
        long_run += 2.0 * (1.0 - offset / (lag + 1.0)) * covariance
    return math.sqrt(max(long_run, 0.0) / n)


def block_bootstrap_difference(social: np.ndarray, market: np.ndarray, seed: int = 20260826) -> list[float]:
    utility = (social - 2.0 * social * social) - (market - 2.0 * market * market)
    rng = random.Random(seed); n = len(utility); block = 24; samples = []
    for _ in range(2000):
        indices = []
        while len(indices) < n:
            start = rng.randrange(0, max(1, n - block + 1)); indices.extend(range(start, min(start + block, n)))
        samples.append(YEAR_HOURS * float(np.mean(utility[np.asarray(indices[:n])])) )
    samples.sort()
    return [samples[int(0.025 * len(samples))], samples[int(0.975 * len(samples)) - 1]]


def replay(times, returns, forecasts, targets, funding, kind: str, cost_bps: float):
    prior = {asset: 0.0 for asset in ASSETS}; portfolio=[]; rows=[]; total_turnover=0.0; total_funding=0.0
    for at in times:
        weights = {}
        for asset in ASSETS:
            if kind == "fixed": multiplier = 1.0
            else:
                prediction = forecasts[(at, asset)][0 if kind == "social" else 1]
                sigma = math.sqrt(math.exp(max(-30.0, min(30.0, prediction))))
                multiplier = min(1.0, targets[asset] / sigma)
            weights[asset] = 0.1 * multiplier
        turnover = math.fsum(abs(weights[a] - prior[a]) for a in ASSETS)
        funding_cost = math.fsum(weights[a] * math.fsum(rate for event, rate in funding[a] if at < event <= at + HOUR) for a in ASSETS)
        gross = math.fsum(weights[a] * returns[(at, a)] for a in ASSETS)
        net = gross - funding_cost - cost_bps / 10_000.0 * turnover
        portfolio.append(net); total_turnover += turnover; total_funding += funding_cost
        rows.append({"decisionTimeEpochMillis":at,"strategy":kind,"grossReturn":gross,"funding":funding_cost,"turnover":turnover,"netReturn":net,"grossExposure":sum(weights.values())})
        prior = weights
    exit_turnover = math.fsum(abs(value) for value in prior.values())
    portfolio[-1] -= cost_bps / 10_000.0 * exit_turnover; rows[-1]["netReturn"] = portfolio[-1]; rows[-1]["turnover"] += exit_turnover; total_turnover += exit_turnover
    values=np.asarray(portfolio)
    return rows, {**metrics(values),"totalTurnover":total_turnover,"totalFunding":total_funding,"meanGrossExposure":float(np.mean([r['grossExposure'] for r in rows]))}, values


def freeze(args):
    lock=read_json(Path(args.lock)); implementation=Path(__file__); frozen_models=Path(args.frozen_models); development=Path(args.development_features); evaluation=Path(args.evaluation_features)
    development_sha=read_json(development / "manifest.json")["outputSha256"]
    evaluation_sha=read_json(evaluation / "manifest.json")["outputSha256"]
    for identity,path,key in ((sha256(frozen_models),frozen_models,"frozenModelSetSha256"),(development_sha,development,"developmentFeatureRowsSha256"),(evaluation_sha,evaluation,"evaluationFeatureRowsSha256")):
        if identity!=lock["artifacts"][key]: raise ValueError(f"registered hash mismatch: {path}")
    info,_,artifact=model_bundle(frozen_models)
    value={"schemaVersion":"marketlab.variance-exposure-freeze.v1","candidateId":lock["candidateId"],"stage":"EXPLORATORY","lockPath":str(Path(args.lock).resolve()),"lockSha256":sha256(Path(args.lock)),"implementationPath":str(implementation.resolve()),"implementationSha256":sha256(implementation),"frozenModelSetPath":str(frozen_models.resolve()),"frozenModelSetSha256":sha256(frozen_models),"modelArtifactPath":str(artifact.resolve()),"modelArtifactSha256":info["modelArtifactSha256"],"developmentFeaturesPath":str(development.resolve()),"developmentFeatureRowsSha256":development_sha,"evaluationFeaturesPath":str(evaluation.resolve()),"evaluationFeatureRowsSha256":evaluation_sha,"fundingMonth":lock["fundingMonth"],"fundingRequestUris":[r.uri for r in funding_requests(lock["fundingMonth"])],"openedOutcomePeriod":lock["evaluationPeriod"],"paperTradingAuthorized":False,"liveTradingAuthorized":False}
    digest=write_exclusive(Path(args.output),value); print(json.dumps({"artifactSha256":digest,"freeze":value},sort_keys=True))


def evaluate(args):
    frozen_path=Path(args.frozen)
    if sha256(frozen_path)!=args.frozen_sha256: raise ValueError("freeze hash mismatch")
    f=read_json(frozen_path)
    for key,path_key in (("frozenModelSetSha256","frozenModelSetPath"),("developmentFeatureRowsSha256","developmentFeaturesPath"),("evaluationFeatureRowsSha256","evaluationFeaturesPath"),("modelArtifactSha256","modelArtifactPath"),("implementationSha256","implementationPath"),("lockSha256","lockPath")):
        path=Path(f[path_key])
        identity=read_json(path / "manifest.json")["outputSha256"] if key.endswith("FeatureRowsSha256") else sha256(path)
        if identity!=f[key]: raise ValueError(f"frozen artifact changed: {path_key}")
    _,stored,_=model_bundle(Path(f["frozenModelSetPath"]))
    dev=load_features(Path(f["developmentFeaturesPath"]),stored); ev=load_features(Path(f["evaluationFeaturesPath"]),stored)
    dev_fc=hourly_forecasts(*dev); ev_fc=hourly_forecasts(*ev); ev_returns=hourly_returns(ev[0])
    targets={a:float(np.median([math.sqrt(math.exp(max(-30,min(30,v[1])))) for (t,s),v in dev_fc.items() if s==a])) for a in ASSETS}
    times=sorted({t for t,s in ev_fc})
    if any((t,a) not in ev_returns or (t,a) not in ev_fc for t in times for a in ASSETS): raise ValueError("evaluation panel is incomplete")
    funding=parse_funding(Path(args.funding_manifest),f["fundingMonth"])
    all_rows=[]; scenarios={}; arrays={}
    for bps in (5.0,10.0):
        scenarios[str(int(bps))]={}
        for kind in ("fixed","market","social"):
            rows,m,value=replay(times,ev_returns,ev_fc,targets,funding,kind,bps); all_rows+=rows; scenarios[str(int(bps))][kind]=m; arrays[(bps,kind)]=value
    gate_by_cost={}
    for bps in (5.0,10.0):
        s=scenarios[str(int(bps))]["social"]; m=scenarios[str(int(bps))]["market"]
        gate_by_cost[str(int(bps))]={"certaintyEquivalentImproved":s["annualizedCertaintyEquivalentGamma4"]>m["annualizedCertaintyEquivalentGamma4"],"expectedShortfallImproved":s["expectedShortfall5PctHourly"]>m["expectedShortfall5PctHourly"],"maximumDrawdownImproved":s["maximumLogDrawdown"]>m["maximumLogDrawdown"],"certaintyEquivalentDifference":s["annualizedCertaintyEquivalentGamma4"]-m["annualizedCertaintyEquivalentGamma4"],"hacStandardErrorHourlyUtilityDifference":hac_standard_error((arrays[(bps,"social")]-2*arrays[(bps,"social")]**2)-(arrays[(bps,"market")]-2*arrays[(bps,"market")]**2)),"blockBootstrap95PctAnnualizedCeDifference":block_bootstrap_difference(arrays[(bps,"social")],arrays[(bps,"market")])}
    passed=all(v["certaintyEquivalentImproved"] and (v["expectedShortfallImproved"] or v["maximumDrawdownImproved"]) for v in gate_by_cost.values())
    rows_path=Path(args.output).with_suffix(".rows.jsonl")
    with rows_path.open("x") as h:
        for row in all_rows:h.write(json.dumps(row,sort_keys=True)+"\n")
    result={"schemaVersion":"marketlab.variance-exposure-result.v1","candidateId":f["candidateId"],"stage":"EXPLORATORY" if passed else "REJECTED","decision":"EXPLORATORY_GATE_PASSED" if passed else "EXPLORATORY_GATE_FAILED","phaseTwoAuthorized":passed,"predictiveDirectionTested":False,"tradabilityTested":False,"evaluationRows":len(times)*len(ASSETS),"hourlyDecisions":len(times),"targets":targets,"scenarios":scenarios,"gateByCostBps":gate_by_cost,"portfolioRowsPath":str(rows_path),"portfolioRowsSha256":sha256(rows_path),"limitations":["Strategy was designed after July forecast outcomes were opened.","Five and ten basis points are execution stresses, not observed fills.","No spread, slippage, latency, borrow, queue, paper, or live claim was tested."],"paperTradingAuthorized":False,"liveTradingAuthorized":False}
    digest=write_exclusive(Path(args.output),result); print(json.dumps({"artifactSha256":digest,"result":result},sort_keys=True))


def main(argv=None):
    p=argparse.ArgumentParser(); sub=p.add_subparsers(dest="command",required=True)
    fz=sub.add_parser("freeze"); fz.add_argument("--lock",required=True); fz.add_argument("--development-features",required=True); fz.add_argument("--evaluation-features",required=True); fz.add_argument("--frozen-models",required=True); fz.add_argument("--output",required=True); fz.set_defaults(fn=freeze)
    ac=sub.add_parser("acquire-funding"); ac.add_argument("--frozen",required=True); ac.add_argument("--frozen-sha256",required=True); ac.add_argument("--output",required=True); ac.set_defaults(fn=lambda a: print(json.dumps(acquire_funding(Path(a.frozen),a.frozen_sha256,Path(a.output)),sort_keys=True)))
    ev=sub.add_parser("evaluate"); ev.add_argument("--frozen",required=True); ev.add_argument("--frozen-sha256",required=True); ev.add_argument("--funding-manifest",required=True); ev.add_argument("--output",required=True); ev.set_defaults(fn=evaluate)
    args=p.parse_args(argv); args.fn(args)
if __name__=="__main__":main()
