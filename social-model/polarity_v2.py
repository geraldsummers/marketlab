#!/usr/bin/env python3
"""Run the registered polarity-shock V2 discovery family."""
import argparse, json
from pathlib import Path
import event_conditioned as event

def run(args):
    output=Path(args.output); output.mkdir(parents=True,exist_ok=False)
    lock_path=Path(args.program_lock); rows,input_identity=event.materialize(Path(args.input_root),event.read_json(lock_path))
    panel=output/"panel.jsonl"
    with panel.open("x") as handle:
        for row in rows: handle.write(json.dumps(row,sort_keys=True,separators=(",",":"))+"\n")
    variants=[event.evaluate_variant(rows,"polarity_surprise",horizon,estimator,args.seed) for horizon in event.HORIZONS for estimator in event.ESTIMATORS]
    adjusted=event.holm([row["twoSidedPValue"] for row in variants])
    for row,value in zip(variants,adjusted):
        row["holmAdjustedPValue"]=value
        row["passesAllDevelopmentGates"]=row["meanLossImprovement"]>0 and row["positiveFolds"]>=4 and row["positiveAssets"]>=7 and value<.05 and row["netMeanReturn5Bps"]>0 and row["netMeanReturn10Bps"]>0 and row["minimumFoldCoverage"]>=.03 and row["minimumFoldActedRows"]>=100
    passing=[row for row in variants if row["passesAllDevelopmentGates"]]
    winner=max(passing,key=lambda row:row["meanLossImprovement"]) if passing else None
    report={"schemaVersion":"marketlab.polarity-shock-direction-v2-development.v1","candidateId":"polarity-shock-direction-v2","classification":"AVAILABILITY_BIASED_RETROSPECTIVE_DISCOVERY","stage":"FROZEN_CANDIDATE" if winner else "REJECTED","promotionCeilingOfHistoricalEvidence":"EXPLORATORY","dataBiases":list(event.BIAS_LEDGER),"targetLeakageAllowed":False,"period":{"startInclusive":"2025-11-03T00:00:00Z","endExclusive":"2026-07-01T00:00:00Z"},"inputManifestSetSha256":input_identity,"programLockSha256":event.sha256(lock_path),"panelSha256":event.sha256(panel),"panelRows":len(rows),"familySize":4,"variants":variants,"winner":winner["variantId"] if winner else None,"prospectiveActivation":{"developmentPassed":winner is not None,"candidateFrozen":False,"outcomeSealingReady":False,"eligible":False},"paperTradingAuthorized":False,"liveTradingAuthorized":False}
    report_path=output/"report.json"; report_path.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n")
    ledger=output/"trial-ledger.json"; ledger.write_text(json.dumps({"schemaVersion":"marketlab.polarity-shock-v2-trial-ledger.v1","trials":variants},indent=2,sort_keys=True)+"\n")
    manifest={"schemaVersion":"marketlab.polarity-shock-v2-artifacts.v1","panelSha256":event.sha256(panel),"reportSha256":event.sha256(report_path),"trialLedgerSha256":event.sha256(ledger)}
    (output/"manifest.json").write_text(json.dumps(manifest,indent=2,sort_keys=True)+"\n")
    print(json.dumps({"stage":report["stage"],"winner":report["winner"],"reportSha256":event.sha256(report_path),"ledgerSha256":event.sha256(ledger)}))

def main():
    parser=argparse.ArgumentParser(description=__doc__); parser.add_argument("--input-root",required=True); parser.add_argument("--program-lock",required=True); parser.add_argument("--output",required=True); parser.add_argument("--seed",type=int,default=20260827); run(parser.parse_args())
if __name__=="__main__": main()
