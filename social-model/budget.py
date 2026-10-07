"""Local historical research supervision; library unit tests remain independent."""
import os
from pathlib import Path
import sys


def require_budget(**kwargs):
    sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "research/alpha/tools"))
    from experiment import require_budget as require
    return require(**kwargs)


def record_trial():
    if os.environ.get("MARKETLAB_EXPERIMENT_ID"):
        require_budget(model=True)
        from experiment import record_trial as record
        record()
