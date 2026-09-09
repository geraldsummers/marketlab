#!/usr/bin/env python3
"""Install verified user units; enabling is an explicit CLI option."""
from pathlib import Path
import argparse
import shutil
import subprocess


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--enable',action='store_true');args=parser.parse_args()
    root=Path(__file__).resolve().parents[2]
    expected=Path.home()/'.local/share/marketlab/automation-worktree'
    if root != expected:raise SystemExit('install from the dedicated automation worktree')
    subprocess.run(['systemctl','--user','show-environment'],check=True,stdout=subprocess.DEVNULL)
    bindir=Path.home()/'.local/bin';bindir.mkdir(parents=True,exist_ok=True)
    launcher=bindir/'marketlab-explore'
    launcher.write_text('#!/bin/sh\nexec /usr/bin/python3 "$HOME/.local/share/marketlab/automation-worktree/research/automation/main.py" "$@"\n')
    launcher.chmod(0o755)
    units=Path.home()/'.config/systemd/user';units.mkdir(parents=True,exist_ok=True)
    for source in (root/'research/automation/systemd').glob('*'):
        target=units/source.name
        if target.exists() and target.read_bytes()!=source.read_bytes():
            backups=Path.home()/'.local/state/marketlab/automation/unit-backups';backups.mkdir(parents=True,exist_ok=True)
            import time
            shutil.copy2(target,backups/(target.name+'.'+str(time.time_ns())))
        shutil.copy2(source,target)
        subprocess.run(['systemd-analyze','--user','verify',str(target)],check=True)
    subprocess.run(['systemctl','--user','daemon-reload'],check=True)
    if args.enable:subprocess.run(['systemctl','--user','enable','--now','marketlab-explore.timer'],check=True)
    subprocess.run(['zsh','-lic','command -v marketlab-explore && marketlab-explore --version'],check=True)


if __name__=='__main__':main()
