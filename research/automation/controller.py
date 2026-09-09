"""Serialized research cycles with Git checkpoints and systemd worker supervision."""
from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

from core import REPO, Publisher, RPC, changed as _changed, config, local_day, locked, owned, read, run as _run, utc, write
sys.path.insert(0,str(REPO/'research/alpha/tools'))
import experiment
import workspace

def changed(): return _changed(REPO)

def run(argv, **kwargs):
    kwargs.setdefault('cwd', REPO)
    return _run(argv, **kwargs)

JOURNAL='research/automation/runs'
CODE_SURFACES=['alpha-model','social-model','research-cli/src','data/src','engine/src','historical-data/src']


def checks(paths, cfg, deadline=None):
    """Validate the actual checkpoint. Logs remain outside Git."""
    env=os.environ.copy()
    env.update(TMPDIR=str(Path.home()/'.tmp'),JAVA_HOME=cfg['javaHome'],JAVA_TOOL_OPTIONS='-Djava.io.tmpdir='+str(Path.home()/'.tmp'))
    env['PATH']=str(Path(cfg['javaHome'])/'bin')+':'+str(Path.home()/'.local/bin')+':'+env.get('PATH','/usr/bin:/bin')
    commands=[['git','diff','--check'],['python3','research/alpha/tools/workspace.py','validate'],
              ['python3','-m','unittest','research/alpha/tools/test_workspace.py'],['./gradlew','check','--no-daemon']]
    if any(p.startswith(('research/automation/','research/alpha/tools/')) and p.endswith('.py') for p in paths):
        commands += [['python3','-m','unittest','discover','-s','research/automation','-p','test_*.py'],
                     ['python3','-m','unittest','research/alpha/tools/test_experiment.py']]
    for directory in ('alpha-model','social-model'):
        if any(p.startswith(directory+'/') and p.endswith('.py') for p in paths):
            commands.append([cfg['python'],'-m','unittest','discover','-s',directory,'-p','test_*.py'])
    logroot=Path(cfg['artifactRoot'])/'checks'; logroot.mkdir(parents=True,exist_ok=True)
    logfile=logroot/(str(time.time_ns())+'.log')
    with logfile.open('w') as log:
        for command in commands:
            remaining=(deadline-time.time()) if deadline else 1800
            if remaining <= 1: raise RuntimeError('validation deadline exhausted')
            log.write(json.dumps(command)+'\n');log.flush()
            # Killing the whole validation process group also kills Gradle children.
            process=subprocess.Popen(command,cwd=REPO,env=env,stdout=log,stderr=subprocess.STDOUT,start_new_session=True)
            try:
                code=process.wait(timeout=min(remaining,1800))
            except subprocess.TimeoutExpired:
                os.killpg(process.pid,9);process.wait();raise RuntimeError(f'validation timed out; {logfile}')
            if code: raise RuntimeError(f'validation failed; {logfile}')
    return {'log':str(logfile),'sha256':hashlib.sha256(logfile.read_bytes()).hexdigest(),'commands':commands,'result':'PASS'}


def event(cycle, kind, **details):
    folder=REPO/JOURNAL/cycle; folder.mkdir(parents=True,exist_ok=True)
    path=folder/'events.jsonl'
    record={'time':utc(),'event':kind,**details}
    with path.open('a') as handle:
        handle.write(json.dumps(record,sort_keys=True,allow_nan=False)+'\n');handle.flush();os.fsync(handle.fileno())
    records=[json.loads(line) for line in path.read_text().splitlines()]
    lines=[f'# Research cycle {cycle}','', 'All times are UTC. Market evidence retains its explicit epistemic stage.','']
    for value in records:
        lines.append(f"- {value['time']} — {value['event']}: {value.get('summary',value.get('reason',value.get('experimentId','')))}")
    (folder/'README.md').write_text('\n'.join(lines)+'\n')
    return [str(path.relative_to(REPO)),str((folder/'README.md').relative_to(REPO))]


def candidate_path(task):
    return REPO/'research/alpha/spaces'/task['spaceId']/'candidates'/task['candidateIds'][0]/'candidate.json'


