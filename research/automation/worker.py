"""One bounded Codex turn inside a systemd experiment cgroup."""
from __future__ import annotations

import json
import os
from pathlib import Path
import signal
import time

from core import REPO, RPC, config, read, utc, write

FIELDS = ['status','stage','summary','known','suspected','untested','openedOutcomes','nextAction','files','artifacts','acceptanceEvidence']
OUTPUT_SCHEMA = {'type':'object','additionalProperties':False,'required':FIELDS,'properties':{
    'status':{'type':'string','enum':['COMPLETE','CONTINUE','BLOCKED']},
    'stage':{'type':'string','enum':['IDEA','DATA_FEASIBILITY','EXPLORATORY','REJECTED','INCONCLUSIVE','DATA_BLOCKED','OPERATIONALLY_BLOCKED']},
    **{k:{'type':'string'} for k in ('summary','known','suspected','untested','openedOutcomes','nextAction')},
    'files':{'type':'array','items':{'type':'string'}}, 'artifacts':{'type':'array','items':{'type':'string'}},
    'acceptanceEvidence':{'type':'array','items':{'type':'object','additionalProperties':False,'required':['criterion','path'],'properties':{'criterion':{'type':'string'},'path':{'type':'string'}}}}}}


def validate_output(value):
    if not isinstance(value,dict) or set(value) != set(FIELDS): raise ValueError('invalid structured handoff fields')
    for key in ('files','artifacts'):
        if not isinstance(value[key],list) or any(not isinstance(p,str) for p in value[key]): raise ValueError('invalid handoff paths')
    if not isinstance(value['acceptanceEvidence'],list) or any(not isinstance(x,dict) or set(x)!={'criterion','path'} or not all(isinstance(v,str) for v in x.values()) for x in value['acceptanceEvidence']):raise ValueError('invalid acceptance evidence')
    for key in set(FIELDS)-{'files','artifacts','acceptanceEvidence'}:
        if not isinstance(value[key],str): raise ValueError('invalid handoff text')
    if value['status'] not in OUTPUT_SCHEMA['properties']['status']['enum'] or value['stage'] not in OUTPUT_SCHEMA['properties']['stage']['enum']:
        raise ValueError('invalid handoff state')
    return value


def execute(rpc, job, cfg, status_path, stop_requested=lambda:False):
    prompt=(REPO/'research/automation/prompt.md').read_text()
    thread=rpc.call('thread/start',{'cwd':str(REPO),'model':cfg['model'],'approvalPolicy':'never',
                                    'sandbox':'danger-full-access','ephemeral':False,
                                    'developerInstructions':prompt,
                                    'config':{'model_reasoning_effort':cfg['effort']}})['thread']['id']
    text='Execute the next bounded step for this registered task.\n'+json.dumps(job,indent=2)
    before=rpc.quota(cfg['bucket'])
    if before['remaining'] <= cfg['reservePercent']: return {'reason':'ALLOWANCE_RESERVE','allowance':before}
    turn=rpc.call('turn/start',{'threadId':thread,'model':cfg['model'],'effort':cfg['effort'],
                               'input':[{'type':'text','text':text}], 'outputSchema':OUTPUT_SCHEMA})['turn']['id']
    now=time.monotonic(); end=min(now+cfg['turnMaxSeconds'],now+max(0,job['workDeadline']-time.time()))
    next_status=now+5; next_poll=now+cfg['pollSeconds']; last_good=now; last=before; final_text=None; usage=None; reason=None
    write(status_path,{'threadId':thread,'turnId':turn,'phase':'AGENT_WORK','allowance':last,'updatedAt':utc()})
    while True:
        now=time.monotonic()
        if stop_requested(): reason='PAUSED'
        elif time.time() >= job['workDeadline']: reason='BUDGET_EXHAUSTED'
        elif now >= end: reason='TURN_TIME_LIMIT'
        if now >= next_poll:
            try:
                last=rpc.quota(cfg['bucket'], timeout=max(.1,min(15,cfg['staleSeconds']-(time.monotonic()-last_good)))); last_good=time.monotonic()
                if last['remaining'] <= cfg['reservePercent']: reason='ALLOWANCE_RESERVE'
            except (RuntimeError,TimeoutError,ValueError):
                if time.monotonic()-last_good >= cfg['staleSeconds']: reason='ALLOWANCE_UNAVAILABLE'
            next_poll=time.monotonic()+min(cfg['pollSeconds'],10 if time.monotonic()-last_good>=cfg['pollSeconds'] else cfg['pollSeconds'])
        if reason:
            try: rpc.call('turn/interrupt',{'threadId':thread,'turnId':turn},timeout=5)
            except (RuntimeError,TimeoutError,BrokenPipeError): pass
            return {'reason':reason,'allowance':last,'threadId':thread,'turnId':turn,'usage':usage}
        # RPC calls may have queued notifications while awaiting a response.
        events=rpc.events[:]; rpc.events.clear()
        for event in events:
            method=event.get('method'); params=event.get('params',{})
            if method=='thread/tokenUsage/updated': usage=params.get('tokenUsage')
            if method=='item/completed':
                item=params.get('item',{})
                if item.get('type')=='agentMessage' and item.get('phase') in (None,'final_answer'):
                    final_text=item.get('text')
            if method=='turn/completed' and params.get('turn',{}).get('id')==turn:
                completed=params['turn']
                for item in completed.get('items',[]):
                    if item.get('type')=='agentMessage' and item.get('phase') in (None,'final_answer'):
                        final_text=item.get('text')
                if completed.get('status')!='completed': raise RuntimeError('agent turn did not complete')
                value=validate_output(json.loads(final_text or '{}'))
                return {'reason':'TURN_COMPLETED','output':value,'allowance':last,'threadId':thread,'turnId':turn,'usage':usage}
        if time.monotonic()>=next_status:
            write(status_path,{'threadId':thread,'turnId':turn,'phase':'AGENT_WORK','allowance':last,'usage':usage,'updatedAt':utc()})
            next_status=time.monotonic()+5
        rpc.pump(timeout=.2)


def main(experiment_id):
    cfg=config(); folder=Path(cfg['stateRoot'])/'jobs'/experiment_id
    job=read(folder/'job.json'); stopped=[False]
    signal.signal(signal.SIGTERM,lambda *_:stopped.__setitem__(0,True))
    signal.signal(signal.SIGINT,lambda *_:stopped.__setitem__(0,True))
    try:
        with RPC([cfg['codex'],'app-server','--listen','stdio://']) as rpc:
            result=execute(rpc,job,cfg,folder/'live.json',lambda: stopped[0] or (Path(cfg['stateRoot'])/'paused').exists())
    except Exception as error:
        result={'reason':'WORKER_FAILED','error':str(error)[:1000]}
    write(folder/'result.json',dict(result,finishedAt=utc()))
    return 0
