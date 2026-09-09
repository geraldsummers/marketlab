#!/usr/bin/env python3
"""Durable, local experiment budgets. No market outcomes are read by this tool."""
from __future__ import annotations

import argparse
from contextlib import contextmanager
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import stat
import sys
import time
import threading

MAX_SECONDS = 42300
DEFAULT_RESERVE = 900
ROOT = Path.home() / '.local/share/marketlab/experiments'
ID = re.compile(r'^[a-z0-9]+(?:-[a-z0-9]+)*$')
STAGES = {'DATA_FEASIBILITY', 'EXPLORATORY', 'REJECTED', 'INCONCLUSIVE', 'DATA_BLOCKED', 'OPERATIONALLY_BLOCKED'}
DOMAINS = {'crypto', 'conventional', 'onchain', 'prediction-markets', 'usd-stablecoins'}


def read(path):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError(f'duplicate key: {key}')
            result[key] = value
        return result
    return json.loads(Path(path).read_text(), object_pairs_hook=pairs)


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()


def atomic(path, value):
    path = Path(path)
    temporary = path.with_suffix('.pending')
    with temporary.open('w') as handle:
        json.dump(value, handle, indent=2, sort_keys=True, allow_nan=False)
        handle.write('\n')
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, path)
    fd = os.open(path.parent, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


@contextmanager
def lock(path, blocking=True):
    with Path(path).open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
        except BlockingIOError as error:
            raise ValueError('another invocation owns this experiment/resource') from error
        yield


def validate_contract(c):
    if not isinstance(c, dict) or c.get('schemaVersion') != 'marketlab.experiment.v1':
        raise ValueError('unsupported experiment contract')
    for key in ('experimentId', 'candidateId', 'searchFamilyId'):
        if not isinstance(c.get(key), str) or not ID.fullmatch(c[key]):
            raise ValueError(f'invalid {key}')
    for key in ('hypothesis', 'targetMarket', 'targetVenue', 'decisionClock', 'outcomeAccess'):
        if not isinstance(c.get(key), str) or not c[key].strip():
            raise ValueError(f'missing {key}')
    if c['outcomeAccess'] not in {'NONE', 'DEVELOPMENT_ONLY', 'SINGLE_USE_HISTORICAL_CONFIRMATION'}:
        raise ValueError('invalid outcomeAccess; budget is not outcome authorization')
    if not isinstance(c.get('domains'), list) or not c['domains'] or any(not isinstance(d, str) or d not in DOMAINS for d in c['domains']):
        raise ValueError('invalid domains')
    for key, minimum, maximum in (('budgetSeconds', 1, MAX_SECONDS), ('reportReserveSeconds', 1, MAX_SECONDS - 1),
                                  ('maxTrials', 0, 16), ('familyTrialBudget', 0, 256),
                                  ('blasThreads', 1, 4), ('memoryMiB', 64, 32768)):
        if type(c.get(key)) is not int or not minimum <= c[key] <= maximum:
            raise ValueError(f'invalid {key}')
    if c['reportReserveSeconds'] >= c['budgetSeconds'] or c['maxTrials'] > c['familyTrialBudget']:
        raise ValueError('invalid reserve or family budget')
    if c.get('resourceClass') not in {'METADATA_ONLY', 'DATA_IO', 'CPU_MODEL'} or type(c.get('gpu')) is not bool:
        raise ValueError('invalid resource class or GPU setting')
    if c['gpu'] and (c['resourceClass'] != 'CPU_MODEL' or not c.get('gpuJustification')):
        raise ValueError('GPU requires a model resource and justification')
    if c['resourceClass'] != 'CPU_MODEL' and c['maxTrials']:
        raise ValueError('non-model experiments cannot reserve model trials')
    if not isinstance(c.get('predecessorExperimentIds'), list) or any(not isinstance(x, str) or not ID.fullmatch(x) for x in c['predecessorExperimentIds']):
        raise ValueError('predecessorExperimentIds must list experiment IDs')
    return c


def identity(pid):
    try:
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return {'pid': pid, 'startTicks': fields[19]}
    except (FileNotFoundError, ProcessLookupError):
        return None


def alive(process):
    if not process or identity(process['pid']) != process:
        return False
    try:
        return Path(f"/proc/{process['pid']}/stat").read_text().rsplit(')', 1)[1].split()[0] != 'Z'
    except (FileNotFoundError, ProcessLookupError):
        return False


def clock():
    return {'utc': time.time(), 'boot': Path('/proc/sys/kernel/random/boot_id').read_text().strip(),
            'elapsed': time.clock_gettime(time.CLOCK_BOOTTIME)}


def remaining(s):
    now = clock()
    if now['utc'] + 1 < s['lastClock']['utc']:
        raise ValueError('wall clock moved backwards')
    if now['boot'] == s['lastClock']['boot']:
        wall_delta = now['utc'] - s['lastClock']['utc']
        elapsed_delta = now['elapsed'] - s['lastClock']['elapsed']
        if elapsed_delta < 0 or abs(wall_delta - elapsed_delta) > 5:
            raise ValueError('wall and elapsed clocks disagree')
    s['lastClock'] = now
    left = s['deadlineUtc'] - now['utc']
    if now['boot'] == s['startClock']['boot']:
        left = min(left, s['contract']['budgetSeconds'] - (now['elapsed'] - s['startClock']['elapsed']))
    return left


def terminate(process):
    # A dead leader may still have living descendants in its original group.
    if process and (identity(process['pid']) == process or identity(process['pid']) is None):
        try:
            os.killpg(process['pid'], signal.SIGKILL)
        except ProcessLookupError:
            pass


def rss_mib(process):
    if not alive(process):
        return 0
    total = 0
    for path in Path('/proc').glob('[0-9]*/stat'):
        try:
            fields = path.read_text().rsplit(')', 1)[1].split()
            if int(fields[2]) == process['pid']:
                total += int(fields[21]) * os.sysconf('SC_PAGE_SIZE')
        except (OSError, ValueError, IndexError):
            continue
    return total / 1048576


def finish(s, stage, reason, evidence=None):
    terminate(s.get('child'))
    s['result'] = {'stage': stage, 'reason': reason, 'completedAtUtc': time.time(),
                   'evidence': evidence, 'contractSha256': s['contractSha256'],
                   'openedOutcomes': 'Consult candidate and single-use outcome ledger; supervisor does not inspect outcomes.',
                   'nextPermittedAction': 'Record this result in the candidate and inventory; do not reset this experiment.'}
    s['child'] = None
    for attempt in s['attempts']:
        if attempt.get('endedAtUtc') is None:
            attempt.update(endedAtUtc=time.time(), reason=reason)


def reconcile(s):
    if s.get('result'):
        return
    try:
        left = remaining(s)
    except ValueError:
        finish(s, 'OPERATIONALLY_BLOCKED', 'CLOCK_INCONSISTENT')
        return
    if left <= s['contract']['reportReserveSeconds']:
        finish(s, 'INCONCLUSIVE', 'BUDGET_EXHAUSTED')
    elif any(a.get('endedAtUtc') is None for a in s['attempts']) and not alive(s.get('runner')):
        finish(s, 'OPERATIONALLY_BLOCKED', 'SUPERVISOR_INTERRUPTED')
    elif rss_mib(s.get('child')) > s['contract']['memoryMiB']:
        finish(s, 'OPERATIONALLY_BLOCKED', 'MEMORY_LIMIT')


def path_for(root, experiment_id):
    if not ID.fullmatch(experiment_id):
        raise ValueError('invalid experiment ID')
    return root / experiment_id / 'state.json'


def load_state(path):
    s = read(path)
    validate_contract(s['contract'])
    if digest(s['contract']) != s['contractSha256']:
        raise ValueError('stored contract changed')
    if s['deadlineUtc'] != s['startClock']['utc'] + s['contract']['budgetSeconds']:
        raise ValueError('stored deadline changed')
    return s


def start(root, contract_path, *, launch_watcher=True, systemd_unit=None):
    c = validate_contract(read(contract_path))
    root.mkdir(parents=True, exist_ok=True)
    with lock(root / '.registry.lock'):
        path = path_for(root, c['experimentId'])
        existing = [load_state(p) for p in root.glob('*/state.json')]
        if path.exists():
            s = load_state(path)
            if s['contractSha256'] != digest(c):
                raise ValueError('experiment contract cannot change')
            reconcile(s)
            atomic(path, s)
        else:
            if any(x['contract']['candidateId'] == c['candidateId'] for x in existing):
                raise ValueError('candidate already has an experiment; resume its original identity')
            family = [x for x in existing if x['contract']['searchFamilyId'] == c['searchFamilyId']]
            if any(x['contract']['familyTrialBudget'] != c['familyTrialBudget'] for x in family):
                raise ValueError('family trial budget cannot change')
            known = {x['contract']['experimentId'] for x in existing}
            if not set(c['predecessorExperimentIds']) <= known:
                raise ValueError('unknown predecessor experiment')
            family_ids = {x['contract']['experimentId'] for x in family}
            if family and not family_ids.intersection(c['predecessorExperimentIds']):
                raise ValueError('family successor must link predecessor evidence from this family')
            now = clock()
            s = {'schemaVersion': 'marketlab.experiment-state.v1', 'contract': c,
                 'contractSha256': digest(c), 'startClock': now, 'lastClock': now,
                 'deadlineUtc': now['utc'] + c['budgetSeconds'], 'attempts': [],
                 'child': None, 'runner': None, 'result': None,
                 'supervision': {'kind':'SYSTEMD' if systemd_unit else 'PROCESS_GROUP', 'unit':systemd_unit}}
            path.parent.mkdir()
            atomic(path, s)
        # The watcher lives independently of the invoking terminal and also expires idle experiments.
        if not s.get('result') and launch_watcher:
            with (path.parent / 'watcher.log').open('a') as log:
                watcher = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--root', str(root), '_watch', c['experimentId']],
                                 stdin=subprocess.DEVNULL, stdout=log, stderr=log, start_new_session=True)
                threading.Thread(target=watcher.wait, daemon=True).start()
        return s