def surfaces(task):
    return sorted(set(task['claimSurfaces']+[f"research/alpha/spaces/{task['spaceId']}/candidates"]+CODE_SURFACES+['research/inventory/evidence','research/decision-log.md',JOURNAL]))


def check_owned(paths, task):
    allowed=surfaces(task)
    if len(paths)>200:raise RuntimeError('checkpoint exceeds 200-file review bound')
    for name in paths:
        if not owned(name,allowed): raise RuntimeError(f'unclaimed path changed: {name}')
        if name=='AGENTS.md' or (name.startswith('research/automation/') and not name.startswith(JOURNAL+'/')):
            raise RuntimeError('agent changed protected automation policy')
        if name.endswith('.lock.json') and subprocess.run(['git','cat-file','-e','HEAD:'+name],cwd=REPO,stderr=subprocess.DEVNULL).returncode==0:
            raise RuntimeError('agent changed an existing frozen lock')
        exists_in_head=subprocess.run(['git','cat-file','-e','HEAD:'+name],cwd=REPO,stderr=subprocess.DEVNULL).returncode==0
        selected=str(candidate_path(task).parent.relative_to(REPO))
        if '/candidates/' in name and exists_in_head and not owned(name,[selected]):
            raise RuntimeError('agent changed another existing candidate')
        if name.startswith('research/inventory/evidence/') and exists_in_head:
            raise RuntimeError('agent changed existing evidence; finalization is dispatcher-owned')
        path=REPO/name
        if path.is_symlink(): raise RuntimeError('agent created a symlink')
        if path.exists() and (path.stat().st_size>2*1024*1024 or path.suffix.lower() in {'.parquet','.pickle','.pkl','.pt','.bin','.csv','.duckdb','.env'}):
            raise RuntimeError(f'raw, sensitive, or large artifact must stay outside Git: {name}')
    return paths


def claim(task, cfg, experiment_id):
    root=Path.home()/'.local/share/worklane/agent-work';root.mkdir(parents=True,exist_ok=True)
    target=root/f'agent--automation--{experiment_id}.md'
    # Live Herdr state and claim ownership are complementary. Never reconcile by deleting other claims.
    live=run(['herdr','--session',cfg['herdrSession'],'agent','list'])
    other_claims=[p for p in root.glob('*.md') if p!=target]
    live_agents=json.loads(live).get('result',{}).get('agents',[])
    for agent in live_agents:
        if agent.get('agent_status')=='working' and not any(agent.get('pane_id','unmatched') in p.read_text() for p in other_claims):
            raise RuntimeError('An interactive agent is working without a claim; defer unattended overlap')
    for path in other_claims:
        if path==target: continue
        text=path.read_text()
        if any(s.rstrip('/') in text for s in surfaces(task)):
            raise RuntimeError(f'overlapping claim: {path.name}; live agents inspected')
    target.write_text(f'# /automation/{experiment_id}\nStatus: working\nOutside a Herdr pane; managed by systemd user service.\nConfigured Herdr session: {cfg["herdrSession"]}\nUpdated UTC: {utc()}\nSurfaces:\n'+''.join('- '+s+'\n' for s in surfaces(task))+'\nLive agents snapshot:\n'+live+'\n')
    return target


def choose_task(cfg, state):
    tasks=workspace.ready_work(all_priorities=True)
    eligible=[]
    for task in tasks:
        if task['outcomeAccess'] not in {'NONE','DEVELOPMENT_ONLY'} or 'HISTORICAL' not in task['researchModes']:
            continue
        if not task.get('experimentContract') or len(task['candidateIds'])!=1: continue
        c=experiment.validate_contract(read(REPO/task['experimentContract']))
        if read(candidate_path(task))['stage'] in {'REJECTED','INCONCLUSIVE','FROZEN_CANDIDATE','BLIND_VALIDATED','PROSPECTIVE_SHADOW','PAPER_ELIGIBLE','LIVE_ELIGIBLE'}:continue
        if (Path(cfg['experimentRoot'])/c['experimentId']/'state.json').exists(): continue
        eligible.append(task)
    if not eligible: return None
    # Do not bypass a runnable higher priority that requires explicit registration.
    highest=min(workspace.TASK_PRIORITIES[t['priority']] for t in tasks) if tasks else 2
    eligible=[t for t in eligible if workspace.TASK_PRIORITIES[t['priority']]==highest]
    counts=state.get('domainCounts',{})
    return min(eligible,key=lambda t:(min(counts.get(d,0) for d in t.get('domains',['crypto'])),t['id'])) if eligible else None


