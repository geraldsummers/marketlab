#!/usr/bin/env python3
"""Control the authorized midnight exploration workflow."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import subprocess
import sys

from core import REPO, RPC, config, locked, read, run, utc, write


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version',action='version',version='marketlab-explore 1.0.0')
    sub=parser.add_subparsers(dest='action',required=True)
    for name in ('dispatch','status','pause','resume','disable','quota','self-test'):
        sub.add_parser(name)
    for name in ('worker','reconcile'):
        p=sub.add_parser(name);p.add_argument('experiment_id')
    args=parser.parse_args();cfg=config();root=Path(cfg['stateRoot']);root.mkdir(parents=True,exist_ok=True)
    try:
        if args.action=='worker':
            from worker import main as worker
            return worker(args.experiment_id)
        if args.action=='reconcile':
            # ExecStopPost may run after hard timeout. It cannot create a new experiment or agent turn.
            sys.path.insert(0,str(REPO/'research/alpha/tools'));import experiment
            value=experiment.status(Path(cfg['experimentRoot']),args.experiment_id)
            print(json.dumps({'experimentId':args.experiment_id,'result':value['result']}));return 0
        if args.action=='dispatch':
            from controller import Controller
            Controller(cfg).dispatch();return 0
        if args.action=='quota':
            with RPC([cfg['codex'],'app-server','--listen','stdio://']) as rpc:print(json.dumps(rpc.quota(cfg['bucket']),indent=2))
            return 0
        if args.action=='status':
            value={'configuration':{'timezone':cfg['timezone'],'model':cfg['model'],'effort':cfg['effort'],'reservePercent':cfg['reservePercent'],'branch':cfg['branch']},
                   'paused':(root/'paused').exists(),'worktree':str(REPO)}
            for name in ('status','schedule','outbox','active','failure'):
                path=root/(name+'.json');value[name]=read(path) if path.exists() else None
            if value.get('active'):
                worker_status=root/'jobs'/value['active']['contract']['experimentId']/'live.json'
                value['worker']=read(worker_status) if worker_status.exists() else None
            value['timer']=run(['systemctl','--user','show','marketlab-explore.timer','--property=ActiveState,NextElapseUSecRealtime'])
            print(json.dumps(value,indent=2));return 0
        if args.action=='pause':
            (root/'paused').write_text(utc()+'\n')
            print('Paused. Active worker will stop; deterministic reporting and publication may finish.');return 0
        if args.action=='resume':
            # Resume means permit the next scheduled cycle, never launch a surprise daytime run.
            from controller import Controller
            controller=Controller(cfg)
            with locked(root/'dispatcher.lock'):
                controller.recover()
                (root/'paused').unlink(missing_ok=True)
            run(['systemctl','--user','enable','--now','marketlab-explore.timer'])
            print('Resumed for the next Hobart midnight.');return 0
        if args.action=='disable':
            (root/'paused').write_text(utc()+'\n')
            run(['systemctl','--user','disable','--now','marketlab-explore.timer'])
            print('Timer disabled. Active worker will stop and report.');return 0
        if args.action=='self-test':
            # Deterministic lifecycle probe: no Codex invocation, research data, or Git mutation.
            probe=root/'self-test.json'
            child=subprocess.Popen([sys.executable,'-c','print("fake research worker")'],stdout=subprocess.PIPE,text=True)
            output=child.communicate(timeout=5)[0]
            if child.returncode:raise RuntimeError('fake worker failed')
            write(probe,{'result':'PASS','output':output.strip(),'cgroup':Path('/proc/self/cgroup').read_text().strip(),'time':utc()})
            print(json.dumps(read(probe)));return 0
    except Exception as error:
        write(root/'failure.json',{'time':utc(),'action':args.action,'error':str(error)[:2000]})
        if args.action=='dispatch':
            prior=read(root/'status.json') if (root/'status.json').exists() else {}
            write(root/'status.json',dict(prior,phase='OPERATIONALLY_BLOCKED',reason=str(error)[:1000],updatedAt=utc()))
        print(f'marketlab-explore: {error}',file=sys.stderr);return 1
    return 0


if __name__=='__main__':raise SystemExit(main())
