"""Python implementation of Marketlab worker protocol v1."""

from __future__ import annotations

import hashlib
import json
import os
import pickle
import re
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Mapping
from urllib.parse import unquote, urlparse

from .artifacts import canonical_json_bytes, sha256_file, write_once_bytes
from .models import InputKind, build_estimator, model_registry, probe_gpu_environment


SAFE_COLUMN = re.compile(r"^[A-Za-z_][A-Za-z0-9_]*$")
SUCCESS_TYPE = "dev.marketlab.contracts.worker.FitPredictResponse.Success"
FAILURE_TYPE = "dev.marketlab.contracts.worker.FitPredictResponse.Failure"


def run_worker(manifest_path: Path, output: Path) -> None:
    output = output.absolute()
    output.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(manifest_path.read_text())
    request = _request(manifest)
    try:
        response = execute(manifest, request, output)
        _publish_result(output, response, manifest)
    except Exception as error:
        # Remove only artifacts owned by this invocation before publishing a
        # failure. A size-limit failure must never leave a durable Success.
        for owned_name in ("predictions.parquet", "model.pickle"):
            owned = output / owned_name
            if owned.is_file():
                owned.unlink()
        response = {
            "type": FAILURE_TYPE,
            "protocolVersion": 1,
            "requestId": request.get("requestId", "unknown-request"),
            "code": "WORKER_EXECUTION_FAILED",
            "message": str(error) or type(error).__name__,
            "retryable": False,
        }
        if not (output / "result.json").exists():
            _publish_result(output, response, manifest)
        raise


def execute(
    manifest: Mapping[str, Any],
    request: Mapping[str, Any],
    output: Path,
    *,
    input_root: Path = Path("/work/input"),
) -> dict[str, Any]:
    if int(manifest.get("protocolVersion", 0)) != 1 or int(request.get("protocolVersion", 0)) != 1:
        raise ValueError("unsupported worker protocol version")
    if request.get("runtime") != "PYTHON":
        raise ValueError("Python worker refuses a non-PYTHON task")
    deadline = int(request["deadline"]["epochMillis"] if isinstance(request["deadline"], dict) else request["deadline"])
    if int(datetime.now(timezone.utc).timestamp() * 1000) >= deadline:
        raise ValueError("worker invocation deadline has expired")
    if request.get("randomSeed") != manifest.get("seed"):
        raise ValueError("manifest and task seeds differ")
    if manifest.get("capabilityId") != "python-torch-gpu-v1":
        raise ValueError("GPU alpha worker received the wrong capability")
    constraints = manifest["outputConstraints"]
    if Path(constraints["outputDirectory"]).absolute() != output:
        raise ValueError("CLI output directory differs from the authorized manifest")
    required_media = {"application/json", "application/octet-stream", "application/vnd.apache.parquet"}
    if not required_media.issubset(set(constraints["allowedMediaTypes"])):
        raise ValueError("output contract does not authorize JSON and Parquet")

    training = request["training"]
    train_ref, test_ref = training["features"], request["testFeatures"]
    if train_ref["featureColumns"] != test_ref["featureColumns"]:
        raise ValueError("training and test feature schemas differ")
    immutable = {_digest_value(value) for value in manifest["immutableInputHashes"]}
    train_path = _authorized_dataset(train_ref, immutable, input_root=input_root)
    test_path = _authorized_dataset(test_ref, immutable, input_root=input_root)
    features = [_column(value) for value in train_ref["featureColumns"]]
    label = _column(training["labelColumn"])
    row_id = _column(test_ref["rowIdColumn"])

    import duckdb
    import numpy as np

    connection = duckdb.connect(":memory:")
    try:
        train_rows = connection.execute(
            f"SELECT {', '.join(_quote(value) for value in features)}, {_quote(label)} FROM read_parquet(?) ORDER BY {_quote(train_ref['rowIdColumn'])}",
            [str(train_path)],
        ).fetchall()
        test_rows = connection.execute(
            f"SELECT {_quote(row_id)}, {', '.join(_quote(value) for value in features)} FROM read_parquet(?) ORDER BY {_quote(row_id)}",
            [str(test_path)],
        ).fetchall()
        if not train_rows or not test_rows:
            raise ValueError("worker datasets must not be empty")
        x_train = np.asarray([row[:-1] for row in train_rows], dtype=np.float64)
        y_train = np.asarray([row[-1] for row in train_rows], dtype=np.float64)
        ids = [str(row[0]) for row in test_rows]
        x_test = np.asarray([row[1:] for row in test_rows], dtype=np.float64)
        if not np.isfinite(x_train).all() or not np.isfinite(y_train).all() or not np.isfinite(x_test).all():
            raise ValueError("worker input contains non-finite values")
        estimator_id = str(request["estimator"])
        spec = model_registry().get(estimator_id)
        if spec is None:
            raise ValueError(f"estimator is not registered: {estimator_id}")
        parameters = {key: _parameter(value) for key, value in request.get("parameters", {}).items()}
        if spec.input_kind is InputKind.TEMPORAL:
            timesteps = int(parameters.pop("temporalTimesteps", 0))
            channels = int(parameters.pop("temporalChannels", 0))
            if timesteps < 2 or channels < 1 or timesteps * channels != len(features):
                raise ValueError("temporal estimator requires explicit timesteps*channels matching feature columns")
            channel_names = parameters.pop("temporalChannelNames", None)
            if not isinstance(channel_names, list) or len(channel_names) != channels:
                raise ValueError("temporal estimator requires an ordered temporalChannelNames array")
            _validate_temporal_coordinates(features, timesteps, channels, channel_names)
            x_train = x_train.reshape(len(x_train), timesteps, channels)
            x_test = x_test.reshape(len(x_test), timesteps, channels)
        estimator = build_estimator(
            estimator_id,
            seed=int(request["randomSeed"]),
            input_channels=int(x_train.shape[-1]),
            **parameters,
        )
        estimator.fit(x_train, y_train)
        predictions = np.asarray(estimator.predict(x_test), dtype=np.float64).reshape(-1)
        if len(predictions) != len(ids) or not np.isfinite(predictions).all():
            raise ValueError("estimator emitted invalid predictions")
        prediction_path = output / "predictions.parquet"
        connection.execute("CREATE TABLE predictions(row_id VARCHAR, prediction DOUBLE)")
        connection.executemany("INSERT INTO predictions VALUES (?, ?)", list(zip(ids, predictions.tolist())))
        escaped = str(prediction_path).replace("'", "''")
        connection.execute(f"COPY predictions TO '{escaped}' (FORMAT PARQUET, COMPRESSION ZSTD)")
    finally:
        connection.close()
    digest = sha256_file(prediction_path)
    model_path = output / "model.pickle"
    model_digest = write_once_bytes(model_path, pickle.dumps(estimator, protocol=5))
    gpu = probe_gpu_environment()
    return {
        "type": SUCCESS_TYPE,
        "protocolVersion": 1,
        "requestId": request["requestId"],
        "predictions": {
            "artifactId": f"predictions-{digest[:24]}",
            "uri": prediction_path.as_uri(),
            "contentHash": digest,
            "rowIdColumn": "row_id",
            "predictionColumn": "prediction",
        },
        "model": {
            "artifactId": f"model-{model_digest[:24]}",
            "uri": model_path.as_uri(),
            "contentHash": model_digest,
            "format": "PYTHON_PICKLE",
            "estimator": estimator_id,
        },
        "diagnostics": {
            "runtime": sys.version.split()[0],
            "estimator": estimator_id,
            "rows": str(len(predictions)),
            "cudaAvailable": str(gpu.available).lower(),
            "gpuName": gpu.name or "unavailable",
            "strictTemporalShape": str(spec.input_kind is InputKind.TEMPORAL).lower(),
        },
    }


