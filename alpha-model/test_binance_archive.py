from __future__ import annotations

import hashlib
import sys
import tempfile
import unittest
import urllib.error
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.binance_archive import ArchiveRequest, acquire_binance_archives, month_range
from marketlab_alpha.archive_discovery import discover_archive_symbols


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

    def test_historical_prefix_discovery_filters_quote_and_current_metadata(self):
        page = b'''<?xml version="1.0" encoding="UTF-8"?>
<ListBucketResult xmlns="http://s3.amazonaws.com/doc/2006-03-01/">
  <IsTruncated>false</IsTruncated>
  <CommonPrefixes><Prefix>data/futures/um/monthly/klines/BTCUSDT/</Prefix></CommonPrefixes>
  <CommonPrefixes><Prefix>data/futures/um/monthly/klines/DELISTEDUSDT/</Prefix></CommonPrefixes>
  <CommonPrefixes><Prefix>data/futures/um/monthly/klines/ETHUSDC/</Prefix></CommonPrefixes>
</ListBucketResult>'''
        with tempfile.TemporaryDirectory() as temporary:
            result = discover_archive_symbols(Path(temporary), fetch=lambda _: page)
            self.assertEqual(["BTCUSDT", "DELISTEDUSDT"], result["symbols"])
            self.assertIn("current exchange metadata prohibited", result["symbolSource"])
            resumed = discover_archive_symbols(Path(temporary), fetch=lambda _: self.fail("must not refetch"))
            self.assertEqual(result["symbols"], resumed["symbols"])

    def test_interrupted_download_resumes_from_verified_request_checkpoints(self):
        first = ArchiveRequest("um", "klines", "BTCUSDT", "2025-01", "5m")
        second = ArchiveRequest("um", "klines", "ETHUSDT", "2025-01", "5m")
        payloads = {first.uri: b"btc", second.uri: b"eth"}
        calls = []

        def checksum(uri):
            calls.append(uri)
            if uri.startswith(second.uri):
                raise RuntimeError("transient interruption")
            payload = payloads[uri.removesuffix(".CHECKSUM")]
            return hashlib.sha256(payload).hexdigest().encode() if uri.endswith(".CHECKSUM") else payload

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            with self.assertRaisesRegex(RuntimeError, "transient"):
                acquire_binance_archives([first, second], root, fetch=checksum)
            first_calls = list(calls)
            calls.clear()

            def recovered(uri):
                calls.append(uri)
                payload = payloads[uri.removesuffix(".CHECKSUM")]
                return hashlib.sha256(payload).hexdigest().encode() if uri.endswith(".CHECKSUM") else payload

            result = acquire_binance_archives([first, second], root, fetch=recovered)
            self.assertEqual(2, len(result["entries"]))
            self.assertTrue(any(uri.startswith(first.uri) for uri in first_calls))
            self.assertFalse(any(uri.startswith(first.uri) for uri in calls))


if __name__ == "__main__":
    unittest.main()
