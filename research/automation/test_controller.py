"""End-to-end publication tests with real Git and deterministic fake workers."""
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parent))
import controller as c
from core import read, write
from test_core import handoff


class CycleTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=Path.home()/'.tmp');self.root=Path(self.tmp.name)
        self.remote=self.root/'remote.git';self.repo=self.root/'repo'
        self.cmd(self.root,'git','clone','--bare',str(c.REPO),str(self.remote))
        self.cmd(self.root,'git','clone','--branch','automation/market-exploration',str(self.remote),str(self.repo))
        self.cmd(self.repo,'git','config','user.email','test@example.invalid');self.cmd(self.repo,'git','config','user.name','Test')
        self.state=self.root/'state';self.cfg=c.config()
        self.cfg.update(stateRoot=str(self.state),artifactRoot=str(self.root/'artifacts'),experimentRoot=str(self.root/'experiments'))
        self.task=next(t for t in c.workspace.ready_work() if t['id']=='conventional-market-feasibility')
        self.claim=self.root/'claim.md'
        self.repo_patch=patch.object(c,'REPO',self.repo);self.repo_patch.start()
        self.choose_patch=patch.object(c,'choose_task',return_value=self.task);self.choose_patch.start()
        def claim(*args):self.claim.write_text('owned');return self.claim
        self.claim_patch=patch.object(c,'claim',side_effect=claim);self.claim_patch.start()
        self.checks_patch=patch.object(c,'checks',side_effect=self.check);self.checks_patch.start()
        self.control=c.Controller(self.cfg)
        self.quota_patch=patch.object(self.control,'quota',side_effect=[{'remaining':80}]*3+[{'remaining':50}]);self.quota_patch.start()
        self.remote_before=self.cmd(self.repo,'git','rev-parse','HEAD')
    def tearDown(self):
        self.quota_patch.stop();self.checks_patch.stop();self.claim_patch.stop();self.choose_patch.stop();self.repo_patch.stop();self.tmp.cleanup()
    def cmd(self,cwd,*args):
        r=subprocess.run(args,cwd=cwd,capture_output=True,text=True)
        if r.returncode:raise RuntimeError(r.stderr)
        return r.stdout.strip()
    def check(self,paths,*args):
        errors=c.workspace.validate(self.repo/'research/alpha',self.repo)
        self.assertEqual([],errors)
        return {'result':'PASS'}
    def fake(self,*args):
        remote=self.cmd(self.repo,'git','ls-remote','origin','refs/heads/automation/market-exploration').split()[0]
        local=self.cmd(self.repo,'git','rev-parse','HEAD')
        self.assertEqual(remote,local,'worker ran before registration was pushed')
        journal=self.repo/c.JOURNAL/c.local_day()/'events.jsonl'
        self.assertIn('EXPERIMENT_REGISTERED',journal.read_text())
        report=self.root/'artifacts'/'audit.md';report.parent.mkdir(exist_ok=True);report.write_text('Documented source feasibility audit and blockers')
        output=handoff();output['artifacts']=[str(report)];output['acceptanceEvidence']=[{'criterion':x,'path':str(report)} for x in self.task['acceptanceCriteria']]
        return {'reason':'TURN_COMPLETED','output':output,'allowance':{'remaining':55}}
    def test_full_cycle_registers_before_work_and_publishes_result(self):
        with patch.object(c,'worker_step',side_effect=self.fake):self.control.dispatch()
        self.assertFalse(self.claim.exists())
        candidate=read(c.candidate_path(self.task));self.assertEqual('DATA_FEASIBILITY',candidate['stage'])
        ep=read(self.repo/'research/inventory/evidence/conventional-market-feasibility-v1-result.json')
        self.assertFalse(ep['outcomesOpened'])
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])
        space=read(self.repo/'research/alpha/spaces/relative-value/space.json')
        self.assertEqual('DONE',next(t for t in space['readyWork'] if t['id']==self.task['id'])['status'])
        self.assertEqual('',self.cmd(self.repo,'git','status','--porcelain'))
        history=self.cmd(self.repo,'git','log','--format=%s',self.remote_before+'..HEAD')
        self.assertIn('Register conventional-market-feasibility-v1',history)
        self.assertIn('Record conventional-market-feasibility-v1',history)
    def test_allowance_stop_publishes_inconclusive_evidence(self):
        with patch.object(c,'worker_step',return_value={'reason':'ALLOWANCE_RESERVE','allowance':{'remaining':49}}):self.control.dispatch()
        self.assertEqual('INCONCLUSIVE',read(c.candidate_path(self.task))['stage'])
        self.assertFalse((self.state/'active.json').exists())
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])
    def test_duplicate_midnight_never_launches_second_cycle(self):
        with patch.object(c,'worker_step',side_effect=self.fake) as worker:
            self.control.dispatch();self.control.dispatch()
            self.assertEqual(1,worker.call_count)
    def test_failed_code_is_preserved_but_only_failure_evidence_is_pushed(self):
        def fail_worker(*args):
            path=c.candidate_path(self.task).parent/'broken.py';path.write_text('bad code')
            value=self.fake();value['output']['files']=[str(path.relative_to(self.repo))]
            return value
        original=self.check
        def checks(paths,*args):
            if any(p.endswith('broken.py') for p in paths):raise RuntimeError('unit test failed')
            return original(paths,*args)
        with patch.object(c,'worker_step',side_effect=fail_worker),patch.object(c,'checks',side_effect=checks):self.control.dispatch()
        self.assertFalse((c.candidate_path(self.task).parent/'broken.py').exists())
        self.assertTrue(list((self.root/'artifacts').rglob('broken.py')))
        self.assertEqual('OPERATIONALLY_BLOCKED',read(c.candidate_path(self.task))['stage'])
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])
    def test_no_model_runs_below_reserve(self):
        self.quota_patch.stop()
        with patch.object(self.control,'quota',return_value={'remaining':11}),patch.object(c,'worker_step') as worker:self.control.dispatch()
        worker.assert_not_called()
        self.assertIn('SKIPPED',(self.repo/c.JOURNAL/c.local_day()/'events.jsonl').read_text())
    def test_discovery_task_is_valid_and_metadata_only(self):
        task,candidate,contract=c.discovery_task(self.cfg,'2026-09-09',{'domainCounts':{}})
        self.assertEqual('NONE',contract['outcomeAccess']);self.assertEqual(0,contract['maxTrials'])
        self.assertLess(contract['budgetSeconds'],43200)
        self.assertEqual('METADATA_ONLY',task['resourceClass'])
        c.experiment.validate_contract(contract)

    def test_registration_push_failure_prevents_worker_execution(self):
        hook=self.remote/'hooks/pre-receive'
        # Permit the cycle header, reject experiment registration.
        hook.write_text('#!/bin/sh\nwhile read old new ref; do\n subject=$(git log -1 --format=%s "$new")\n case "$subject" in Register*) exit 1;; esac\ndone\nexit 0\n');hook.chmod(0o755)
        self.control.publisher.sleep=lambda _:None
        with patch.object(c,'worker_step') as worker,self.assertRaises(RuntimeError):self.control.dispatch()
        worker.assert_not_called()
        self.assertEqual('PENDING',read(self.state/'outbox.json')['status'])
        self.assertTrue((self.state/'active.json').exists())

    def test_crash_recovery_preserves_deadline_and_publishes_interruption(self):
        with patch.object(c,'worker_step',side_effect=RuntimeError('simulated crash')),self.assertRaises(RuntimeError):self.control.dispatch()
        active=read(self.state/'active.json');eid=active['contract']['experimentId']
        before=read(Path(self.cfg['experimentRoot'])/eid/'state.json')['deadlineUtc']
        original=c.run
        def run(argv,**kw):
            if argv[:2]==['systemctl','--user']:return 'inactive'
            return original(argv,**kw)
        with patch.object(c,'run',side_effect=run):self.control.recover()
        after=read(Path(self.cfg['experimentRoot'])/eid/'state.json')
        self.assertEqual(before,after['deadlineUtc'])
        self.assertEqual('SUPERVISOR_INTERRUPTED',after['result']['reason'])
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])
        self.assertFalse((self.state/'active.json').exists())