def status(root, experiment_id):
    with lock(root / '.registry.lock'):
        path = path_for(root, experiment_id)
        s = load_state(path)
        reconcile(s)
        atomic(path, s)
        return s


def watch(root, experiment_id):
    path = path_for(root, experiment_id)
    try:
        with lock(path.parent / '.watch.lock', blocking=False):
            while not status(root, experiment_id)['result']:
                time.sleep(0.2)
    except ValueError as error:
        if 'another invocation' not in str(error):
            raise


def run(root, experiment_id, command, trials=0):
    if not command:
        raise ValueError('a foreground command is required')
    path = path_for(root, experiment_id)
    with lock(path.parent / '.run.lock', blocking=False):
        s = status(root, experiment_id)
        c = s['contract']
        if s['result']:
            raise ValueError('experiment is terminal')
        resource = '.model.lock' if c['resourceClass'] == 'CPU_MODEL' else '.data.lock'
        # One data command at a time is below the workspace maximum of two audits.
        with lock(root / resource, blocking=False):
            with lock(root / '.registry.lock'):
                s = load_state(path)
                reconcile(s)
                if s['result']:
                    raise ValueError('experiment expired before launch')
                if type(trials) is not int or trials < 0 or (c['resourceClass'] != 'CPU_MODEL' and trials):
                    raise ValueError('invalid trial reservation')
                used = sum(a['trials'] for a in s['attempts'])
                family_used = sum(a['trials'] for p in root.glob('*/state.json')
                                  if (v := load_state(p))['contract']['searchFamilyId'] == c['searchFamilyId']
                                  for a in v['attempts'])
                if used + trials > c['maxTrials'] or family_used + trials > c['familyTrialBudget']:
                    raise ValueError('experiment or family trial budget exhausted')
                env = os.environ.copy()
                env.update(MARKETLAB_EXPERIMENT_ROOT=str(root), MARKETLAB_EXPERIMENT_ID=experiment_id,
                           MARKETLAB_EXPERIMENT_SHA256=s['contractSha256'],
                           MARKETLAB_EXPERIMENT_WORK_DEADLINE=str(s['deadlineUtc'] - c['reportReserveSeconds']),
                           MARKETLAB_EXPERIMENT_TRIALS=str(trials))
                for key in ('OPENBLAS_NUM_THREADS', 'OMP_NUM_THREADS', 'MKL_NUM_THREADS', 'NUMEXPR_NUM_THREADS'):
                    env[key] = str(c['blasThreads'])
                if not c['gpu']:
                    env['CUDA_VISIBLE_DEVICES'] = ''
                env['TMPDIR'] = str(path.parent / 'tmp')
                Path(env['TMPDIR']).mkdir(exist_ok=True)
                s['runner'] = identity(os.getpid())
                attempt = {'command': command, 'trials': trials, 'startedAtUtc': time.time(), 'endedAtUtc': None}
                s['attempts'].append(attempt)
                # Persist reservation before spawning; failed launches also count.
                atomic(path, s)
                try:
                    if shutil.which(command[0]) is None:
                        raise FileNotFoundError(command[0])
                    child = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), '--root', str(root),
                                              '_child', experiment_id, '--', *command], env=env, start_new_session=True)
                except OSError:
                    finish(s, 'OPERATIONALLY_BLOCKED', 'LAUNCH_FAILED')
                    atomic(path, s)
                    raise
                s['child'] = identity(child.pid)
                atomic(path, s)
            try:
                while child.poll() is None:
                    if status(root, experiment_id)['result']:
                        break
                    time.sleep(0.1)
            finally:
                # Clean up even if state validation fails; never leave unbudgeted descendants.
                try:
                    os.killpg(child.pid, signal.SIGKILL)
                except ProcessLookupError:
                    pass
                code = child.wait()
                with lock(root / '.registry.lock'):
                    s = load_state(path)
                    reconcile(s)
                    s['child'] = None
                    s['attempts'][-1].update(endedAtUtc=time.time(), exitCode=code)
                    if code and not s['result']:
                        s['attempts'][-1]['reason'] = 'COMMAND_FAILED'
                        s['lastFailureStage'] = 'OPERATIONALLY_BLOCKED'
                    atomic(path, s)
            return 124 if s.get('result', {}) and s['result']['reason'] == 'BUDGET_EXHAUSTED' else (1 if s['result'] else (code if code >= 0 else 1))


