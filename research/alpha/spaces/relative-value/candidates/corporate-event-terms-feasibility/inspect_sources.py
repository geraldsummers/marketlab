"""Verify captured metadata/terms bytes; do not evaluate market outcomes."""
from pathlib import Path
import hashlib
import json
import sys
from html.parser import HTMLParser
sys.path.insert(0,str(Path(__file__).resolve().parents[4]/'tools'))
from experiment import require_budget

class Text(HTMLParser):
 def __init__(self):super().__init__();self.parts=[];self.hidden=0
 def handle_starttag(self,tag,attrs):
  if tag in ('script','style'):self.hidden+=1
 def handle_endtag(self,tag):
  if tag in ('script','style'):self.hidden=max(0,self.hidden-1)
 def handle_data(self,data):
  if not self.hidden:self.parts.append(data)

def main():
 require_budget(outcome_access='NONE')
 root=Path.home()/'artifacts/corporate-event-terms-feasibility-v1'
 rows=[json.loads(line) for line in (root/'acquisition.jsonl').read_text().splitlines()]
 texts={}
 for row in rows:
  if 'path' not in row:continue
  data=Path(row['path']).read_bytes()
  assert hashlib.sha256(data).hexdigest()==row['sha256']
  assert len(data)==row['bytes']
  if row['status']==200 and 'html' in (row['headers']['Content-Type'] or ''):
   parser=Text();parser.feed(data.decode('utf-8',errors='replace'))
   texts[row['id']]=' '.join(' '.join(parser.parts).split()).lower()
 requirements={'monster-issuer-html':['odd lots','financing condition','letter of transmittal','guaranteed delivery','withdrawal','53.00','60.00'], 'ibkr-instructions':['12:00','day prior','settled'], 'fidelity-instructions':['all your accounts','cutoff date','expiration date'], 'frontera-amendment':['no longer valid','odd lot'], 'ibkr-current-fees':['all other','free'], 'nyse-taq':['nbbo','master']}
 findings=[]
 for label,terms in requirements.items():
  absent=[t for t in terms if t not in texts[label]]
  findings.append({'id':label,'requiredTermsPresent':not absent,'missing':absent})
 assert all(x['requiredTermsPresent'] for x in findings),findings
 value={'hashesVerified':sum('sha256' in x for x in rows),'httpStatusCounts':{str(s):sum(r.get('status')==s for r in rows) for s in sorted({r.get('status') for r in rows if 'status' in r})},'documentChecks':findings,'scope':'Source integrity and presence checks only; not a legal interpretation, extraction accuracy benchmark, historical coverage test or return evaluation.'}
 (root/'source-verification.json').write_text(json.dumps(value,indent=2)+'\n')
 # Only provenance goes in Git. Source bytes and extracted documents remain external.
 Path(__file__).with_name('source-manifest.json').write_text(json.dumps({'sources':rows,'verification':value},indent=2)+'\n')
 print(json.dumps(value,indent=2))

if __name__=='__main__':main()