def _request(manifest: Mapping[str, Any]) -> Mapping[str, Any]:
    task = manifest.get("task")
    if not isinstance(task, Mapping) or not isinstance(task.get("request"), Mapping):
        raise ValueError("worker manifest contains no FitPredict request")
    task_type = str(task.get("type", ""))
    if task_type and not task_type.endswith("WorkerTask.FitPredict"):
        raise ValueError("unsupported worker task")
    return task["request"]


def _authorized_dataset(
    reference: Mapping[str, Any], immutable: set[str], *, input_root: Path = Path("/work/input")
) -> Path:
    if reference.get("format") != "PARQUET":
        raise ValueError("Python worker accepts only PARQUET datasets")
    digest = _digest_value(reference["contentHash"])
    if digest not in immutable:
        raise ValueError("dataset hash is not authorized by the invocation manifest")
    uri = urlparse(str(reference["uri"]))
    if uri.scheme != "file" or uri.netloc not in ("", "localhost"):
        raise ValueError("dataset URI must be a local file URI")
    path = Path(unquote(uri.path)).resolve(strict=True)
    input_root = input_root.resolve(strict=True)
    if not path.is_relative_to(input_root) or not path.is_file():
        raise ValueError("dataset path escapes the immutable input mount")
    if sha256_file(path) != digest:
        raise ValueError("dataset bytes differ from the authorized hash")
    return path


def _publish_result(output: Path, response: Mapping[str, Any], manifest: Mapping[str, Any]) -> None:
    result = output / "result.json"
    payload = canonical_json_bytes(response)
    maximum = int(manifest["outputConstraints"]["maximumBytes"])
    existing_size = sum(path.stat().st_size for path in output.rglob("*") if path.is_file())
    if existing_size + len(payload) > maximum:
        raise ValueError("worker output exceeded the authorized byte limit")
    descriptor = os.open(result, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o444)
    try:
        os.write(descriptor, payload)
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _parameter(value: str) -> Any:
    try:
        return json.loads(value)
    except (TypeError, json.JSONDecodeError):
        return value


def _validate_temporal_coordinates(
    features: list[str], timesteps: int, channels: int, channel_names: list[Any]
) -> None:
    if timesteps < 2 or channels < 1 or len(channel_names) != channels:
        raise ValueError("invalid temporal coordinate dimensions")
    normalized = [_column(str(value)) for value in channel_names]
    expected = [
        f"t{timestep:03d}__{channel}"
        for timestep in range(timesteps)
        for channel in normalized
    ]
    if features != expected:
        raise ValueError("temporal feature columns must encode ordered timestep/channel coordinates")


def _digest_value(value: Any) -> str:
    return str(value["hex"] if isinstance(value, Mapping) else value)


def _column(value: str) -> str:
    if not SAFE_COLUMN.fullmatch(str(value)):
        raise ValueError(f"unsafe dataset column: {value}")
    return str(value)


def _quote(value: str) -> str:
    return '"' + _column(value) + '"'
