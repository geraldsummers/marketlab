from datetime import datetime, timezone
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0,str(Path(__file__).parent))
from core import allowance, local_day, Publisher, changed, read, write
from worker import validate_output, execute


def quota(used=10,secondary=None):
    return {'rateLimitsByLimitId':{'codex':{'limitId':'codex','primary':{'usedPercent':used,'windowDurationMins':10080,'resetsAt':int(time.time())+99999},'secondary':secondary}}}


class QuotaTest(unittest.TestCase):
    def test_weekly_only_and_strict_boundary(self):
        self.assertEqual(51,allowance(quota(49))['remaining'])
        self.assertEqual(50,allowance(quota(50))['remaining'])
        self.assertEqual(11,allowance(quota(89))['remaining'])

    def test_restrictive_reported_window_wins(self):
        w={'usedPercent':80,'windowDurationMins':300,'resetsAt':int(time.time())+1000}
        self.assertEqual(20,allowance(quota(10,w))['remaining'])

    def test_missing_main_bucket_cannot_fall_back_to_spark(self):
        v=quota();v['rateLimitsByLimitId']['spark']=v['rateLimitsByLimitId'].pop('codex')
        with self.assertRaises(ValueError):allowance(v)

    def test_invalid_and_stale_values_fail_closed(self):
        for value in (-1,101,True,float('nan'),'30'):
            with self.subTest(value=value),self.assertRaises(ValueError):allowance(quota(value))
        v=quota();v['rateLimitsByLimitId']['codex']['primary']['resetsAt']=1
        with self.assertRaises(ValueError):allowance(v)
        with self.assertRaises(ValueError):allowance({})

    def test_hobart_standard_and_daylight_time(self):
        self.assertEqual('2026-07-02',local_day(datetime(2026,7,1,14,tzinfo=timezone.utc)))
        self.assertEqual('2026-12-02',local_day(datetime(2026,12,1,13,tzinfo=timezone.utc)))
        self.assertEqual('2026-07-01',local_day(datetime(2026,7,1,13,tzinfo=timezone.utc)))


class PublicationTest(unittest.TestCase):
    def setUp(self):
        root=Path.home()/'.tmp';root.mkdir(exist_ok=True)
        self.tmp=tempfile.TemporaryDirectory(dir=root);self.root=Path(self.tmp.name)
        self.repo=self.root/'work';self.remote=self.root/'remote.git';self.state=self.root/'state'
        self.git_at(self.root,'init','--bare',str(self.remote))
        self.git_at(self.root,'init','-b','automation/market-exploration',str(self.repo))
        self.git('config','user.email','test@example.invalid');self.git('config','user.name','Test')
        (self.repo/'research').mkdir();(self.repo/'research/base.md').write_text('base\n')
        self.git('add','research/base.md');self.git('commit','-m','base');self.git('remote','add','origin',str(self.remote))
        self.git('push','-u','origin','HEAD')
        self.checked=[]
        self.publisher=Publisher(self.repo,self.state,lambda paths:self.checked.append(paths),sleep=lambda _:None)

    def tearDown(self):self.tmp.cleanup()
    def git_at(self,cwd,*args):
        r=subprocess.run(['git',*args],cwd=cwd,capture_output=True,text=True)
        if r.returncode:raise RuntimeError(r.stderr)
        return r.stdout.strip()
    def git(self,*args):return self.git_at(self.repo,*args)
    def edit(self):
        (self.repo/'research/test.md').write_text('registered hypothesis\n')
        return ['research/test.md']

    def test_commit_push_and_remote_tip(self):
        head=self.publisher.checkpoint(self.edit(),'Register experiment',['research'])
        self.assertEqual(head,self.git('ls-remote','origin','refs/heads/automation/market-exploration').split()[0])
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])
        self.assertEqual([['research/test.md']],self.checked)

    def test_unrelated_dirty_file_is_not_staged(self):
        paths=self.edit();(self.repo/'personal.txt').write_text('user-owned')
        with self.assertRaisesRegex(RuntimeError,'unclaimed'):
            self.publisher.checkpoint(paths,'bad',['research'])
        self.assertEqual('',self.git('diff','--cached','--name-only'))
        self.assertTrue((self.repo/'personal.txt').exists())

    def test_failed_check_never_commits_or_pushes(self):
        before=self.git('rev-parse','HEAD')
        def fail(_):raise RuntimeError('test failed')
        self.publisher.check=fail
        with self.assertRaises(RuntimeError):self.publisher.checkpoint(self.edit(),'bad',['research'])
        self.assertEqual(before,self.git('rev-parse','HEAD'))
        self.assertTrue((self.repo/'research/test.md').exists())

    def test_push_rejection_preserves_outbox_then_recovers(self):
        hook=self.remote/'hooks/pre-receive';hook.write_text('#!/bin/sh\nexit 1\n');hook.chmod(0o755)
        with self.assertRaises(RuntimeError):self.publisher.checkpoint(self.edit(),'pending',['research'])
        head=self.git('rev-parse','HEAD');self.assertEqual('PENDING',read(self.state/'outbox.json')['status'])
        hook.unlink();self.assertEqual(head,self.publisher.push())
        self.assertEqual('PUBLISHED',read(self.state/'outbox.json')['status'])

    def test_remote_advance_stops_without_force_push(self):
        other=self.root/'other';self.git_at(self.root,'clone','--branch','automation/market-exploration',str(self.remote),str(other))
        self.git_at(other,'config','user.email','test@example.invalid');self.git_at(other,'config','user.name','Other')
        (other/'research/other.md').write_text('other');self.git_at(other,'add','.');self.git_at(other,'commit','-m','other');self.git_at(other,'push')
        before=self.git('rev-parse','HEAD')
        with self.assertRaisesRegex(RuntimeError,'advanced or diverged'):self.publisher.synchronize()
        self.assertEqual(before,self.git('rev-parse','HEAD'))

    def test_changed_during_check_is_rejected(self):
        paths=self.edit()
        def edit(_):(self.repo/'research/test.md').write_text('changed')
        self.publisher.check=edit
        with self.assertRaisesRegex(RuntimeError,'changed during validation'):self.publisher.checkpoint(paths,'bad',['research'])

    def test_symlink_is_not_published(self):
        (self.repo/'research/link').symlink_to('/etc/passwd')
        with self.assertRaises(RuntimeError):self.publisher.checkpoint(['research/link'],'bad',['research'])


