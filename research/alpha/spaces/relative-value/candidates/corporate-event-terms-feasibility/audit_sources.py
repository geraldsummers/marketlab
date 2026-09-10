"""Bounded metadata/offer-document audit; never acquires price or payoff datasets."""
import hashlib
import json
from pathlib import Path
import time
from datetime import datetime, timezone
from urllib.request import Request, urlopen
from urllib.error import HTTPError

SOURCES = [
 ('sec-api','https://www.sec.gov/search-filings/edgar-application-programming-interfaces'),
 ('sec-access','https://www.sec.gov/about/developer-resources'),
 ('sec-form-index','https://www.sec.gov/Archives/edgar/full-index/2024/QTR2/form.idx'),
 ('sec-submissions','https://data.sec.gov/submissions/CIK0000865752.json'),
 ('offer-2024','https://www.sec.gov/Archives/edgar/data/865752/000110465924058430/tm2413707d1_exa1a.htm'),
 ('offer-rumble','https://www.sec.gov/Archives/edgar/data/1830081/000121390025000757/sctoi_ex99a1arumble.htm'),
 ('frontera-amendment','https://fronteraenergy.mediaroom.com/2024-09-25-Frontera-to-Amend-Substantial-Issuer-Bid-to-Remove-the-Preferential-Acceptance-of-Odd-Lots'),
 ('ibkr-instructions','https://investors.interactivebrokers.com/en/trading/corp-action-instructions.php'),
 ('ibkr-fees','https://www.interactivebrokers.com/en/accounts/fees/otherFees.php?p=e'),
 ('fidelity-instructions','https://www.fidelity.com/customer-service/corporate-actions-learn-more-faqs'),
 ('nyse-taq','https://www.nyse.com/data-products/catalog/daily-taq'),
 ('nyse-pricing','https://www.nyse.com/publicdocs/nyse/data/NYSE_Historical_Market_Data_Pricing.pdf'),
]

SUPPLEMENT = [
 ('monster-issuer-offer','https://investors.monsterbevcorp.com/static-files/3cce6f0b-bb34-4f4b-84eb-efe0df174c85'),
 ('monster-issuer-filing-index','https://investors.monsterbevcorp.com/sec-filings/sec-filing/sc-i/0001104659-24-058430'),
 ('ibkr-current-fees','https://www.interactivebrokers.com/en/pricing/other-fees.php'),
]

def main():
 import sys
 sys.path.insert(0, str(Path(__file__).resolve().parents[4]/'tools'))
 from experiment import require_budget
 require_budget(outcome_access='NONE')
 root=Path.home()/'artifacts/corporate-event-terms-feasibility-v1';root.mkdir(parents=True,exist_ok=True)
 manifest=root/'acquisition.jsonl'
 import argparse
 parser=argparse.ArgumentParser();parser.add_argument('--supplement',action='store_true');parser.add_argument('--issuer-html',action='store_true');args=parser.parse_args()
 for label,url in ([('monster-issuer-html','https://investors.monsterbevcorp.com/node/16891/html')] if args.issuer_html else SUPPLEMENT if args.supplement else SOURCES):
  started=datetime.now(timezone.utc).isoformat()
  req=Request(url,headers={'User-Agent':'Marketlab research source-feasibility audit (github.com/geraldsummers/marketlab)','Accept-Encoding':'identity'})
  error=None
  try:
   response=urlopen(req,timeout=25)
  except HTTPError as e:
   response=e;error=str(e)
  except Exception as e:
   with manifest.open('a') as f:f.write(json.dumps({'id':label,'url':url,'retrievedAt':started,'error':str(e)})+'\n')
   print(label,type(e).__name__);continue
  with response:
   data=response.read(12*1024*1024+1)
   if len(data)>12*1024*1024:raise ValueError('source exceeded 12 MiB bound')
   digest=hashlib.sha256(data).hexdigest();(root/digest).write_bytes(data)
   row={'id':label,'url':url,'finalUrl':response.geturl(),'retrievedAt':started,'completedAt':datetime.now(timezone.utc).isoformat(),'status':response.status,'bytes':len(data),'sha256':digest,'path':str(root/digest),'headers':{k:response.headers.get(k) for k in ['Date','Last-Modified','ETag','Content-Type']},'error':error}
  with manifest.open('a') as f:f.write(json.dumps(row)+'\n')
  print(label,row['status'],len(data),digest[:12]);time.sleep(.3)

if __name__=='__main__':main()