def finalize(root, experiment_id, stage, evidence_path):
    if stage not in STAGES:
        raise ValueError('supervisor cannot grant epistemic promotion')
    evidence_path = Path(evidence_path).resolve()
    if not stat.S_ISREG(evidence_path.stat().st_mode):
        raise ValueError('evidence must be a regular file')
    initial = status(root, experiment_id)
    if initial['result']:
        raise ValueError('terminal result cannot be replaced')
    checksum = hashlib.sha256()
    with evidence_path.open('rb') as evidence_file:
        while chunk := evidence_file.read(1048576):
            if time.time() >= initial['deadlineUtc'] - initial['contract']['reportReserveSeconds']:
                status(root, experiment_id)
                raise ValueError('evidence hashing exceeded work deadline')
            checksum.update(chunk)
    evidence = {'path': str(evidence_path), 'sha256': checksum.hexdigest()}
    path = path_for(root, experiment_id)
    with lock(path.parent / '.run.lock', blocking=False), lock(root / '.registry.lock'):
        s = load_state(path)
        reconcile(s)
        if s['result']:
            atomic(path, s)
            raise ValueError('terminal result cannot be replaced')
        finish(s, stage, 'EVIDENCE_RECORDED', evidence)
        atomic(path, s)
        return s


def require_budget(*, model=False, outcome_access=None):
    """Guard local research CLIs; this does not authorize opening outcomes."""
    experiment_id = os.environ.get('MARKETLAB_EXPERIMENT_ID')
    if not experiment_id:
        raise ValueError('use research/alpha/tools/experiment.py start and run before research execution')
    root = Path(os.environ.get('MARKETLAB_EXPERIMENT_ROOT', str(ROOT)))
    s = status(root, experiment_id)
    if s['result'] or s['contractSha256'] != os.environ.get('MARKETLAB_EXPERIMENT_SHA256') or not alive(s.get('runner')):
        raise ValueError('missing, expired, or changed supervised experiment')
    if not s.get('child') or os.getpgrp() != s['child']['pid']:
        raise ValueError('command is outside the supervised process group')
    if model and (s['contract']['resourceClass'] != 'CPU_MODEL' or int(os.environ.get('MARKETLAB_EXPERIMENT_TRIALS', '0')) <= 0):
        raise ValueError('model command requires a trial reservation')
    if outcome_access and s['contract']['outcomeAccess'] != outcome_access:
        raise ValueError('experiment outcome-access contract does not permit this command')
    return s



