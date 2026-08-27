from __future__ import annotations

import hashlib
import io
import json
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.artifacts import sha256_file
from marketlab_alpha.binance_normalize import build_daily_universe_observations, normalize_kline_manifest


class BinanceNormalizeTest(unittest.TestCase):
    def _manifest_for_csv(self, root: Path, csv_payload: str) -> Path:
        objects = root / "objects/aa"
        objects.mkdir(parents=True)
        buffer = io.BytesIO()
        with zipfile.ZipFile(buffer, "w") as archive:
            archive.writestr("BTCUSDT-1m.csv", csv_payload)
        payload = buffer.getvalue()
        digest = hashlib.sha256(payload).hexdigest()
        archive_path = objects / f"{digest}.zip"
        archive_path.write_bytes(payload)
        manifest = root / "manifest.json"
        manifest.write_text(json.dumps({
            "schemaVersion": "marketlab.binance-archive-manifest.v1",
            "entries": [{
                "status": "ACQUIRED", "request": {"dataType": "klines", "symbol": "BTCUSDT"},
                "archiveObject": {"path": str(archive_path.relative_to(root)), "sha256": digest},
            }],
        }))
        return manifest

    def test_exact_archive_becomes_causal_bar_rows(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            objects = root / "objects/aa"
            objects.mkdir(parents=True)
            buffer = io.BytesIO()
            with zipfile.ZipFile(buffer, "w") as archive:
                archive.writestr(
                    "BTCUSDT-1m.csv",
                    "open_time,open,high,low,close,volume,close_time,quote_volume,count,taker_base,taker_quote,ignore\n"
                    "1735689600000,100,102,99,101,2,1735689659999,202,10,1.2,121.2,0\n"
                    "1735689660000,101,103,100,102,3,1735689719999,306,12,2,204,0\n",
                )
            payload = buffer.getvalue()
            digest = hashlib.sha256(payload).hexdigest()
            archive_path = objects / f"{digest}.zip"
            archive_path.write_bytes(payload)
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({
                "schemaVersion": "marketlab.binance-archive-manifest.v1",
                "entries": [{
                    "status": "ACQUIRED", "request": {"dataType": "klines", "symbol": "BTCUSDT", "interval": "1m"},
                    "archiveObject": {"path": str(archive_path.relative_to(root)), "sha256": digest},
                }],
            }))
            output = root / "bars.jsonl"
            result = normalize_kline_manifest(manifest, output)
            rows = [json.loads(line) for line in output.read_text().splitlines()]
            self.assertEqual(2, result["rows"])
            self.assertEqual("BTC", rows[0]["symbol"])
            self.assertEqual(1735689660000, rows[0]["availableTimeEpochMillis"])
            self.assertEqual(121.2, rows[0]["takerBuyQuoteVolume"])
            self.assertEqual(sha256_file(output), result["outputSha256"])

            observations = root / "observations.jsonl"
            aggregate = build_daily_universe_observations(output, observations)
            self.assertEqual(0, aggregate["rows"])
            self.assertEqual(1, len(aggregate["quarantinedDays"]))

    def test_complete_utc_day_becomes_one_liquidity_observation(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            normalized = root / "bars.jsonl"
            rows = [
                {
                    "symbol": "BTC", "eventTimeEpochMillis": 1735689600000,
                    "availableTimeEpochMillis": 1735732800000, "quoteVolume": 202.0,
                },
                {
                    "symbol": "BTC", "eventTimeEpochMillis": 1735732800000,
                    "availableTimeEpochMillis": 1735776000000, "quoteVolume": 306.0,
                },
            ]
            normalized.write_text("".join(json.dumps(row) + "\n" for row in rows))
            normalized.with_suffix(".jsonl.manifest.json").write_text(json.dumps({
                "schemaVersion": "marketlab.binance-normalized-klines.v1",
                "outputSha256": sha256_file(normalized),
            }))
            observations = root / "observations.jsonl"
            aggregate = build_daily_universe_observations(normalized, observations)
            daily = [json.loads(line) for line in observations.read_text().splitlines()]
            self.assertEqual(1, aggregate["rows"])
            self.assertEqual(508.0, daily[0]["quoteNotional"])
            self.assertEqual(1735776000000, daily[0]["observedAtEpochMillis"])
            self.assertEqual(2, daily[0]["sourceRows"])

    def test_unordered_normalized_rows_are_stablely_sorted_for_observations(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            normalized = root / "bars.jsonl"
            rows = [
                {
                    "symbol": "BTC", "eventTimeEpochMillis": 1735732800000,
                    "availableTimeEpochMillis": 1735776000000, "quoteVolume": 306.0,
                },
                {
                    "symbol": "BTC", "eventTimeEpochMillis": 1735689600000,
                    "availableTimeEpochMillis": 1735732800000, "quoteVolume": 202.0,
                },
            ]
            normalized.write_text("".join(json.dumps(row) + "\n" for row in rows))
            normalized.with_suffix(".jsonl.manifest.json").write_text(json.dumps({
                "schemaVersion": "marketlab.binance-normalized-klines.v1",
                "outputSha256": sha256_file(normalized),
            }))
            observations = root / "observations.jsonl"
            aggregate = build_daily_universe_observations(normalized, observations)
            daily = [json.loads(line) for line in observations.read_text().splitlines()]
            self.assertEqual(1, aggregate["rows"])
            self.assertEqual(508.0, daily[0]["quoteNotional"])

    def test_daily_observations_reject_changed_normalized_bytes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            normalized = root / "bars.jsonl"
            normalized.write_text("{}\n")
            manifest = normalized.with_suffix(".jsonl.manifest.json")
            manifest.write_text(json.dumps({
                "schemaVersion": "marketlab.binance-normalized-klines.v1",
                "outputSha256": "0" * 64,
            }))
            with self.assertRaises(ValueError):
                build_daily_universe_observations(normalized, root / "observations.jsonl")

    def test_impossible_volume_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            manifest = self._manifest_for_csv(
                root,
                "1735689600000,100,102,99,101,2,1735689659999,202,10,3,303,0\n",
            )
            with self.assertRaisesRegex(ValueError, "taker-buy"):
                normalize_kline_manifest(manifest, root / "bars.jsonl")


if __name__ == "__main__":
    unittest.main()