class FakeRPC:
    def __init__(self,quotas,complete=True):self.quotas=iter(quotas);self.events=[];self.calls=[];self.complete=complete
    def call(self,method,params=None,timeout=15):
        self.calls.append(method)
        if method=='thread/start':return {'thread':{'id':'thread'}}
        if method=='turn/start':
            if self.complete:self.events=[{'method':'turn/completed','params':{'turn':{'id':'turn','status':'completed','items':[{'type':'agentMessage','text':json.dumps(handoff())}]}}}]
            return {'turn':{'id':'turn'}}
        return {}
    def quota(self,_,timeout=15):
        v=next(self.quotas)
        if isinstance(v,Exception):raise v
        return {'remaining':v,'windows':[]}
    def pump(self,timeout=.2):time.sleep(.001)


def handoff():
    return dict(status='COMPLETE',stage='DATA_FEASIBILITY',summary='Source audit',known='History missing',suspected='None',untested='Prediction',openedOutcomes='None',nextAction='Document blocker',files=[],artifacts=[],acceptanceEvidence=[])


class WorkerTest(unittest.TestCase):
    def setUp(self):
        self.tmp=tempfile.TemporaryDirectory(dir=Path.home()/'.tmp');self.status=Path(self.tmp.name)/'status.json'
        self.cfg={'model':'gpt-6-astra','effort':'medium','bucket':'codex','reservePercent':50,'pollSeconds':.01,'staleSeconds':.02,'turnMaxSeconds':1}
        self.job={'workDeadline':time.time()+60}
    def tearDown(self):self.tmp.cleanup()
    def test_complete_structured_result(self):
        rpc=FakeRPC([80]);v=execute(rpc,self.job,self.cfg,self.status)
        self.assertEqual('COMPLETE',v['output']['status'])
    def test_threshold_interrupts_active_turn(self):
        rpc=FakeRPC([80,50],complete=False);v=execute(rpc,self.job,self.cfg,self.status)
        self.assertEqual('ALLOWANCE_RESERVE',v['reason']);self.assertIn('turn/interrupt',rpc.calls)
    def test_skip_before_model_turn(self):
        rpc=FakeRPC([49]);v=execute(rpc,self.job,self.cfg,self.status)
        self.assertEqual('ALLOWANCE_RESERVE',v['reason']);self.assertNotIn('turn/start',rpc.calls)
    def test_stale_telemetry_interrupts(self):
        rpc=FakeRPC([80]+[TimeoutError()]*10,complete=False)
        v=execute(rpc,self.job,self.cfg,self.status)
        self.assertEqual('ALLOWANCE_UNAVAILABLE',v['reason'])
    def test_pause_interrupts(self):
        rpc=FakeRPC([80],complete=False)
        self.assertEqual('PAUSED',execute(rpc,self.job,self.cfg,self.status,lambda:True)['reason'])
    def test_model_promotion_schema_rejects(self):
        v=handoff();v['stage']='BLIND_VALIDATED'
        with self.assertRaises(ValueError):validate_output(v)


class TransportTest(unittest.TestCase):
    def test_jsonl_handshake_and_quota_without_model_execution(self):
        from core import RPC
        server = """
import sys,json,time
for line in sys.stdin:
 m=json.loads(line)
 if 'id' not in m: continue
 if m['method']=='initialize': result={'platformFamily':'unix'}
 elif m['method']=='account/rateLimits/read': result={'rateLimits':{'limitId':'codex','primary':{'usedPercent':12,'windowDurationMins':10080,'resetsAt':int(time.time())+10000},'secondary':None}}
 else: raise Exception('unexpected model or account request')
 print(json.dumps({'id':m['id'],'result':result}),flush=True)
"""
        with RPC([sys.executable,'-c',server]) as rpc:
            self.assertEqual(88,rpc.quota()['remaining'])


if __name__=='__main__':unittest.main()
