#!/usr/bin/env bash
set -euo pipefail

artifact_root=${MARKETLAB_ALPHA_ARTIFACT_ROOT:-/mnt/media/marketlab/artifacts/archive-alpha-v2}
status_path="$artifact_root/status.json"
if [[ -f "$status_path" ]]; then
    cat "$status_path"
else
    printf '%s\n' '{"schemaVersion":"marketlab.archive-alpha-operational-status.v1","campaignId":"archive-directional-gpu-v2","phase":"NOT_DISPATCHED","confirmationOpened":false}'
fi
python3 - "$artifact_root" <<'PY'
import json, sys
from pathlib import Path
artifact = Path(sys.argv[1])
data = Path(str(artifact).replace("/artifacts/", "/raw/"))
if data.name == "archive-alpha-v2":
    discovery = data / "discovery/symbols.txt"
    research = data / "research-symbols.txt"
    daily_done = len(list((data / "daily-archives/requests").glob("*/*.json")))
    five_done = len(list((data / "five-minute-archives/requests").glob("*/*.json")))
    symbols = sum(1 for _ in discovery.open()) if discovery.is_file() else 0
    research_symbols = sum(1 for _ in research.open()) if research.is_file() else 0
    print(json.dumps({
        "schemaVersion": "marketlab.archive-alpha-progress.v1",
        "dailyRequestsCompleted": daily_done,
        "dailyRequestCeiling": symbols * 79,
        "fiveMinuteRequestsCompleted": five_done,
        "fiveMinuteRequestCeiling": research_symbols * 79,
    }, sort_keys=True, separators=(",", ":")))
PY
systemctl --user show marketlab-archive-alpha-v2.service \
    --property=ActiveState,SubState,Result,NRestarts --no-pager 2>/dev/null || true
for search_status in "$artifact_root"/search-*/status.json; do
    [[ -f "$search_status" ]] || continue
    cat "$search_status"
done