def discovery_task(cfg, cycle, state):
    """A bounded metadata-only task may propose a new test without reading outcomes."""
    domain=min(cfg['domains'],key=lambda d:state.get('domainCounts',{}).get(d,0))
    space={'conventional':'relative-value','onchain':'settlement-liquidity','prediction-markets':'event-resolution','usd-stablecoins':'monetary-policy-transmission'}[domain]
    cid='discovery-'+cycle+'-'+domain
    base=Path('research/alpha/spaces')/space/'candidates'/cid
    contract=read(REPO/'research/alpha/templates/experiment.json')
    contract.update(experimentId=cid,candidateId=cid,searchFamilyId=cid,domains=[domain],hypothesis='Identify a distinct, data-ready economic mechanism using existing evidence and public source documentation.',
                    targetMarket=domain+' source and hypothesis feasibility',targetVenue='Public source documentation; exact venue is a deliverable',
                    decisionClock='Only information already available at this task start',budgetSeconds=3600,reportReserveSeconds=900,resourceClass='METADATA_ONLY',maxTrials=0)
    candidate=read(REPO/'research/alpha/templates/candidate.json')
    candidate.update(id=cid,spaceId=space,title='Bounded '+domain+' hypothesis discovery',stage='IDEA',claim=contract['hypothesis'],
                     mechanism='Metadata-only discovery; no predictive claim yet.',target=contract['targetMarket'],horizon='No outcome evaluation',
                     informationSet=['Existing evidence and publicly available source documentation'],primaryBaseline='No predictive evaluation',nextAction='Propose a data-ready bounded successor or a specific blocker',
                     domains=[domain],targetMarket=contract['targetMarket'],targetVenue=contract['targetVenue'],searchFamilyId=cid)
    task={'id':cid,'status':'READY','kind':'research','summary':contract['hypothesis'],'priority':'P1','researchModes':['HISTORICAL'],
          'blockedBy':[],'externalBlockers':[],'candidateIds':[cid],'domains':[domain],'resourceClass':'METADATA_ONLY','outcomeAccess':'NONE',
          'experimentContract':str(base/'experiment.json'),'computeLimits':{'maxProcesses':1,**{k:contract[k] for k in ('blasThreads','maxTrials','gpu','budgetSeconds','reportReserveSeconds','memoryMiB')}},
          'deliverables':['At most three distinct mechanisms; one data-ready bounded successor or a documented blocker'],
          'acceptanceCriteria':['Consult prior failed evidence','No experimental data or sealed outcomes opened','Successor specifies causal sources, sign, target, baseline, trial family and economic failure threshold'],
          'claimSurfaces':[str(base),str(Path('research/alpha/spaces')/space/'space.json'),'research/inventory/data-sources/'], 'completionEvidenceIds':[]}
    return task, candidate, contract


def install_worker_limits(experiment_id, contract, cfg):
    state=experiment.status(Path(cfg['experimentRoot']),experiment_id)
    remaining=state['deadlineUtc']-contract['reportReserveSeconds']-time.time()
    if state['result'] or remaining<=1: raise RuntimeError('experiment work deadline expired')
    memory=contract['memoryMiB']+2048
    available=int(next(line.split()[1] for line in Path('/proc/meminfo').read_text().splitlines() if line.startswith('MemAvailable:')))//1024
    if available<memory+1024: raise RuntimeError('insufficient shared memory headroom')
    folder=Path.home()/'.config/systemd/user'/f'marketlab-explore-worker@{experiment_id}.service.d'
    folder.mkdir(parents=True,exist_ok=True)
    (folder/'limits.conf').write_text('[Service]\n'+f'RuntimeMaxSec={int(remaining)}s\nMemoryHigh={memory-512}M\nMemoryMax={memory}M\nTasksMax=512\n')
    run(['systemctl','--user','daemon-reload'])
    return f'marketlab-explore-worker@{experiment_id}.service'


