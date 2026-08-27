#!/usr/bin/env python3
"""Bias-labeled event-conditioned signed-social discovery."""
from __future__ import annotations
import argparse, hashlib, json, math
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
import numpy as np
from sklearn.ensemble import HistGradientBoostingRegressor
from sklearn.linear_model import Ridge
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

HOUR=3_600_000; DAY=24*HOUR
ASSETS=("BTC","ETH","HYPE","LIT","NEAR","PUMP","SOL","WLD","XRP","ZEC")
MARKET=("latest_return","btc_latest_return","log_rv_1h","log_rv_24h","hour_sin","hour_cos","day_sin","day_cos")
SIGNED=("polarity_surprise","disagreement_change"); HORIZONS=(1,4); ESTIMATORS=("ridge","histogram_boosted_tree")
BIAS_LEDGER=("AVAILABILITY_TIMESTAMP_PROXY","OPENED_OUTCOMES","SURVIVOR_UNIVERSE")

def sha256(path):
    digest=hashlib.sha256()
    with Path(path).open("rb") as handle:
        for chunk in iter(lambda:handle.read(1<<20),b""): digest.update(chunk)
    return digest.hexdigest()

def read_json(path):
    with Path(path).open(encoding="utf-8") as handle: return json.load(handle)

def verified_rows(root,uri,expected):
    root=Path(root).resolve(); path=(root/uri).resolve()
    if root not in path.parents or not path.is_file() or sha256(path)!=expected: raise ValueError(f"invalid object {uri}")
    with path.open(encoding="utf-8") as handle: return [json.loads(line) for line in handle if line.strip()]

def dates(start,end):
    current=start.date()
    while current<end.date(): yield current.isoformat(); current+=timedelta(days=1)

