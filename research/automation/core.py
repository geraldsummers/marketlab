"""Local scheduling, allowance, RPC, and publication primitives (standard library)."""
from __future__ import annotations

from contextlib import contextmanager
from datetime import datetime, timezone
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import signal
import subprocess
import threading
import time
from zoneinfo import ZoneInfo

REPO = Path(__file__).resolve().parents[2]
BRANCH = 'automation/market-exploration'


def read(path):
    return json.loads(Path(path).read_text())


def write(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + f'.{os.getpid()}.pending')
    with tmp.open('w') as handle:
        json.dump(value, handle, indent=2, sort_keys=True, allow_nan=False)
        handle.write('\n'); handle.flush(); os.fsync(handle.fileno())
    os.replace(tmp, path)


def config():
    value = read(REPO / 'research/automation/config.json')
    for key in ('stateRoot', 'experimentRoot', 'artifactRoot', 'python', 'javaHome'):
        value[key] = str(Path(value[key].replace('$HOME', str(Path.home()))).resolve())
    return value


def utc():
    return datetime.now(timezone.utc).isoformat()


@contextmanager
def locked(path):
    path = Path(path); path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a') as handle:
        try:
            fcntl.flock(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as error:
            raise RuntimeError('another dispatcher owns the queue') from error
        yield


def run(argv, cwd=REPO, timeout=60, env=None):
    result = subprocess.run(argv, cwd=cwd, env=env, capture_output=True, text=True, timeout=timeout)
    if result.returncode:
        raise RuntimeError(f'{argv[0]} failed ({result.returncode}): {result.stderr[-1500:]}')
    return result.stdout.strip()


def allowance(result, bucket='codex'):
    buckets = result.get('rateLimitsByLimitId')
    value = buckets.get(bucket) if isinstance(buckets, dict) else result.get('rateLimits')
    if not isinstance(value, dict) or value.get('limitId') != bucket:
        raise ValueError('main allowance bucket is missing')
    if value.get('rateLimitReachedType'):
        return {'remaining': 0, 'windows': [], 'observedAt': utc()}
    windows = []
    for name in ('primary', 'secondary'):
        window = value.get(name)
        if window is None:
            continue
        used, duration, reset = (window.get(k) for k in ('usedPercent', 'windowDurationMins', 'resetsAt'))
        if (type(used) not in (int, float) or not math.isfinite(used) or not 0 <= used <= 100
                or type(duration) is not int or duration <= 0 or type(reset) is not int or reset <= time.time()):
            raise ValueError('invalid or stale allowance window')
        windows.append({'name': name, 'remaining': 100-used, 'durationMinutes': duration, 'resetsAt': reset})
    if not windows:
        raise ValueError('no applicable allowance windows')
    return {'remaining': min(w['remaining'] for w in windows), 'windows': windows, 'observedAt': utc()}


def local_day(now=None):
    return (now or datetime.now(timezone.utc)).astimezone(ZoneInfo('Australia/Hobart')).date().isoformat()


class RPC:
    """JSONL app-server transport. No account secrets or tool output are logged."""
    def __init__(self, command=None):
        self.process = subprocess.Popen(command or ['/usr/local/bin/codex','app-server','--listen','stdio://'],
                                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                                        text=True, bufsize=1, start_new_session=True)
        self.inbox = queue.Queue(); self.events = []; self.responses = {}; self.serial = 0
        threading.Thread(target=self._reader, daemon=True).start()
        self.call('initialize', {'clientInfo': {'name':'marketlab_automation','version':'1.0'}})
        self.send({'method':'initialized'})

    def _reader(self):
        try:
            for line in self.process.stdout:
                self.inbox.put(json.loads(line))
        except (ValueError, OSError) as error:
            self.inbox.put({'transportError': str(error)})
        finally:
            self.inbox.put({'transportError':'app-server exited'})

    def send(self, message):
        self.process.stdin.write(json.dumps(message)+'\n'); self.process.stdin.flush()

    def pump(self, timeout=0.2):
        try:
            message = self.inbox.get(timeout=timeout)
        except queue.Empty:
            return None
        if 'transportError' in message:
            raise RuntimeError(message['transportError'])
        if 'id' in message and 'method' in message:
            # Interactive requests are not permission to broaden unattended authority.
            self.send({'id':message['id'],'error':{'code':-32000,'message':'Unattended research cannot answer approval or user-input requests'}})
            raise RuntimeError('agent requested interactive input')
        if 'id' in message:
            self.responses[message['id']] = message
        else:
            self.events.append(message)
        return message

    def call(self, method, params=None, timeout=15):
        self.serial += 1; request = self.serial
        message = {'id':request,'method':method}
        if params is not None: message['params'] = params
        self.send(message)
        end = time.monotonic()+timeout
        while request not in self.responses:
            if time.monotonic() >= end: raise TimeoutError(f'{method} timed out')
            self.pump(min(.2, max(.01, end-time.monotonic())))
        response = self.responses.pop(request)
        if 'error' in response: raise RuntimeError(f'{method}: {response["error"]}')
        return response['result']

    def quota(self, bucket='codex', timeout=15):
        return allowance(self.call('account/rateLimits/read', timeout=timeout), bucket)

    def close(self):
        try:
            os.killpg(self.process.pid, signal.SIGTERM)
            self.process.wait(timeout=3)
        except subprocess.TimeoutExpired:
            os.killpg(self.process.pid, signal.SIGKILL); self.process.wait()
        except ProcessLookupError:
            pass
        self.process.stdin.close(); self.process.stdout.close()

    def __enter__(self): return self
    def __exit__(self, *_): self.close()


def changed(repo=REPO):
    # Porcelain -z handles spaces without interpreting filenames as shell input.
    raw = subprocess.check_output(['git','status','--porcelain=v1','-z','--untracked-files=all'],cwd=repo)
    entries = raw.decode().split('\0'); paths=[]; i=0
    while i < len(entries):
        line=entries[i]; i+=1
        if not line: continue
        if 'R' in line[:2] or 'C' in line[:2]:
            raise RuntimeError('renames/copies require explicit review')
        paths.append(line[3:])
    return sorted(paths)


def owned(path, surfaces):
    return any(path == s or path.startswith(s.rstrip('/')+'/') for s in surfaces)


def fingerprint(repo, paths):
    h=hashlib.sha256()
    for name in sorted(paths):
        p=repo/name; h.update(name.encode())
        if p.is_symlink(): raise RuntimeError(f'symlink changes are not publishable: {name}')
        h.update(p.read_bytes() if p.exists() else b'<deleted>')
    return h.hexdigest()


class Publisher:
    def __init__(self, repo, state, check, remote='origin', branch=BRANCH, sleep=time.sleep):
        self.repo=Path(repo); self.state=Path(state); self.check=check; self.remote=remote; self.branch=branch; self.sleep=sleep

    def git(self, *args): return run(['git',*args],cwd=self.repo,timeout=90)

    def synchronize(self):
        if self.git('branch','--show-current') != self.branch: raise RuntimeError('wrong publishing branch')
        response=self.git('ls-remote','--heads',self.remote,f'refs/heads/{self.branch}')
        if not response: return
        self.git('fetch','--no-tags',self.remote,f'refs/heads/{self.branch}')
        remote=self.git('rev-parse','FETCH_HEAD'); local=self.git('rev-parse','HEAD')
        if remote == local: return
        ancestor=subprocess.run(['git','merge-base','--is-ancestor',remote,local],cwd=self.repo).returncode == 0
        if not ancestor: raise RuntimeError('remote advanced or diverged; human integration required')

    def push(self):
        head=self.git('rev-parse','HEAD')
        write(self.state/'outbox.json',{'commit':head,'branch':self.branch,'status':'PENDING','updatedAt':utc()})
        for attempt in range(3):
            try:
                self.synchronize()
                self.git('push',self.remote,f'HEAD:refs/heads/{self.branch}')
                response=self.git('ls-remote','--heads',self.remote,f'refs/heads/{self.branch}')
                if response.split()[0] != head: raise RuntimeError('remote tip does not match published commit')
                write(self.state/'outbox.json',{'commit':head,'branch':self.branch,'status':'PUBLISHED','updatedAt':utc()})
                return head
            except (RuntimeError, subprocess.TimeoutExpired) as error:
                if 'advanced or diverged' in str(error) or attempt == 2: raise
                self.sleep((2,5)[attempt])
        raise RuntimeError('publication failed')

    def checkpoint(self, paths, title, surfaces):
        actual=changed(self.repo)
        paths=sorted(set(paths))
        if set(actual) != set(paths): raise RuntimeError('unclaimed or unstaged changes exist; refusing partial publication')
        if not all(owned(p,surfaces) for p in paths): raise RuntimeError('checkpoint exceeds claimed surfaces')
        before=fingerprint(self.repo, paths)
        validation=self.check(paths)
        if before != fingerprint(self.repo, paths) or actual != changed(self.repo):
            raise RuntimeError('files changed during validation')
        if paths:
            self.git('add','--',*paths)
            staged=self.git('diff','--cached','--name-only').splitlines()
            if set(staged) != set(paths): raise RuntimeError('index contains unexpected files')
            body=title+('\n\nValidation: '+json.dumps(validation,sort_keys=True) if validation else '')
            self.git('commit','-m',body)
        return self.push()