def stop_unit(unit):
    run(['systemctl','--user','stop',unit],timeout=30)


def worker_step(job, contract, cfg, quota_fn):
    experiment_id=contract['experimentId']; folder=Path(cfg['stateRoot'])/'jobs'/experiment_id
    write(folder/'job.json',job)
    result_path=folder/'result.json'
    if result_path.exists(): result_path.unlink()  # Prior turns are already archived in the cycle journal.
    unit=install_worker_limits(experiment_id,contract,cfg)
    subprocess.run(['systemctl','--user','reset-failed',unit],stdout=subprocess.DEVNULL,stderr=subprocess.DEVNULL)
    run(['systemctl','--user','start',unit])
    last_good=time.monotonic(); last_poll=0; reason=None
    while True:
        if (Path(cfg['stateRoot'])/'paused').exists(): reason='PAUSED'
        if time.time()>=job['workDeadline']: reason='BUDGET_EXHAUSTED'
        now=time.monotonic()
        if now-last_poll>=cfg['pollSeconds']:
            last_poll=now
            try:
                q=quota_fn();last_good=time.monotonic()
                if q['remaining']<=cfg['reservePercent']: reason='ALLOWANCE_RESERVE'
            except (RuntimeError,TimeoutError,ValueError):
                if time.monotonic()-last_good>=cfg['staleSeconds']: reason='ALLOWANCE_UNAVAILABLE'
        if reason:
            stop_unit(unit)
            return {'reason':reason}
        active=run(['systemctl','--user','show',unit,'--property=ActiveState','--value'])
        if active in ('inactive','failed'):
            if result_path.exists(): return read(result_path)
            return {'reason':'WORKER_FAILED','error':run(['systemctl','--user','show',unit,'--property=Result','--value'])}
        time.sleep(1)


def archive_and_restore(paths, cfg, experiment_id):
    """Preserve failed local edits before restoring only this worker's changes."""
    folder=Path(cfg['artifactRoot'])/experiment_id/('failed-checkpoint-'+str(time.time_ns()))
    folder.mkdir(parents=True)
    patch=subprocess.check_output(['git','diff','--binary','HEAD','--',*paths],cwd=REPO)
    (folder/'changes.patch').write_bytes(patch)
    for name in paths:
        source=REPO/name; dest=folder/'files'/name
        if source.is_symlink():
            dest.parent.mkdir(parents=True,exist_ok=True);dest.with_suffix(dest.suffix+'.symlink.txt').write_text(os.readlink(source))
        elif source.is_file():
            dest.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,dest)
        tracked=subprocess.run(['git','cat-file','-e','HEAD:'+name],cwd=REPO,stderr=subprocess.DEVNULL).returncode==0
        if tracked: run(['git','restore','--source=HEAD','--staged','--worktree','--',name])
        elif source.is_file() or source.is_symlink(): source.unlink()
    return {'location':str(folder),'patchSha256':hashlib.sha256(patch).hexdigest()}