def child_command(root, experiment_id, command):
    # The child may not execute user work until its identity is durably registered.
    path = path_for(root, experiment_id)
    until = time.monotonic() + 5
    while time.monotonic() < until:
        with lock(root / '.registry.lock'):
            s = load_state(path)
            if s.get('result') or not alive(s.get('runner')):
                raise ValueError('launch lost its supervisor')
            if s.get('child') == identity(os.getpid()):
                break
        time.sleep(0.02)
    else:
        raise ValueError('launch registration timed out')
    os.sched_setaffinity(0, sorted(os.sched_getaffinity(0))[:s['contract']['blasThreads']])
    require_budget()
    os.execvpe(command[0], command, os.environ)


def record_trial():
    """Charge one actual model trial against the current command reservation."""
    state = require_budget(model=True)
    root = Path(os.environ['MARKETLAB_EXPERIMENT_ROOT'])
    path = path_for(root, state['contract']['experimentId'])
    with lock(root / '.registry.lock'):
        state = load_state(path)
        reconcile(state)
        if state['result']:
            raise ValueError('experiment ended before trial')
        attempt = state['attempts'][-1]
        actual = attempt.get('actualTrials', 0)
        if actual >= attempt['trials']:
            raise ValueError('command trial reservation exhausted')
        attempt['actualTrials'] = actual + 1
        atomic(path, state)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--root', type=Path, default=ROOT, help='dedicated registry; override only for isolated tests')
    sub = parser.add_subparsers(dest='action', required=True)
    p = sub.add_parser('start'); p.add_argument('--contract', type=Path, required=True)
    for action in ('status', '_watch', '_child', 'run', 'finalize'):
        p = sub.add_parser(action); p.add_argument('experiment_id')
        if action == 'run':
            p.add_argument('--trials', type=int, default=0)
        if action in {'run', '_child'}:
            p.add_argument('command', nargs=argparse.REMAINDER)
        if action == 'finalize':
            p.add_argument('--stage', choices=sorted(STAGES), required=True)
            p.add_argument('--evidence', type=Path, required=True)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        if args.action == 'start': result = start(root, args.contract)
        elif args.action == 'status': result = status(root, args.experiment_id)
        elif args.action == '_watch': watch(root, args.experiment_id); return 0
        elif args.action == 'finalize': result = finalize(root, args.experiment_id, args.stage, args.evidence)
        else:
            command = args.command
            if command[:1] == ['--']: command = command[1:]
            if args.action == '_child':
                child_command(root, args.experiment_id, command)
                return 0
            return run(root, args.experiment_id, command, args.trials)
        print(json.dumps(result, indent=2, sort_keys=True))
        return 0
    except (OSError, ValueError, KeyError) as error:
        print(f'experiment: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
