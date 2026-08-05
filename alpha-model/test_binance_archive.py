from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.binance_archive import ArchiveRequest, acquire_binance_archives, month_range


class BinanceArchiveTest(unittest.TestCase):
    def test_uri_checksum_and_content_addressed_manifest(self):
        request = ArchiveRequest("um", "klines", "BTCUSDT", "2025-01", "1m")
        payload = b"exact zip bytes"
        checksum = hashlib.sha256(payload).hexdigest().encode() + b"  BTCUSDT.zip\n"
        responses = {request.uri: payload, request.uri + ".CHECKSUM": checksum}
        with tempfile.TemporaryDirectory() as temporary:
            result = acquire_binance_archives([request], Path(temporary), fetch=responses.__getitem__)
            entry = result["entries"][0]
            self.assertEqual("ACQUIRED", entry["status"])
            self.assertTrue((Path(temporary) / entry["archiveObject"]["path"]).is_file())
            self.assertEqual(request.uri, entry["uri"])

    def test_missing_archive_is_not_zero_activity(self):
        request = ArchiveRequest("um", "fundingRate", "ETHUSDT", "2020-01")

        def missing(uri):
            raise urllib.error.HTTPError(uri, 404, "missing", {}, None)

        with tempfile.TemporaryDirectory() as temporary:
            result = acquire_binance_archives([request], Path(temporary), fetch=missing)
            self.assertEqual("MISSING", result["entries"][0]["status"])
            self.assertIn("not zero", result["entries"][0]["interpretation"])

    def test_month_range_and_request_shape_are_bounded(self):
        self.assertEqual(("2025-11", "2025-12", "2026-01"), month_range("2025-11", "2026-01"))
        with self.assertRaisesRegex(ValueError, "requires an interval"):
            ArchiveRequest("um", "klines", "BTCUSDT", "2025-01")


if __name__ == "__main__":
    unittest.main()