def conclude(task, contract, result, cfg, complete):
    eid=contract['experimentId']; folder=Path(cfg['artifactRoot'])/eid;folder.mkdir(parents=True,exist_ok=True)
    output=result.get('output',{}); reason=result.get('reason','UNKNOWN')
    references=[]
    deadline=experiment.load_state(experiment.path_for(Path(cfg['experimentRoot']),eid))['deadlineUtc']
    for name in output.get('artifacts',[]) if complete else []:
        path=Path(name).resolve()
        if not path.is_relative_to(Path(cfg['artifactRoot']).resolve()) or not path.is_file():
            raise RuntimeError('result references missing or unowned artifact')
        checksum=hashlib.sha256()
        with path.open('rb') as handle:
            while chunk:=handle.read(1048576):
                if time.time()>=deadline:raise RuntimeError('artifact verification exceeded experiment deadline')
                checksum.update(chunk)
        references.append({'role':'research-artifact','location':str(path),'sha256':checksum.hexdigest(),'verification':'LOCAL_FILE'})
    stage=output.get('stage','INCONCLUSIVE') if complete else ('OPERATIONALLY_BLOCKED' if reason in {'WORKER_FAILED','VALIDATION_FAILED'} else 'INCONCLUSIVE')
    if stage=='IDEA': stage='DATA_FEASIBILITY'
    record={'experimentId':eid,'stage':stage,'reason':reason,'finishedAt':utc(),'whatWeTested':contract['hypothesis'],
            'whatHappened':output.get('summary',reason),'whatWeKnow':output.get('known','No completed predictive claim'),
            'whatWeSuspect':output.get('suspected','No inference beyond existing evidence'),
            'whatRemainsUntested':output.get('untested','Unfinished task acceptance criteria'),
            'openedOutcomes':output.get('openedOutcomes','No sealed outcome access authorized; inspect retained task artifacts for development exposure'),
            'whatWouldChangeAnswer':'New causal evidence or a distinct preregistered hypothesis',
            'nextPermittedAction':output.get('nextAction','Inspect the result; do not reset this experiment'),
            'referencedArtifacts':references,'workerResult':result,'contractSha256':experiment.digest(contract)}
    payload=(json.dumps(record,indent=2,sort_keys=True)+'\n').encode(); sha=hashlib.sha256(payload).hexdigest()
    artifact=folder/(sha+'.json');artifact.write_bytes(payload)
    reg=Path(cfg['experimentRoot'])
    with experiment.lock(reg/'.registry.lock'):
        sp=experiment.path_for(reg,eid); state=experiment.load_state(sp)
        if not state.get('result'):
            experiment.finish(state,stage,reason,{'path':str(artifact),'sha256':sha});experiment.atomic(sp,state)
    evidence_id=eid+'-result'
    cp=candidate_path(task); candidate=read(cp)
    # Only the selected candidate's prior current decision is superseded; preserve all other decisions.
    for old in candidate.get('evidence',[]):
        ep=REPO/'research/inventory/evidence'/(old['evidenceId']+'.json'); prior=read(ep)
        for decision in prior['candidateDecisions']:
            if decision['candidateId']==candidate['id']: decision['current']=False
        write(ep,prior)
    evidence={'schemaVersion':'marketlab.evidence-inventory.v2','id':evidence_id,'candidateIds':[candidate['id']],
              'classification':stage,'decision':stage,'summarySource':str(cp.relative_to(REPO)),
              'summary':record['whatHappened'],'candidateDecisions':[{'candidateId':candidate['id'],'stage':stage,'decision':stage,'current':True}],
              'outcomesOpened':False,'openedOutcomes':[],
              'artifacts':[{'role':'experiment-result','sha256':sha,'location':str(artifact),'verification':'LOCAL_FILE'}]+references}
    # Development exposure must be registered by the agent when labels are used; never claim blindness.
    evidence['developmentOutcomeAccess']=contract['outcomeAccess']
    evidence['developmentExposureDescription']=record['openedOutcomes']
    if contract['outcomeAccess']=='DEVELOPMENT_ONLY':
        evidence['outcomesOpened']=True
        evidence['openedOutcomes']=[{'id':eid+'-development','venue':contract['targetVenue'],'target':contract['hypothesis'],'candidateIds':[candidate['id']],'reuseStatus':'OPENED_NOT_CONFIRMATION_ELIGIBLE','periodStatus':'UNKNOWN','unknownReason':'See experiment report and candidate development-period registration; all accessed development outcomes are exploratory.'}]
    write(REPO/'research/inventory/evidence'/(evidence_id+'.json'),evidence)
    candidate['stage']=stage;candidate['nextAction']=record['nextPermittedAction']
    candidate.setdefault('evidence',[]).append({'evidenceId':evidence_id,'classification':stage,'decision':stage,'summary':record['whatHappened'],'sourcePath':str(cp.relative_to(REPO))})
    write(cp,candidate)
    spacepath=REPO/'research/alpha/spaces'/task['spaceId']/'space.json';space=read(spacepath)
    for t in space['readyWork']:
        if t['id']==task['id']:
            accepted=complete and output.get('status')=='COMPLETE'
            t.update(status='DONE' if accepted else 'BLOCKED', completionEvidenceIds=[evidence_id] if accepted else [],
                     externalBlockers=[] if accepted else [f'{stage}: inspect evidence {evidence_id}; original experiment cannot restart'])
    write(spacepath,space)
    return record


