from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import time
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import experiment as e


class ExperimentTest(unittest.TestCase):
    def setUp(self):
        temporary = Path.home() / '.tmp'
        temporary.mkdir(exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(dir=temporary)
        self.root = Path(self.temp.name)
        self.contract_path = self.root / 'contract.json'
        self.contract = e.read(Path(__file__).parents[1] / 'templates/experiment.json')
        self.contract.update(experimentId='test-one', candidateId='candidate-one', searchFamilyId='family-one',
                             budgetSeconds=60, reportReserveSeconds=5)

    def tearDown(self):
        for p in self.root.glob('*/state.json'):
            try:
                with e.lock(self.root / '.registry.lock'):
                    s = e.load_state(p)
                    e.finish(s, 'INCONCLUSIVE', 'TEST_CLEANUP')
                    e.atomic(p, s)
            except (OSError, ValueError):
                pass
        time.sleep(0.25)
        self.temp.cleanup()

    def start(self, watcher=False):
        self.contract_path.write_text(json.dumps(self.contract))
        if watcher:
            return e.start(self.root, self.contract_path)
        with patch.object(e.subprocess, 'Popen'):
            return e.start(self.root, self.contract_path)

    def command(self, source):
        return [sys.executable, '-c', source]

    def state(self):
        return e.status(self.root, self.contract['experimentId'])

    def test_contract_boundaries(self):
        for key, value in [('budgetSeconds', 43200), ('budgetSeconds', True), ('budgetSeconds', 0),
                           ('reportReserveSeconds', 60), ('maxTrials', 17), ('memoryMiB', 0),
                           ('domains', ['unknown']), ('outcomeAccess', 'LIVE')]:
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                e.validate_contract(dict(self.contract, **{key: value}))
        e.validate_contract(dict(self.contract, budgetSeconds=42300))

    def test_restart_keeps_deadline_and_candidate_cannot_reset(self):
        first = self.start()
        self.assertEqual(first['deadlineUtc'], self.start()['deadlineUtc'])
        self.contract['experimentId'] = 'new-run-id'
        with self.assertRaisesRegex(ValueError, 'candidate already'):
            self.start()

    def test_contract_and_deadline_tampering(self):
        self.start()
        self.contract['hypothesis'] = 'Changed'
        with self.assertRaisesRegex(ValueError, 'cannot change'):
            self.start()
        path = e.path_for(self.root, 'test-one')
        s = e.read(path); s['deadlineUtc'] += 100; e.atomic(path, s)
        with self.assertRaisesRegex(ValueError, 'deadline changed'):
            self.state()

    def test_expiry_uses_original_clock_and_finalizes(self):
        s = self.start()
        now = dict(s['startClock'], utc=s['startClock']['utc'] + 55, elapsed=s['startClock']['elapsed'] + 55)
        with patch.object(e, 'clock', return_value=now):
            s = self.state()
        self.assertEqual('BUDGET_EXHAUSTED', s['result']['reason'])
        self.assertEqual('INCONCLUSIVE', s['result']['stage'])
        with self.assertRaisesRegex(ValueError, 'terminal'):
            e.run(self.root, 'test-one', self.command('raise Exception()'))

    def test_clock_rollback_and_disagreement_fail_closed(self):
        s = self.start()
        for delta in (-10, 20):
            copy = json.loads(json.dumps(s))
            now = dict(s['lastClock'], utc=s['lastClock']['utc'] + delta)
            with patch.object(e, 'clock', return_value=now):
                e.reconcile(copy)
            self.assertEqual('CLOCK_INCONSISTENT', copy['result']['reason'])

    def test_reboot_does_not_reset_budget(self):
        s = self.start()
        now = dict(s['lastClock'], boot='new-boot', utc=s['startClock']['utc'] + 58, elapsed=1)
        with patch.object(e, 'clock', return_value=now):
            e.reconcile(s)
        self.assertEqual('BUDGET_EXHAUSTED', s['result']['reason'])

    def test_success_requires_evidence_and_cannot_be_overwritten(self):
        self.start()
        self.assertEqual(0, e.run(self.root, 'test-one', self.command('print("ok")')))
        self.assertIsNone(self.state()['result'])
        report = self.root / 'report.md'; report.write_text('DATA_FEASIBILITY: source coverage is missing.')
        s = e.finalize(self.root, 'test-one', 'DATA_FEASIBILITY', report)
        self.assertEqual(64, len(s['result']['evidence']['sha256']))
        with self.assertRaisesRegex(ValueError, 'cannot be replaced'):
            e.finalize(self.root, 'test-one', 'EXPLORATORY', report)
        with self.assertRaises(ValueError):
            e.finalize(self.root, 'test-one', 'BLIND_VALIDATED', report)

    def test_failure_and_retry_are_both_recorded(self):
        self.start()
        self.assertEqual(3, e.run(self.root, 'test-one', self.command('raise SystemExit(3)')))
        self.assertEqual(0, e.run(self.root, 'test-one', self.command('pass')))
        self.assertEqual([3, 0], [a['exitCode'] for a in self.state()['attempts']])
        self.assertEqual('COMMAND_FAILED', self.state()['attempts'][0]['reason'])

    def test_missing_command_counts_failed_launch(self):
        self.start()
        with self.assertRaises(OSError):
            e.run(self.root, 'test-one', ['/does-not-exist'])
        self.assertEqual('LAUNCH_FAILED', self.state()['result']['reason'])

    def test_trial_and_family_budget_include_retries(self):
        self.contract.update(resourceClass='CPU_MODEL', maxTrials=2, familyTrialBudget=2)
        self.start()
        e.run(self.root, 'test-one', self.command('raise SystemExit(1)'), trials=1)
        e.run(self.root, 'test-one', self.command('pass'), trials=1)
        with self.assertRaisesRegex(ValueError, 'trial budget exhausted'):
            e.run(self.root, 'test-one', self.command('pass'), trials=1)
        self.contract.update(experimentId='test-two', candidateId='candidate-two', predecessorExperimentIds=['test-one'])
        self.start()
        with self.assertRaisesRegex(ValueError, 'trial budget exhausted'):
            e.run(self.root, 'test-two', self.command('pass'), trials=1)

    def test_family_successor_requires_link_and_fixed_budget(self):
        self.start()
        self.contract.update(experimentId='test-two', candidateId='candidate-two')
        with self.assertRaisesRegex(ValueError, 'predecessor'):
            self.start()
        self.contract.update(predecessorExperimentIds=['test-one'], familyTrialBudget=17)
        with self.assertRaisesRegex(ValueError, 'family trial budget cannot change'):
            self.start()

    def test_duplicate_run_lock_and_model_resource_lock(self):
        self.start()
        with e.lock(self.root / 'test-one/.run.lock'):
            with self.assertRaisesRegex(ValueError, 'another invocation'):
                e.run(self.root, 'test-one', self.command('pass'))
        with e.lock(self.root / '.data.lock'):
            with self.assertRaisesRegex(ValueError, 'another invocation'):
                e.run(self.root, 'test-one', self.command('pass'))

    def test_memory_limit_preserves_distinct_stage(self):
        self.start()
        with patch.object(e, 'rss_mib', return_value=100000):
            s = self.state()
        self.assertEqual('MEMORY_LIMIT', s['result']['reason'])
        self.assertEqual('OPERATIONALLY_BLOCKED', s['result']['stage'])

    def test_guard_checks_supervision_trials_and_outcome_boundary(self):
        self.contract.update(resourceClass='CPU_MODEL', maxTrials=1)
        self.start()
        tools = str(Path(__file__).resolve().parent)
        source = f'import sys; sys.path.insert(0, {tools!r}); from experiment import require_budget; require_budget(model=True)'
        self.assertEqual(0, e.run(self.root, 'test-one', self.command(source), trials=1))
        source = f'import sys; sys.path.insert(0, {tools!r}); from experiment import require_budget; require_budget(outcome_access="SINGLE_USE_HISTORICAL_CONFIRMATION")'
        self.assertNotEqual(0, e.run(self.root, 'test-one', self.command(source)))
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'start and run'):
            e.require_budget()

    def test_threads_affinity_gpu_and_temp_are_bounded(self):
        self.start()
        output = self.root / 'env.json'
        source = f'import json, os; from pathlib import Path; Path({str(output)!r}).write_text(json.dumps(dict(threads=os.environ["OMP_NUM_THREADS"], gpu=os.environ["CUDA_VISIBLE_DEVICES"], cpus=len(os.sched_getaffinity(0)), tmp=os.environ["TMPDIR"])))'
        e.run(self.root, 'test-one', self.command(source))
        result = e.read(output)
        self.assertEqual('', result['gpu'])
        self.assertLessEqual(result['cpus'], 2)
        self.assertEqual('2', result['threads'])
        self.assertTrue(result['tmp'].startswith(str(self.root)))

    def test_idle_watcher_expires_without_further_user_command(self):
        self.contract.update(budgetSeconds=2, reportReserveSeconds=1)
        self.start(watcher=True)
        time.sleep(1.4)
        self.assertEqual('BUDGET_EXHAUSTED', e.read(self.root / 'test-one/state.json')['result']['reason'])

    def test_timeout_kills_process_group_and_records_handoff(self):
        self.contract.update(budgetSeconds=3, reportReserveSeconds=1)
        self.start(watcher=True)
        output = self.root / 'child.pid'
        source = f'import subprocess, time; from pathlib import Path; p=subprocess.Popen(["sleep", "30"]); Path({str(output)!r}).write_text(str(p.pid)); time.sleep(30)'
        self.assertEqual(124, e.run(self.root, 'test-one', self.command(source)))
        pid = int(output.read_text())
        stat = Path(f'/proc/{pid}/stat')
        if stat.exists():
            self.assertEqual('Z', stat.read_text().rsplit(')', 1)[1].split()[0])
        self.assertEqual('INCONCLUSIVE', self.state()['result']['stage'])

    def test_cli_trial_option_and_missing_experiment(self):
        self.contract.update(resourceClass='CPU_MODEL', maxTrials=1)
        self.start()
        cli = [sys.executable, e.__file__, '--root', str(self.root)]
        result = subprocess.run(cli + ['run', '--trials', '1', 'test-one', '--'] + self.command('pass'), capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual(1, self.state()['attempts'][0]['trials'])
        result = subprocess.run(cli + ['status', 'missing'], capture_output=True)
        self.assertNotEqual(0, result.returncode)

    def test_actual_trials_cannot_exceed_reservation(self):
        self.contract.update(resourceClass='CPU_MODEL', maxTrials=1)
        self.start()
        tools = str(Path(__file__).resolve().parent)
        source = f'import sys; sys.path.insert(0, {tools!r}); from experiment import record_trial; record_trial(); record_trial()'
        self.assertNotEqual(0, e.run(self.root, 'test-one', self.command(source), trials=1))
        self.assertEqual(1, self.state()['attempts'][0]['actualTrials'])

    def test_killed_runner_is_cleaned_up_by_independent_watcher(self):
        self.start(watcher=True)
        cli = [sys.executable, e.__file__, '--root', str(self.root), 'run', 'test-one', '--']
        runner = subprocess.Popen(cli + self.command('import time; time.sleep(30)'), stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        until = time.monotonic() + 3
        state = self.state()
        while not state.get('child') and time.monotonic() < until:
            time.sleep(0.05)
            state = self.state()
        self.assertIsNotNone(state.get('child'))
        runner.kill(); runner.wait()
        time.sleep(0.6)
        final = e.read(self.root / 'test-one/state.json')
        self.assertEqual('SUPERVISOR_INTERRUPTED', final['result']['reason'])
        stat = Path(f"/proc/{state['child']['pid']}/stat")
        if stat.exists():
            self.assertEqual('Z', stat.read_text().rsplit(')', 1)[1].split()[0])

    def test_successful_command_cannot_leave_background_children(self):
        self.start()
        output = self.root / 'child.pid'
        source = f'import subprocess; from pathlib import Path; p=subprocess.Popen(["sleep", "30"]); Path({str(output)!r}).write_text(str(p.pid))'
        self.assertEqual(0, e.run(self.root, 'test-one', self.command(source)))
        stat = Path(f'/proc/{int(output.read_text())}/stat')
        if stat.exists():
            self.assertEqual('Z', stat.read_text().rsplit(')', 1)[1].split()[0])

    def test_launch_does_not_execute_before_durable_registration(self):
        self.start()
        output = self.root / 'registered.json'
        source = f'from pathlib import Path; import json, os; state=json.loads(Path({str(self.root / "test-one/state.json")!r}).read_text()); assert state["child"]["pid"] == os.getpid(); Path({str(output)!r}).write_text("registered")'
        self.assertEqual(0, e.run(self.root, 'test-one', self.command(source)))
        self.assertTrue(output.exists())


if __name__ == '__main__':
    unittest.main()