def filter_social(rows):
    last_by_text={}; author_counts=defaultdict(int); output=[]
    for row in sorted(rows,key=lambda value:(value["indexedAtAvailabilityProxyEpochMillis"],value["sourceEventId"])):
        at=row["indexedAtAvailabilityProxyEpochMillis"]; prior=last_by_text.get(row["textSha256"])
        if prior is not None and at-prior<DAY: continue
        key=(row["authorIdSha256"],at//HOUR); author_counts[key]+=1
        if author_counts[key]>3: continue
        last_by_text[row["textSha256"]]=at; output.append(row)
    return output

def sample_std(values): return None if len(values)<2 else float(np.std(values,ddof=1))

def period_return(bars,start,hours):
    selected=[bars.get(start+i*900_000) for i in range(hours*4)]
    return None if any(row is None for row in selected) else math.log(selected[-1]["close"]/selected[0]["open"])

def period_variance(bars,start,hours):
    selected=[bars.get(start+i*900_000) for i in range(hours*4)]
    return None if any(row is None for row in selected) else sum(row["realizedVariance1m"] for row in selected)

def load_inputs(root,lock):
    market={}; social={}; identities=[]
    start=datetime.fromisoformat(lock["period"]["startInclusive"].replace("Z","+00:00")); end=datetime.fromisoformat(lock["period"]["endExclusive"].replace("Z","+00:00"))
    if tuple(lock["universe"]["symbols"])!=ASSETS: raise ValueError("registered universe changed")
    for symbol in ASSETS:
        path=Path(root)/"market"/"manifests"/f"{symbol}.json"; manifest=read_json(path); identities.append(f"market/{symbol}:{sha256(path)}")
        rows=verified_rows(root,manifest["outputUri"],manifest["outputSha256"])
        if len(rows)!=manifest["barCount"]: raise ValueError(f"market count mismatch {symbol}")
        market[symbol]={row["openTimeEpochMillis"]:row for row in rows}; collected=[]
        for date in dates(start,end):
            path=Path(root)/"social"/"manifests"/symbol/f"{date}.json"; day=read_json(path); identities.append(f"social/{symbol}/{date}:{sha256(path)}")
            rows=verified_rows(root,day["outputUri"],day["outputSha256"])
            if len(rows)!=day["observationCount"]: raise ValueError(f"social count mismatch {symbol} {date}")
            collected.extend(rows)
        social[symbol]=filter_social(collected)
    return market,social,identities

def materialize(root,lock):
    market,social,identities=load_inputs(root,lock); by_hour={symbol:defaultdict(list) for symbol in ASSETS}
    for symbol in ASSETS:
        for row in social[symbol]: by_hour[symbol][((row["indexedAtAvailabilityProxyEpochMillis"]-1)//HOUR)*HOUR].append(float(row["polarity"]))
    start=int(datetime.fromisoformat(lock["period"]["startInclusive"].replace("Z","+00:00")).timestamp()*1000)+30*DAY
    end=int(datetime.fromisoformat(lock["period"]["endExclusive"].replace("Z","+00:00")).timestamp()*1000)-4*HOUR; output=[]
    for symbol in ASSETS:
        bars=market[symbol]; btc=market["BTC"]; decision=start
        while decision<end:
            current=by_hour[symbol].get(decision-HOUR,[]); counts=[len(by_hour[symbol].get(decision-HOUR-offset*HOUR,[])) for offset in range(1,721)]
            count_std=float(np.std(counts,ddof=1)); attention=None if count_std==0 else (len(current)-float(np.mean(counts)))/count_std
            history=[value for offset in range(1,721) for value in by_hour[symbol].get(decision-HOUR-offset*HOUR,[])]
            polarity=None if not current or not history else float(np.mean(current)-np.mean(history)); now_std=sample_std(current); prior_std=sample_std(by_hour[symbol].get(decision-2*HOUR,[]))
            disagreement=None if now_std is None or prior_std is None else now_std-prior_std
            latest=period_return(bars,decision-HOUR,1); btc_latest=period_return(btc,decision-HOUR,1); rv1=period_variance(bars,decision-HOUR,1); rv24=period_variance(bars,decision-24*HOUR,24); target1=period_return(bars,decision,1); target4=period_return(bars,decision,4)
            if attention is not None and None not in (latest,btc_latest,rv1,rv24,target1,target4):
                instant=datetime.fromtimestamp(decision/1000,tz=timezone.utc)
                output.append({"decisionTimeEpochMillis":decision,"symbol":symbol,"latest_return":latest,"btc_latest_return":btc_latest,"log_rv_1h":math.log(max(rv1,1e-12)),"log_rv_24h":math.log(max(rv24,1e-12)),"hour_sin":math.sin(2*math.pi*instant.hour/24),"hour_cos":math.cos(2*math.pi*instant.hour/24),"day_sin":math.sin(2*math.pi*instant.weekday()/7),"day_cos":math.cos(2*math.pi*instant.weekday()/7),"attention_shock":attention,"polarity_surprise":polarity,"disagreement_change":disagreement,"target_1h":target1,"target_4h":target4})
            decision+=HOUR
    return output,hashlib.sha256("\n".join(sorted(identities)).encode()).hexdigest()

def design(rows,fields):
    return np.asarray([[float(row[field]) for field in fields]+[1.0 if row["symbol"]==symbol else 0.0 for symbol in ASSETS[1:]] for row in rows],dtype=np.float32)

def model(kind,seed):
    return make_pipeline(StandardScaler(),Ridge(alpha=1.0)) if kind=="ridge" else HistGradientBoostingRegressor(max_iter=100,learning_rate=.05,max_depth=2,min_samples_leaf=100,l2_regularization=1.0,random_state=seed)

def hac(values,lag=4):
    x=np.asarray(values,dtype=float); mean=float(np.mean(x)); centered=x-mean; n=len(x); variance=float(centered@centered/n)
    for offset in range(1,min(lag,n-1)+1): variance+=2*(1-offset/(lag+1))*float(centered[offset:]@centered[:-offset]/n)
    se=math.sqrt(max(variance,0)/n); return se,1.0 if se==0 else math.erfc(abs(mean/se)/math.sqrt(2))

def holm(values):
    ranked=sorted(enumerate(values),key=lambda item:item[1]); output=[1.0]*len(values); running=0.0
    for rank,(index,value) in enumerate(ranked): running=max(running,value*(len(values)-rank)); output[index]=min(1.0,running)
    return output

def evaluate_variant(rows,signed,horizon,estimator,seed):
    eligible=[row for row in rows if row[signed] is not None]; times=sorted({row["decisionTimeEpochMillis"] for row in eligible}); initial=int(len(times)*.4); remaining=len(times)-initial; boundaries=[initial+round(remaining*i/5) for i in range(6)]; target=f"target_{horizon}h"; all_rows=[]; folds=[]
    for fold in range(5):
        test_start=times[boundaries[fold]]; test_end=times[boundaries[fold+1]-1]+1; train_all=[row for row in eligible if row["decisionTimeEpochMillis"]<test_start-4*HOUR]; test_all=[row for row in eligible if test_start<=row["decisionTimeEpochMillis"]<test_end]; threshold=float(np.quantile([row["attention_shock"] for row in train_all],.9)); train=[row for row in train_all if row["attention_shock"]>=threshold]; test=[row for row in test_all if row["attention_shock"]>=threshold]
        if len(train)<200 or len(test)<100: raise ValueError(f"insufficient acted rows {signed} {horizon}h fold {fold+1}")
        y_train=np.asarray([row[target] for row in train]); y_test=np.asarray([row[target] for row in test]); fields=MARKET+("attention_shock",signed); prediction=model(estimator,seed+fold).fit(design(train,fields),y_train).predict(design(test,fields)); baselines={"zero":np.zeros(len(test)),"historical_mean":np.full(len(test),float(np.mean(y_train)))}
        for name,base_fields in (("market_only",MARKET),("signed_social_only",(signed,)),("attention_state_only",("attention_shock",))): baselines[name]=model("ridge",seed+fold).fit(design(train,base_fields),y_train).predict(design(test,base_fields))
        losses={name:float(np.mean((y_test,value)**2)) for name,value in []} if False else {name:float(np.mean((y_test-value)**2)) for name,value in baselines.items()}; strongest=min(losses,key=losses.get); differences=(y_test-baselines[strongest])**2-(y_test-prediction)**2; gross=np.sign(prediction)*y_test
        folds.append({"fold":fold+1,"trainRows":len(train),"actedRows":len(test),"eligibleRows":len(test_all),"coverage":len(test)/len(test_all),"attentionThreshold":threshold,"strongestBaseline":strongest,"meanLossImprovement":float(np.mean(differences)),"grossMeanReturn":float(np.mean(gross)),"netMeanReturn5Bps":float(np.mean(gross-.0005)),"netMeanReturn10Bps":float(np.mean(gross-.001))})
        all_rows.extend({"time":row["decisionTimeEpochMillis"],"symbol":row["symbol"],"difference":float(diff),"gross":float(value)} for row,diff,value in zip(test,differences,gross))
    by_time=defaultdict(list); by_asset=defaultdict(list)
    for row in all_rows: by_time[row["time"]].append(row["difference"]); by_asset[row["symbol"]].append(row["difference"])
    differences=[float(np.mean(by_time[key])) for key in sorted(by_time)]; se,p=hac(differences); per_asset={symbol:float(np.mean(by_asset[symbol])) if by_asset[symbol] else 0.0 for symbol in ASSETS}
    return {"variantId":f"{signed}-{horizon}h-{estimator}","signedFeature":signed,"horizonHours":horizon,"estimator":estimator,"folds":folds,"meanLossImprovement":float(np.mean(differences)),"hacStandardError":se,"twoSidedPValue":p,"positiveFolds":sum(row["meanLossImprovement"]>0 for row in folds),"positiveAssets":sum(value>0 for value in per_asset.values()),"perAssetLossImprovement":per_asset,"netMeanReturn5Bps":float(np.mean([row["gross"]-.0005 for row in all_rows])),"netMeanReturn10Bps":float(np.mean([row["gross"]-.001 for row in all_rows])),"minimumFoldCoverage":min(row["coverage"] for row in folds),"minimumFoldActedRows":min(row["actedRows"] for row in folds)}

def run(args):
    output=Path(args.output); output.mkdir(parents=True,exist_ok=False); root=Path(args.input_root); lock_path=Path(args.program_lock); lock=read_json(lock_path)
    if lock["schemaVersion"]!="marketlab.social-backfill-program-lock.v2": raise ValueError("unsupported lock")
    rows,input_identity=materialize(root,lock); panel=output/"panel.jsonl"
    with panel.open("x",encoding="utf-8") as handle:
        for row in rows: handle.write(json.dumps(row,sort_keys=True,separators=(",",":"))+"\n")
    variants=[evaluate_variant(rows,signed,horizon,estimator,args.seed) for signed in SIGNED for horizon in HORIZONS for estimator in ESTIMATORS]
    for row,value in zip(variants,holm([row["twoSidedPValue"] for row in variants])):
        row["holmAdjustedPValue"]=value; row["passesAllDevelopmentGates"]=row["meanLossImprovement"]>0 and row["positiveFolds"]>=4 and row["positiveAssets"]>=7 and value<.05 and row["netMeanReturn5Bps"]>0 and row["netMeanReturn10Bps"]>0 and row["minimumFoldCoverage"]>=.03 and row["minimumFoldActedRows"]>=100
    passing=[row for row in variants if row["passesAllDevelopmentGates"]]; winner=max(passing,key=lambda row:row["meanLossImprovement"]) if passing else None
    activation={"developmentPassed":winner is not None,"candidateFrozen":winner is not None,"outcomeSealingReady":False,"eligible":False,"minimumQualityValidDays":30,"minimumActedOnEvents":1000,"maximumCalendarDays":90,"interimOutcomeAccessAllowed":False}
    report={"schemaVersion":"marketlab.event-conditioned-social-discovery.v1","candidateId":"event-conditioned-social-direction-v1","classification":"AVAILABILITY_BIASED_RETROSPECTIVE_DISCOVERY","stage":"FROZEN_CANDIDATE" if winner else "REJECTED","promotionCeilingOfHistoricalEvidence":"EXPLORATORY","dataBiases":list(BIAS_LEDGER),"targetLeakageAllowed":False,"period":{"startInclusive":"2025-11-03T00:00:00Z","endExclusive":"2026-07-01T00:00:00Z"},"inputManifestSetSha256":input_identity,"programLockSha256":sha256(lock_path),"panelSha256":sha256(panel),"panelRows":len(rows),"familySize":len(variants),"resources":{"maxProcesses":1,"blasThreads":2,"gpu":False},"variants":variants,"winner":winner["variantId"] if winner else None,"prospectiveActivation":activation,"paperTradingAuthorized":False,"liveTradingAuthorized":False}
    report_path=output/"report.json"; report_path.write_text(json.dumps(report,indent=2,sort_keys=True)+"\n"); manifest={"schemaVersion":"marketlab.event-conditioned-social-artifacts.v1","panel":{"path":"panel.jsonl","sha256":sha256(panel)},"report":{"path":"report.json","sha256":sha256(report_path)}}; (output/"manifest.json").write_text(json.dumps(manifest,indent=2,sort_keys=True)+"\n")
    if winner: (output/"frozen.json").write_text(json.dumps({"schemaVersion":"marketlab.event-conditioned-social-freeze.v1","candidateId":report["candidateId"],"selectedVariant":winner,"reportSha256":sha256(report_path),"panelSha256":sha256(panel),"programLockSha256":report["programLockSha256"],"prospectiveGate":activation},indent=2,sort_keys=True)+"\n")
    print(json.dumps({"stage":report["stage"],"winner":report["winner"],"reportSha256":sha256(report_path),"panelRows":len(rows)}))

def main():
    parser=argparse.ArgumentParser(description=__doc__); sub=parser.add_subparsers(dest="command",required=True); command=sub.add_parser("run"); command.add_argument("--input-root",required=True); command.add_argument("--program-lock",required=True); command.add_argument("--output",required=True); command.add_argument("--seed",type=int,default=20260827); run(parser.parse_args())
if __name__=="__main__": main()