class Controller:
    def __init__(self,cfg=None):
        self.cfg=cfg or config();self.root=Path(self.cfg['stateRoot']);self.root.mkdir(parents=True,exist_ok=True)
        self.validation_deadline=None
        self.publisher=Publisher(REPO,self.root,lambda p:checks(p,self.cfg,self.validation_deadline))

    def quota(self):
        with RPC([self.cfg['codex'],'app-server','--listen','stdio://']) as rpc:return rpc.quota(self.cfg['bucket'])

    def live(self,**value):
        prior=read(self.root/'status.json') if (self.root/'status.json').exists() else {}
        write(self.root/'status.json',dict(prior,**value,updatedAt=utc()))

    def publish(self,cycle,title,allowed):
        commit=self.publisher.checkpoint(changed(),title,allowed+[JOURNAL])
        self.live(cycle=cycle,phase='PUBLISHED',lastPushedCommit=commit)
        return commit

    def recover(self):
        active_path=self.root/'active.json'
        if active_path.exists():
            active=read(active_path);task=active['task'];contract=active['contract'];eid=contract['experimentId']
            cp=candidate_path(task)
            evidence_id=eid+'-result'
            already_recorded=any(x.get('evidenceId')==evidence_id for x in read(cp).get('evidence',[]))
            if not already_recorded:
                unit=f'marketlab-explore-worker@{eid}.service'
                active_state=run(['systemctl','--user','show',unit,'--property=ActiveState','--value'])
                if active_state in {'active','activating','deactivating'}:stop_unit(unit)
                result={'reason':'SUPERVISOR_INTERRUPTED'}
                if changed():
                    check_owned(changed(),task)
                    result['recovery']=archive_and_restore(changed(),self.cfg,eid)
                conclude(task,contract,result,self.cfg,False)
                event(active['cycle'],'RECOVERED_INTERRUPTION',experimentId=eid,reason='SUPERVISOR_INTERRUPTED')
            self.publish(active['cycle'],f'Record interrupted experiment {eid}',surfaces(task))
            active_path.unlink()
        elif changed():
            raise RuntimeError('Dirty automation worktree without an active registration; inspect before resume')
        self.publisher.synchronize()
        if (self.root/'outbox.json').exists() and read(self.root/'outbox.json')['status']=='PENDING':self.publisher.push()

    def dispatch(self):
        with locked(self.root/'dispatcher.lock'):
            if (self.root/'paused').exists():return
            state=read(self.root/'schedule.json') if (self.root/'schedule.json').exists() else {'domainCounts':{}}
            cycle=local_day()
            # Publication recovery uses no model allowance. Never launch more work with an outbox.
            self.recover()
            if state.get('lastCycle')==cycle:return
            state['lastCycle']=cycle;write(self.root/'schedule.json',state)
            try:q=self.quota()
            except (RuntimeError,TimeoutError,ValueError) as error:
                event(cycle,'SKIPPED',reason='ALLOWANCE_UNAVAILABLE',summary=str(error)[:500])
                self.publish(cycle,'Record skipped research cycle: allowance unavailable',[JOURNAL])
                self.live(cycle=cycle,phase='SKIPPED',reason='ALLOWANCE_UNAVAILABLE');return
            event(cycle,'CYCLE_CHECK',allowance=q,summary='Main allowance checked; no other bucket may substitute')
            if q['remaining']<=self.cfg['reservePercent']:
                event(cycle,'SKIPPED',reason='ALLOWANCE_RESERVE')
                self.publish(cycle,'Record skipped research cycle: allowance reserve',[JOURNAL])
                self.live(cycle=cycle,phase='SKIPPED',reason='ALLOWANCE_RESERVE',allowance=q);return
            self.publish(cycle,'Record qualifying midnight research cycle',[JOURNAL])
            while not (self.root/'paused').exists():
                try:q=self.quota()
                except (RuntimeError,TimeoutError,ValueError):break
                if q['remaining']<=self.cfg['reservePercent']:break
                task=choose_task(self.cfg,state)
                planned=None
                if task is None:
                    if any(t['priority']=='P0' for t in workspace.ready_work(all_priorities=True)):
                        event(cycle,'BLOCKED',reason='Runnable P0 work requires a permitted bounded task; do not bypass it')
                        self.publish(cycle,'Record P0 research routing blocker',[JOURNAL]);break
                    # One hypothesis-discovery task per domain/day; avoid spending all allowance on repeated planning.
                    task,candidate,contract=discovery_task(self.cfg,cycle,state);task['spaceId']=candidate['spaceId']
                    if candidate_path(task).exists():break
                    planned=(candidate,contract)
                contract=planned[1] if planned else experiment.validate_contract(read(REPO/task['experimentContract']))
                eid=contract['experimentId']
                try: owned_claim=claim(task,self.cfg,eid)
                except RuntimeError as error:
                    event(cycle,'BLOCKED',reason=str(error));self.publish(cycle,'Record research ownership blocker',[JOURNAL]);break
                try:
                    if planned:
                        write(candidate_path(task),planned[0]);write(REPO/task['experimentContract'],contract)
                        sp=REPO/'research/alpha/spaces'/task['spaceId']/'space.json';space=read(sp)
                        space['readyWork'].append({k:v for k,v in task.items() if k!='spaceId'});write(sp,space)
                    # The persistent experiment clock begins before registration checks or any research work.
                    experiment.start(Path(self.cfg['experimentRoot']),REPO/task['experimentContract'],launch_watcher=False,
                                     systemd_unit=f'marketlab-explore-worker@{eid}.service')
                    es=experiment.status(Path(self.cfg['experimentRoot']),eid)
                    self.validation_deadline=es['deadlineUtc']
                    write(self.root/'active.json',{'task':task,'contract':contract,'cycle':cycle,'deadlineUtc':es['deadlineUtc']})
                    event(cycle,'EXPERIMENT_REGISTERED',experimentId=eid,taskId=task['id'],stage='DATA_FEASIBILITY',
                          contractSha256=experiment.digest(contract),contract=contract,deadlineUtc=es['deadlineUtc'])
                    self.publish(cycle,f'Register {eid}: hypothesis and bounded search',surfaces(task))
                    write(self.root/'active.json',{'task':task,'contract':contract,'cycle':cycle,'deadlineUtc':es['deadlineUtc']})
                    result=self.explore(task,contract,cycle,q,es)
                    # Findings are published even when they reject the mechanism or stop for quota.
                    complete=result.get('reason')=='TURN_COMPLETED' and result.get('output',{}).get('status') in {'COMPLETE','BLOCKED'}
                    if complete:
                        try:check_owned(changed(),task);checks(changed(),self.cfg,self.validation_deadline)
                        except Exception as error:
                            result={'reason':'VALIDATION_FAILED','error':str(error),'recovery':archive_and_restore(changed(),self.cfg,eid)};complete=False
                    elif changed():
                        if result.get('reason')=='OWNERSHIP_VIOLATION':raise RuntimeError(result['error'])
                        check_owned(changed(),task)
                        result['recovery']=archive_and_restore(changed(),self.cfg,eid)
                    record=conclude(task,contract,result,self.cfg,complete)
                    event(cycle,'EXPERIMENT_RESULT',experimentId=eid,stage=record['stage'],reason=record['reason'],summary=record['whatHappened'],
                          allowanceStart=q,allowanceEnd=result.get('allowance'),usage=result.get('usage'))
                    self.publish(cycle,f'Record {eid}: {record["stage"]}',surfaces(task))
                    (self.root/'active.json').unlink(missing_ok=True)
                    for domain in task.get('domains',[]):state['domainCounts'][domain]=state['domainCounts'].get(domain,0)+1
                    write(self.root/'schedule.json',state)
                    if result['reason'] in {'ALLOWANCE_RESERVE','ALLOWANCE_UNAVAILABLE','PAUSED','VALIDATION_FAILED','WORKER_FAILED'}:break
                finally:
                    owned_claim.unlink(missing_ok=True);self.validation_deadline=None
            if not changed():
                event(cycle,'CYCLE_STOPPED',summary='No further research admitted; inspect experiment decisions and live status')
                self.publish(cycle,'Close market exploration cycle',[JOURNAL])
            self.live(cycle=cycle,phase='IDLE',lastPushedCommit=self.publisher.git('rev-parse','HEAD'))

    def explore(self,task,contract,cycle,initial,es):
        eid=contract['experimentId']; artifacts=Path(self.cfg['artifactRoot'])/eid;artifacts.mkdir(parents=True,exist_ok=True)
        prior=None;unchanged=0
        for step in range(1,65):
            q=self.quota()
            if q['remaining']<=self.cfg['reservePercent']:return {'reason':'ALLOWANCE_RESERVE','allowance':q}
            if time.time()>=es['deadlineUtc']-contract['reportReserveSeconds']:return {'reason':'BUDGET_EXHAUSTED'}
            job={'task':task,'experimentId':eid,'workDeadline':es['deadlineUtc']-contract['reportReserveSeconds'],
                 'artifactDirectory':str(artifacts),'claimedSurfaces':surfaces(task),'priorHandoff':prior,'step':step}
            self.live(cycle=cycle,experimentId=eid,task=task['id'],phase='AGENT_WORK',allowance=q,deadlineUtc=es['deadlineUtc'])
            result=worker_step(job,contract,self.cfg,self.quota)
            write(artifacts/f'turn-{step}.json',result)
            if result.get('reason')!='TURN_COMPLETED':return result
            output=result['output'];paths=changed()
            if contract['outcomeAccess']=='NONE' and output['stage'] in {'EXPLORATORY','REJECTED'}:
                return {'reason':'WORKER_FAILED','error':'source-only task attempted predictive promotion','output':output}
            if set(paths)!=set(output['files']):
                return {'reason':'WORKER_FAILED','error':'handoff file list differs from actual changes','output':output}
            try: check_owned(paths,task)
            except RuntimeError as error: return {'reason':'OWNERSHIP_VIOLATION','error':str(error)}
            if output['status']=='COMPLETE':
                evidence=output['acceptanceEvidence']
                if set(x['criterion'] for x in evidence)!=set(task['acceptanceCriteria']):
                    return {'reason':'WORKER_FAILED','error':'acceptance criteria lack evidence','output':output}
                for item in evidence:
                    path=Path(item['path'])
                    if not path.is_absolute():path=REPO/path
                    path=path.resolve()
                    if not path.is_file() or not (path.is_relative_to(Path(self.cfg['artifactRoot']).resolve()) or (path.is_relative_to(REPO) and owned(str(path.relative_to(REPO)),surfaces(task)))):
                        return {'reason':'WORKER_FAILED','error':'acceptance evidence missing or outside owned surfaces','output':output}
            if output['status']!='CONTINUE':return result
            unchanged=unchanged+1 if not paths else 0
            if unchanged>=self.cfg['maxNoProgressTurns']:return {'reason':'NO_PROGRESS','output':output}
            if paths:
                event(cycle,'CHECKPOINT',experimentId=eid,summary=output['summary'],stage=output['stage'],usage=result.get('usage'))
                try:self.publish(cycle,f'Advance {eid}: research checkpoint',surfaces(task))
                except Exception as error:
                    # A rejected push is different from a failed local check; never restore committed work.
                    if (self.root/'outbox.json').exists() and read(self.root/'outbox.json')['status']=='PENDING':raise
                    return {'reason':'VALIDATION_FAILED','error':str(error),'output':output}
            prior={k:output[k] for k in ('summary','known','suspected','untested','nextAction')}
        return {'reason':'NO_PROGRESS'}
