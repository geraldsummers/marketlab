from __future__ import annotations

import csv
import hashlib
import io
import json
import math
import sys
import tempfile
import unittest
import zipfile
from datetime import datetime, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

try:
    from marketlab_alpha.variance_exposure import ASSETS, HOUR, funding_requests, parse_funding, replay
except ModuleNotFoundError as error:
    if error.name not in {"numpy", "sklearn"}:
        raise
    OPTIONAL_DEPENDENCY_ERROR = error
else:
    OPTIONAL_DEPENDENCY_ERROR = None


@unittest.skipIf(OPTIONAL_DEPENDENCY_ERROR is not None, str(OPTIONAL_DEPENDENCY_ERROR))
class VarianceExposureTest(unittest.TestCase):
    def _funding_manifest(self, root: Path, gap_millis: int) -> Path:
        entries = []
        start = int(datetime(2026, 7, 1, tzinfo=timezone.utc).timestamp() * 1000)
        for asset in ASSETS:
            relative = Path("objects") / f"{asset}.zip"
            archive = root / relative
            archive.parent.mkdir(parents=True, exist_ok=True)
            rows = io.StringIO()
            writer = csv.writer(rows)
            writer.writerow(("calc_time", "funding_interval_hours", "last_funding_rate"))
            writer.writerow((start, 8, "0.0001"))
            writer.writerow((start + gap_millis, 8, "-0.0002"))
            with zipfile.ZipFile(archive, "w") as handle:
                handle.writestr(f"{asset}USDT-fundingRate-2026-07.csv", rows.getvalue())
            entries.append(
                {
                    "status": "ACQUIRED",
                    "uri": f"https://example.invalid/{asset}.zip",
                    "request": {"symbol": f"{asset}USDT"},
                    "archiveObject": {
                        "path": str(relative),
                        "sha256": hashlib.sha256(archive.read_bytes()).hexdigest(),
                    },
                }
            )
        manifest = root / "manifest.json"
        manifest.write_text(json.dumps({"entries": entries}))
        return manifest

    def test_funding_requests_are_bounded_to_registered_assets(self):
        requests = funding_requests("2026-07")
        self.assertEqual(len(ASSETS), len(requests))
        self.assertEqual({f"{asset}USDT" for asset in ASSETS}, {request.symbol for request in requests})
        self.assertTrue(all(request.data_type == "fundingRate" for request in requests))

    def test_official_eight_hour_timestamp_jitter_is_accepted_but_missing_event_is_not(self):
        with tempfile.TemporaryDirectory() as temporary:
            parsed = parse_funding(self._funding_manifest(Path(temporary), 8 * HOUR + 10), "2026-07")
            self.assertEqual(set(ASSETS), set(parsed))
        with tempfile.TemporaryDirectory() as temporary:
            manifest = self._funding_manifest(Path(temporary), 8 * HOUR + 1_001)
            with self.assertRaisesRegex(ValueError, "gap exceeds eight hours"):
                parse_funding(manifest, "2026-07")

    def test_replay_applies_de_risk_only_scaling_funding_and_round_trip_turnover(self):
        decision = 0
        returns = {(decision, asset): 0.01 for asset in ASSETS}
        forecasts = {
            (decision, asset): (math.log(0.2**2), math.log(0.1**2))
            for asset in ASSETS
        }
        targets = {asset: 0.1 for asset in ASSETS}
        funding = {asset: [(HOUR, 0.001)] for asset in ASSETS}

        rows, metrics, values = replay(
            [decision], returns, forecasts, targets, funding, "social", 10.0
        )

        self.assertAlmostEqual(0.5, metrics["meanGrossExposure"])
        self.assertAlmostEqual(1.0, metrics["totalTurnover"])
        self.assertAlmostEqual(0.0005, metrics["totalFunding"])
        self.assertAlmostEqual(0.0035, values[0])
        self.assertEqual(1, len(rows))


if __name__ == "__main__":
    unittest.main()
