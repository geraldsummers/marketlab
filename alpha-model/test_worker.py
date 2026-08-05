from __future__ import annotations

import importlib.util
import json
import sys
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.artifacts import sha256_file
from marketlab_alpha.worker import _publish_result, _validate_temporal_coordinates, execute


@unittest.skipUnless(
    all(importlib.util.find_spec(name) for name in ("duckdb", "numpy", "sklearn")),
    "worker integration requires pinned Parquet and model dependencies",
)
class WorkerProtocolTest(unittest.TestCase):
    def test_hash_authorized_parquet_fit_predict(self):
        import duckdb

        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            input_root, output = root / "input", root / "output"
            input_root.mkdir()
            output.mkdir()
            training, test = input_root / "training.parquet", input_root / "test.parquet"
            connection = duckdb.connect(":memory:")
            try:
                connection.execute("CREATE TABLE training(row_id VARCHAR, x DOUBLE, label DOUBLE)")
                connection.executemany("INSERT INTO training VALUES (?, ?, ?)", [(f"r{i}", float(i), float(i) * 0.1) for i in range(30)])
                connection.execute(f"COPY training TO '{training}' (FORMAT PARQUET)")
                connection.execute("CREATE TABLE testing(row_id VARCHAR, x DOUBLE)")
                connection.executemany("INSERT INTO testing VALUES (?, ?)", [(f"t{i}", float(i)) for i in range(5)])
                connection.execute(f"COPY testing TO '{test}' (FORMAT PARQUET)")
            finally:
                connection.close()
            train_hash, test_hash = sha256_file(training), sha256_file(test)
            deadline = int((datetime.now(timezone.utc) + timedelta(hours=1)).timestamp() * 1000)
            dataset = lambda name, digest: {
                "artifactId": name, "uri": (input_root / f"{name}.parquet").as_uri(),
                "contentHash": digest, "format": "PARQUET",
                "featureColumns": ["x"], "rowIdColumn": "row_id",
            }
            request = {
                "protocolVersion": 1, "requestId": "request-1", "runId": "run-1", "trialId": "trial-1",
                "runtime": "PYTHON", "estimator": "ridge", "parameters": {"alpha": "1.0"},
                "randomSeed": 7,
                "training": {"features": dataset("training", train_hash), "labelColumn": "label"},
                "testFeatures": dataset("test", test_hash), "deadline": {"epochMillis": deadline},
            }
            manifest = {
                "protocolVersion": 1, "capabilityId": "python-torch-gpu-v1", "seed": 7,
                "deadline": {"epochMillis": deadline}, "immutableInputHashes": sorted([train_hash, test_hash]),
                "outputConstraints": {
                    "outputDirectory": str(output), "maximumBytes": 1_000_000,
                    "allowedMediaTypes": ["application/json", "application/octet-stream", "application/vnd.apache.parquet"],
                },
            }
            response = execute(manifest, request, output, input_root=input_root)
            self.assertTrue(response["type"].endswith("FitPredictResponse.Success"))
            self.assertEqual("ridge", response["diagnostics"]["estimator"])
            self.assertTrue((output / "predictions.parquet").is_file())
            self.assertTrue((output / "model.pickle").is_file())
            self.assertEqual(sha256_file(output / "model.pickle"), response["model"]["contentHash"])

    def test_temporal_shape_must_be_explicit(self):
        from marketlab_alpha.models import validate_input_shape

        with self.assertRaisesRegex(ValueError, "timestep"):
            validate_input_shape("gru", (10, 12))
        _validate_temporal_coordinates(
            ["t000__return", "t000__variance", "t001__return", "t001__variance"],
            2,
            2,
            ["return", "variance"],
        )
        with self.assertRaisesRegex(ValueError, "ordered timestep"):
            _validate_temporal_coordinates(
                ["t000__return", "t001__return", "t000__variance", "t001__variance"],
                2,
                2,
                ["return", "variance"],
            )

    def test_result_is_not_published_when_projected_output_exceeds_limit(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            (output / "predictions.parquet").write_bytes(b"x" * 512)
            manifest = {"outputConstraints": {"maximumBytes": 600}}
            response = {
                "type": "dev.marketlab.contracts.worker.FitPredictResponse.Success",
                "protocolVersion": 1,
                "requestId": "request-1",
                "diagnostics": {"detail": "y" * 256},
            }
            with self.assertRaisesRegex(ValueError, "byte limit"):
                _publish_result(output, response, manifest)
            self.assertFalse((output / "result.json").exists())


if __name__ == "__main__":
    unittest.main()