class RankedSelectionTest(unittest.TestCase):
    def choose(self, tasks, counts=None):
        import tempfile
        with tempfile.TemporaryDirectory(dir=Path.home()/'.tmp') as temp:
            cfg=c.config();cfg['experimentRoot']=temp
            with patch.object(c.workspace,'ready_work',return_value=tasks):
                return c.choose_task(cfg,{'domainCounts':counts or {}})

    def test_rank_precedes_domain_balance_and_priority_precedes_rank(self):
        from copy import deepcopy
        tasks=deepcopy(c.workspace.ready_work())
        selected=self.choose(tasks,{'conventional':999})
        self.assertEqual('corporate-event-terms-feasibility',selected['id'])
        broad=next(t for t in tasks if t['id']=='conventional-market-feasibility')
        broad['priority']='P0';broad.pop('selectionRank',None)
        self.assertEqual(broad['id'],self.choose(tasks)['id'])
        broad['outcomeAccess']='SINGLE_USE_HISTORICAL_CONFIRMATION'
        self.assertIsNone(self.choose(tasks),'ineligible P0 must not permit bypass to P1')

    def test_equal_ranks_preserve_domain_balance_and_legacy_defaults(self):
        from copy import deepcopy
        tasks=[deepcopy(t) for t in c.workspace.ready_work() if t['selectionRank']==100]
        for t in tasks:t.pop('selectionRank',None)
        selected=self.choose(tasks,{'conventional':20,'prediction-markets':20,'usd-stablecoins':20,'onchain':0})
        self.assertEqual('onchain-settlement-feasibility',selected['id'])


if __name__=='__main__':unittest.main()
