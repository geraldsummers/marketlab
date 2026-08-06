"""Immutable discovery of historically present Binance archive symbols."""

from __future__ import annotations

import re
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ElementTree
from datetime import datetime, timezone
from pathlib import Path
from typing import Callable

from .artifacts import read_json, sha256_bytes, sha256_file, write_once_bytes, write_once_json


DEFAULT_BUCKET = "https://s3-ap-northeast-1.amazonaws.com/data.binance.vision"


def discover_archive_symbols(
    output_root: Path,
    *,
    market: str = "um",
    data_type: str = "klines",
    symbol_pattern: str = r"^[A-Z0-9]+USDT$",
    bucket_url: str = DEFAULT_BUCKET,
    fetch: Callable[[str], bytes] | None = None,
) -> dict:
    """List archive-owned symbol prefixes; never consult current exchange metadata."""
    if market not in {"um", "cm"}:
        raise ValueError("market must be um or cm")
    pattern = re.compile(symbol_pattern)
    output_root = output_root.absolute()
    manifest_path = output_root / "manifest.json"
    if manifest_path.exists():
        manifest = read_json(manifest_path)
        if manifest.get("schemaVersion") != "marketlab.binance-archive-discovery.v1":
            raise ValueError(f"discovery manifest schema is invalid: {manifest_path}")
        expected_identity = {
            "bucketUrl": bucket_url,
            "prefix": f"data/futures/{market}/monthly/{data_type}/",
            "market": market,
            "dataType": data_type,
            "symbolPattern": symbol_pattern,
        }
        if any(manifest.get(key) != value for key, value in expected_identity.items()):
            raise ValueError("discovery manifest identity differs from the frozen invocation")
        manifest["artifactSha256"] = sha256_file(manifest_path)
        return manifest
    fetch = fetch or _fetch
    prefix = f"data/futures/{market}/monthly/{data_type}/"
    continuation: str | None = None
    pages = []
    symbols: set[str] = set()
    while True:
        query = {"list-type": "2", "delimiter": "/", "prefix": prefix}
        if continuation:
            query["continuation-token"] = continuation
        url = bucket_url.rstrip("/") + "?" + urllib.parse.urlencode(query)
        payload = fetch(url)
        digest = sha256_bytes(payload)
        object_path = output_root / "objects" / digest[:2] / f"{digest}.xml"
        if not object_path.exists():
            write_once_bytes(object_path, payload)
        root = ElementTree.fromstring(payload)
        namespace = {"s3": "http://s3.amazonaws.com/doc/2006-03-01/"}
        for item in root.findall("s3:CommonPrefixes/s3:Prefix", namespace):
            value = (item.text or "").removeprefix(prefix).rstrip("/")
            if value and pattern.fullmatch(value):
                symbols.add(value)
        pages.append({
            "url": url,
            "sha256": digest,
            "bytes": len(payload),
            "objectPath": str(object_path.relative_to(output_root)),
        })
        truncated = (root.findtext("s3:IsTruncated", default="false", namespaces=namespace).lower() == "true")
        continuation = root.findtext("s3:NextContinuationToken", default="", namespaces=namespace)
        if not truncated:
            break
        if not continuation:
            raise ValueError("truncated bucket listing has no continuation token")
    if not symbols:
        raise ValueError("archive discovery returned no symbols matching the frozen policy")
    manifest = {
        "schemaVersion": "marketlab.binance-archive-discovery.v1",
        "classification": "RETROSPECTIVE_DISCOVERY",
        "retrievedAt": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "bucketUrl": bucket_url,
        "prefix": prefix,
        "market": market,
        "dataType": data_type,
        "symbolPattern": symbol_pattern,
        "symbolSource": "historical archive prefixes; current exchange metadata prohibited",
        "symbols": sorted(symbols),
        "pages": pages,
    }
    manifest["artifactSha256"] = write_once_json(manifest_path, manifest)
    return manifest


def _fetch(url: str) -> bytes:
    request = urllib.request.Request(url, headers={"User-Agent": "marketlab-alpha-archive/2"})
    with urllib.request.urlopen(request, timeout=120) as response:
        return response.read()
