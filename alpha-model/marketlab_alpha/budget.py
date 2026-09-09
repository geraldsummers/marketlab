"""Local research entrypoint adapter for the workspace experiment supervisor."""
from pathlib import Path
import sys


def require_budget(**kwargs):
    tools = Path(__file__).resolve().parents[2] / "research/alpha/tools"
    if not tools.is_dir():
        raise ValueError("local research requires a source checkout with the experiment supervisor")
    sys.path.insert(0, str(tools))
    from experiment import require_budget as require
    return require(**kwargs)
