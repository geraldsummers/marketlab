"""Bounded model families for the archive-first alpha campaign.

Model names describe estimators, not hypotheses or evidence.  In particular,
the sequence estimators in this module accept only real temporal tensors with
shape ``[sample, timestep, channel]``.  They never reinterpret a flat feature
vector as a sequence.

Heavy dependencies are imported lazily so campaign metadata, validation, and
CPU-only tooling remain usable outside the pinned model worker.
"""

from __future__ import annotations

import importlib.util
import math
import random
from dataclasses import asdict, dataclass
from enum import Enum
from typing import Any, Mapping, Sequence


class InputKind(str, Enum):
    TABULAR = "tabular"
    TEMPORAL = "temporal"


class ModelUnavailableError(RuntimeError):
    """Raised when a selected estimator's optional runtime is unavailable."""


ModelDependencyError = ModelUnavailableError


@dataclass(frozen=True)
class ModelSpec:
    model_id: str
    family: str
    input_kind: InputKind
    dependencies: tuple[str, ...] = ()
    supports_multi_target: bool = False
    control: bool = False


@dataclass(frozen=True)
class GpuEnvironment:
    available: bool
    device_count: int
    device_index: int | None = None
    name: str | None = None
    total_memory_bytes: int | None = None
    compute_capability: str | None = None
    cuda_runtime: str | None = None
    cudnn_version: int | None = None
    deterministic_algorithms_enabled: bool | None = None
    probe_error: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


_MODEL_SPECS = (
    ModelSpec("zero_return", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("historical_mean", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("persistence", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("momentum", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("reversal", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("factor_only", "control", InputKind.TABULAR, ("numpy",), control=True, supports_multi_target=True),
    ModelSpec("ridge", "linear", InputKind.TABULAR, ("numpy", "sklearn"), supports_multi_target=True),
    ModelSpec("elastic_net", "linear", InputKind.TABULAR, ("numpy", "sklearn")),
    ModelSpec("shallow_tree", "tree", InputKind.TABULAR, ("numpy", "sklearn"), supports_multi_target=True),
    ModelSpec("random_forest", "tree", InputKind.TABULAR, ("numpy", "sklearn"), supports_multi_target=True),
    ModelSpec("extra_trees", "tree", InputKind.TABULAR, ("numpy", "sklearn"), supports_multi_target=True),
    ModelSpec("hist_gradient_boosting", "tree", InputKind.TABULAR, ("numpy", "sklearn")),
    ModelSpec("gpu_xgboost", "tree", InputKind.TABULAR, ("numpy", "xgboost"), supports_multi_target=True),
    ModelSpec("mlp", "neural_tabular", InputKind.TABULAR, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec("ft_transformer", "neural_tabular", InputKind.TABULAR, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec("multitask_mlp", "neural_multitask", InputKind.TABULAR, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec("tcn", "neural_temporal", InputKind.TEMPORAL, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec("gru", "neural_temporal", InputKind.TEMPORAL, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec("lstm", "neural_temporal", InputKind.TEMPORAL, ("numpy", "torch"), supports_multi_target=True),
    ModelSpec(
        "causal_transformer",
        "neural_temporal",
        InputKind.TEMPORAL,
        ("numpy", "torch"),
        supports_multi_target=True,
    ),
)


def model_registry() -> dict[str, ModelSpec]:
    """Return the complete, deterministic estimator registry."""

    return {spec.model_id: spec for spec in _MODEL_SPECS}


def dependency_availability() -> dict[str, bool]:
    return {
        name: importlib.util.find_spec(name) is not None
        for name in ("numpy", "sklearn", "torch", "xgboost")
    }


def model_availability() -> dict[str, dict[str, Any]]:
    dependencies = dependency_availability()
    result: dict[str, dict[str, Any]] = {}
    for spec in _MODEL_SPECS:
        missing = [name for name in spec.dependencies if not dependencies[name]]
        result[spec.model_id] = {"available": not missing, "missingDependencies": missing}
    return result


def _require(module_name: str) -> Any:
    if importlib.util.find_spec(module_name) is None:
        raise ModelUnavailableError(
            f"model requires optional dependency {module_name!r}; use the pinned model worker"
        )
    return __import__(module_name)


def validate_input_shape(model_id: str, shape: Sequence[int]) -> None:
    """Validate the public shape contract without importing an ML runtime."""

    try:
        spec = model_registry()[model_id]
    except KeyError as error:
        raise ValueError(f"unknown model_id {model_id!r}") from error
    expected = 3 if spec.input_kind is InputKind.TEMPORAL else 2
    if len(shape) != expected:
        semantic = "[sample, timestep, channel]" if expected == 3 else "[sample, feature]"
        raise ValueError(f"{model_id} requires {semantic}, received shape {tuple(shape)}")
    if any(int(dimension) < 1 for dimension in shape):
        raise ValueError(f"input dimensions must be positive, received shape {tuple(shape)}")


def set_deterministic_seed(seed: int, *, deterministic_algorithms: bool = True) -> None:
    """Seed every installed runtime used by this module."""

    random.seed(seed)
    if importlib.util.find_spec("numpy") is not None:
        numpy = __import__("numpy")
        numpy.random.seed(seed)
    if importlib.util.find_spec("torch") is not None:
        torch = __import__("torch")
        torch.manual_seed(seed)
        if torch.cuda.is_available():
            torch.cuda.manual_seed_all(seed)
        if deterministic_algorithms:
            torch.use_deterministic_algorithms(True, warn_only=True)
            if hasattr(torch.backends, "cudnn"):
                torch.backends.cudnn.benchmark = False
                torch.backends.cudnn.deterministic = True


def probe_gpu_environment(device_index: int = 0) -> GpuEnvironment:
    """Inspect CUDA without making GPU availability an import requirement."""

    if importlib.util.find_spec("torch") is None:
        return GpuEnvironment(False, 0, probe_error="torch is not installed")
    try:
        torch = __import__("torch")
        count = int(torch.cuda.device_count()) if torch.cuda.is_available() else 0
        if count == 0:
            return GpuEnvironment(
                False,
                0,
                cuda_runtime=getattr(torch.version, "cuda", None),
                deterministic_algorithms_enabled=torch.are_deterministic_algorithms_enabled(),
                probe_error="CUDA is not available",
            )
        if not 0 <= device_index < count:
            raise ValueError(f"device_index {device_index} is outside 0..{count - 1}")
        properties = torch.cuda.get_device_properties(device_index)
        capability = torch.cuda.get_device_capability(device_index)
        return GpuEnvironment(
            True,
            count,
            device_index=device_index,
            name=str(properties.name),
            total_memory_bytes=int(properties.total_memory),
            compute_capability=f"{capability[0]}.{capability[1]}",
            cuda_runtime=getattr(torch.version, "cuda", None),
            cudnn_version=torch.backends.cudnn.version(),
            deterministic_algorithms_enabled=torch.are_deterministic_algorithms_enabled(),
        )
    except ValueError:
        raise
    except Exception as error:  # hardware/driver probes differ across runners
        return GpuEnvironment(False, 0, probe_error=f"{type(error).__name__}: {error}")


def suggest_batch_size(
    sample_shape: Sequence[int],
    parameter_count: int,
    environment: GpuEnvironment | Mapping[str, Any] | None = None,
    *,
    dtype_bytes: int = 4,
    activation_multiplier: float = 12.0,
    safety_fraction: float = 0.55,
    maximum: int = 4096,
    cpu_default: int = 256,
) -> int:
    """Return a conservative power-of-two starting batch size.

    This is a scheduling heuristic, not an assertion that a batch is optimal.
    The worker must still record measured peak memory and any OOM retry.
    """

    if not sample_shape or any(int(value) < 1 for value in sample_shape):
        raise ValueError("sample_shape must contain positive dimensions")
    if parameter_count < 0 or dtype_bytes < 1 or activation_multiplier <= 0:
        raise ValueError("invalid memory-estimation argument")
    if not 0 < safety_fraction < 1 or maximum < 1 or cpu_default < 1:
        raise ValueError("invalid batch-size bound")
    if environment is None:
        environment = probe_gpu_environment()
    if isinstance(environment, GpuEnvironment):
        available = environment.available
        total_memory = environment.total_memory_bytes
    else:
        available = bool(environment.get("available"))
        total_memory = environment.get("total_memory_bytes", environment.get("totalMemoryBytes"))
    if not available or not total_memory:
        return min(maximum, cpu_default)

    sample_values = math.prod(int(value) for value in sample_shape)
    # Parameters, gradients, and Adam moments consume about four tensors.
    fixed_bytes = parameter_count * dtype_bytes * 4
    usable_bytes = max(0, int(total_memory * safety_fraction) - fixed_bytes)
    per_sample = max(1, int(sample_values * dtype_bytes * activation_multiplier))
    raw = max(1, min(maximum, usable_bytes // per_sample))
    return 1 << (int(raw).bit_length() - 1)


class ControlRegressor:
    """Explicit non-learned and factor-only forecast controls."""

    def __init__(self, mode: str, feature_indices: Sequence[int] = (0,)):
        if mode not in {"zero_return", "historical_mean", "persistence", "momentum", "reversal", "factor_only"}:
            raise ValueError(f"unknown control mode {mode!r}")
        self.mode = mode
        self.feature_indices = tuple(int(value) for value in feature_indices)
        self.mean_: Any = None
        self.coefficients_: Any = None

    def fit(self, x: Any, y: Any) -> "ControlRegressor":
        numpy = _require("numpy")
        values = numpy.asarray(x, dtype=numpy.float64)
        target = numpy.asarray(y, dtype=numpy.float64)
        validate_input_shape(self.mode, values.shape)
        if len(target) != len(values):
            raise ValueError("x and y sample counts differ")
        self.mean_ = numpy.mean(target, axis=0)
        if self.mode == "factor_only":
            design = values[:, self.feature_indices]
            design = numpy.column_stack((numpy.ones(len(design)), design))
            self.coefficients_ = numpy.linalg.lstsq(design, target, rcond=None)[0]
        return self

    def predict(self, x: Any) -> Any:
        numpy = _require("numpy")
        values = numpy.asarray(x, dtype=numpy.float64)
        validate_input_shape(self.mode, values.shape)
        if self.mean_ is None:
            raise ValueError("model is not fitted")
        target_shape = () if numpy.asarray(self.mean_).ndim == 0 else numpy.asarray(self.mean_).shape
        if self.mode == "zero_return":
            return numpy.zeros((len(values),) + target_shape, dtype=numpy.float64)
        if self.mode == "historical_mean":
            return numpy.broadcast_to(self.mean_, (len(values),) + target_shape).copy()
        if self.mode == "factor_only":
            design = numpy.column_stack((numpy.ones(len(values)), values[:, self.feature_indices]))
            return design @ self.coefficients_
        selected = values[:, self.feature_indices]
        if selected.shape[1] == 1:
            selected = selected[:, 0]
        return -selected if self.mode == "reversal" else selected


class _SklearnMultiOutput:
    """Fit independent copies when a sklearn estimator lacks multi-output."""

    def __init__(self, estimator_class: Any, parameters: Mapping[str, Any]):
        self.estimator_class = estimator_class
        self.parameters = dict(parameters)
        self.models: list[Any] = []
        self.single_target = True

    def _new_estimator(self) -> Any:
        return self.estimator_class(**self.parameters)

    def fit(self, x: Any, y: Any) -> "_SklearnMultiOutput":
        numpy = _require("numpy")
        target = numpy.asarray(y)
        self.single_target = target.ndim == 1
        matrix = target[:, None] if self.single_target else target
        self.models = [self._new_estimator().fit(x, matrix[:, index]) for index in range(matrix.shape[1])]
        return self

    def predict(self, x: Any) -> Any:
        numpy = _require("numpy")
        if not self.models:
            raise ValueError("model is not fitted")
        result = numpy.column_stack([model.predict(x) for model in self.models])
        return result[:, 0] if self.single_target else result


class TorchRegressor:
    """Scikit-like trainer around a tabular or genuinely temporal torch net."""

    def __init__(
        self,
        model_id: str,
        *,
        seed: int = 0,
        hidden_size: int = 32,
        layers: int = 1,
        dropout: float = 0.0,
        learning_rate: float = 1e-3,
        weight_decay: float = 1e-4,
        epochs: int = 10,
        batch_size: int | None = None,
        output_dim: int | None = None,
        input_channels: int | None = None,
        heads: int = 4,
        deterministic: bool = True,
        device: str | None = None,
        mixed_precision: bool = True,
        gradient_accumulation_steps: int = 1,
    ):
        spec = model_registry().get(model_id)
        if spec is None or "torch" not in spec.dependencies:
            raise ValueError(f"{model_id!r} is not a torch model")
        if (
            hidden_size < 1
            or layers < 1
            or epochs < 1
            or learning_rate <= 0
            or gradient_accumulation_steps < 1
        ):
            raise ValueError("invalid torch model configuration")
        if model_id == "multitask_mlp" and (output_dim is None or output_dim < 2):
            raise ValueError("multitask_mlp requires output_dim >= 2")
        self.model_id = model_id
        self.seed = seed
        self.hidden_size = hidden_size
        self.layers = layers
        self.dropout = dropout
        self.learning_rate = learning_rate
        self.weight_decay = weight_decay
        self.epochs = epochs
        self.batch_size = batch_size
        self.output_dim = output_dim
        self.input_channels = input_channels
        self.heads = heads
        self.deterministic = deterministic
        self.device_name = device
        self.mixed_precision = mixed_precision
        self.gradient_accumulation_steps = gradient_accumulation_steps
        self.model_: Any = None
        self.mean_: Any = None
        self.scale_: Any = None
        self.single_target_: bool = True
        self.training_batch_size_: int | None = None
        self.parameter_count_: int | None = None
        self.peak_gpu_memory_bytes_: int | None = None
        self.used_mixed_precision_: bool = False
        self.fitted_input_channels_: int | None = None
        self.fitted_output_dim_: int | None = None

    def fit(self, x: Any, y: Any) -> "TorchRegressor":
        numpy = _require("numpy")
        torch = _require("torch")
        values = numpy.asarray(x, dtype=numpy.float32)
        targets = numpy.asarray(y, dtype=numpy.float32)
        validate_input_shape(self.model_id, values.shape)
        if len(values) != len(targets):
            raise ValueError("x and y sample counts differ")
        self.single_target_ = targets.ndim == 1
        target_matrix = targets[:, None] if self.single_target_ else targets
        if target_matrix.ndim != 2:
            raise ValueError("y must have shape [sample] or [sample, target]")
        output_dim = int(target_matrix.shape[1])
        if self.output_dim is not None and self.output_dim != output_dim:
            raise ValueError(f"configured output_dim={self.output_dim}, observed {output_dim}")
        if self.model_id == "multitask_mlp" and output_dim < 2:
            raise ValueError("multitask_mlp requires two or more target columns")

        set_deterministic_seed(self.seed, deterministic_algorithms=self.deterministic)
        temporal = model_registry()[self.model_id].input_kind is InputKind.TEMPORAL
        axes = (0, 1) if temporal else (0,)
        self.mean_ = values.mean(axis=axes, keepdims=True)
        self.scale_ = values.std(axis=axes, keepdims=True)
        self.scale_ = numpy.where(self.scale_ == 0.0, 1.0, self.scale_)
        channels = int(values.shape[-1])
        if self.input_channels is not None and self.input_channels != channels:
            raise ValueError(
                f"configured input_channels={self.input_channels}, observed {channels}"
            )
        self.fitted_input_channels_ = channels
        self.fitted_output_dim_ = output_dim
        self.model_ = _make_torch_module(
            self.model_id,
            channels,
            output_dim,
            self.hidden_size,
            self.layers,
            self.dropout,
            self.heads,
        )
        if self.device_name is None:
            self.device_name = "cuda" if torch.cuda.is_available() else "cpu"
        device = torch.device(self.device_name)
        self.model_.to(device)
        parameter_count = sum(parameter.numel() for parameter in self.model_.parameters())
        self.parameter_count_ = int(parameter_count)
        if self.batch_size is None:
            environment = (
                probe_gpu_environment(device.index or 0)
                if device.type == "cuda"
                else GpuEnvironment(False, 0)
            )
            sample_shape = values.shape[1:]
            self.training_batch_size_ = suggest_batch_size(sample_shape, parameter_count, environment)
        else:
            self.training_batch_size_ = int(self.batch_size)
        if self.training_batch_size_ < 1:
            raise ValueError("batch_size must be positive")

        optimizer = torch.optim.AdamW(
            self.model_.parameters(), lr=self.learning_rate, weight_decay=self.weight_decay
        )
        generator = torch.Generator(device="cpu").manual_seed(self.seed)
        self.used_mixed_precision_ = bool(self.mixed_precision and device.type == "cuda")
        scaler = torch.amp.GradScaler("cuda", enabled=self.used_mixed_precision_)
        if device.type == "cuda":
            torch.cuda.reset_peak_memory_stats(device)
        for _ in range(self.epochs):
            permutation = torch.randperm(len(values), generator=generator).numpy()
            self.model_.train()
            optimizer.zero_grad(set_to_none=True)
            starts = list(range(0, len(values), self.training_batch_size_))
            for batch_index, start in enumerate(starts):
                indices = permutation[start : start + self.training_batch_size_]
                normalized_batch = (values[indices] - self.mean_) / self.scale_
                batch_x = torch.as_tensor(normalized_batch, device=device)
                batch_y = torch.as_tensor(target_matrix[indices], device=device)
                with torch.autocast(
                    device_type=device.type,
                    dtype=torch.float16,
                    enabled=self.used_mixed_precision_,
                ):
                    prediction = self.model_(batch_x)
                    loss = torch.nn.functional.mse_loss(prediction, batch_y)
                    loss = loss / self.gradient_accumulation_steps
                scaler.scale(loss).backward()
                update = (batch_index + 1) % self.gradient_accumulation_steps == 0
                if update or batch_index + 1 == len(starts):
                    scaler.step(optimizer)
                    scaler.update()
                    optimizer.zero_grad(set_to_none=True)
        if device.type == "cuda":
            self.peak_gpu_memory_bytes_ = int(torch.cuda.max_memory_allocated(device))
        self.model_.cpu().eval()
        if device.type == "cuda":
            torch.cuda.empty_cache()
        return self

    def predict(self, x: Any) -> Any:
        numpy = _require("numpy")
        torch = _require("torch")
        if self.model_ is None:
            raise ValueError("model is not fitted")
        values = numpy.asarray(x, dtype=numpy.float32)
        validate_input_shape(self.model_id, values.shape)
        results = []
        batch_size = self.training_batch_size_ or len(values) or 1
        with torch.no_grad():
            for start in range(0, len(values), batch_size):
                normalized_batch = (values[start : start + batch_size] - self.mean_) / self.scale_
                result = self.model_(torch.as_tensor(normalized_batch))
                results.append(result.numpy())
        matrix = numpy.concatenate(results, axis=0).astype(numpy.float64)
        return matrix[:, 0] if self.single_target_ else matrix

    def __getstate__(self) -> dict[str, Any]:
        """Persist architecture inputs and weights, never a local module class."""

        state = dict(self.__dict__)
        model = state.pop("model_", None)
        state["_serialized_model_state"] = None if model is None else model.state_dict()
        return state

    def __setstate__(self, state: dict[str, Any]) -> None:
        serialized = state.pop("_serialized_model_state", None)
        self.__dict__.update(state)
        self.model_ = None
        if serialized is None:
            return
        if self.fitted_input_channels_ is None or self.fitted_output_dim_ is None:
            raise ValueError("serialized torch estimator lacks its fitted architecture dimensions")
        self.model_ = _make_torch_module(
            self.model_id,
            self.fitted_input_channels_,
            self.fitted_output_dim_,
            self.hidden_size,
            self.layers,
            self.dropout,
            self.heads,
        )
        self.model_.load_state_dict(serialized)
        self.model_.cpu().eval()

    def training_metadata(self) -> dict[str, Any]:
        """Return measured runtime facts suitable for a trial ledger."""

        if self.model_ is None:
            raise ValueError("model is not fitted")
        return {
            "modelId": self.model_id,
            "inputKind": model_registry()[self.model_id].input_kind.value,
            "parameterCount": self.parameter_count_,
            "trainingBatchSize": self.training_batch_size_,
            "gradientAccumulationSteps": self.gradient_accumulation_steps,
            "mixedPrecision": self.used_mixed_precision_,
            "peakGpuMemoryBytes": self.peak_gpu_memory_bytes_,
            "deterministicAlgorithmsRequested": self.deterministic,
        }


def _make_torch_module(
    model_id: str,
    channels: int,
    output_dim: int,
    hidden_size: int,
    layers: int,
    dropout: float,
    heads: int,
) -> Any:
    torch = _require("torch")
    nn = torch.nn
    if model_id in {"mlp", "multitask_mlp"}:
        modules: list[Any] = [nn.Linear(channels, hidden_size), nn.GELU(), nn.Dropout(dropout)]
        for _ in range(layers - 1):
            modules.extend((nn.Linear(hidden_size, hidden_size), nn.GELU(), nn.Dropout(dropout)))
        modules.append(nn.Linear(hidden_size, output_dim))
        return nn.Sequential(*modules)
    if model_id == "ft_transformer":
        return _FTTransformer(torch, channels, hidden_size, output_dim, layers, dropout, heads)
    if model_id == "tcn":
        return _TemporalConvolution(torch, channels, hidden_size, output_dim, layers, dropout)
    if model_id in {"gru", "lstm"}:
        return _RecurrentHead(torch, model_id, channels, hidden_size, output_dim, layers, dropout)
    if model_id == "causal_transformer":
        return _CausalTransformer(torch, channels, hidden_size, output_dim, layers, dropout, heads)
    raise ValueError(f"unknown torch model {model_id!r}")


def _valid_heads(hidden_size: int, requested: int) -> int:
    candidates = [value for value in range(1, max(1, requested) + 1) if hidden_size % value == 0]
    return candidates[-1]


def sinusoidal_position_encoding(
    length: int,
    width: int,
    *,
    device: Any = None,
    dtype: Any = None,
) -> Any:
    """Return a deterministic `[timestep, channel]` sinusoidal encoding."""

    if length < 1 or width < 1:
        raise ValueError("position encoding requires positive length and width")
    torch = _require("torch")
    positions = torch.arange(length, device=device, dtype=torch.float32).unsqueeze(1)
    frequencies = torch.exp(
        torch.arange(0, width, 2, device=device, dtype=torch.float32)
        * (-math.log(10_000.0) / width)
    )
    angles = positions * frequencies.unsqueeze(0)
    encoding = torch.zeros(length, width, device=device, dtype=torch.float32)
    encoding[:, 0::2] = torch.sin(angles)
    odd_channels = encoding[:, 1::2].shape[1]
    encoding[:, 1::2] = torch.cos(angles[:, :odd_channels])
    return encoding.to(dtype=dtype or torch.float32)


class _TemporalConvolution:
    def __new__(cls, torch: Any, channels: int, width: int, outputs: int, layers: int, dropout: float) -> Any:
        nn = torch.nn

        class CausalBlock(nn.Module):
            def __init__(self, inputs: int, dilation: int):
                super().__init__()
                padding = 2 * dilation
                self.padding = padding
                self.conv = nn.Conv1d(inputs, width, kernel_size=3, padding=padding, dilation=dilation)
                self.activation = nn.GELU()
                self.dropout = nn.Dropout(dropout)

            def forward(self, values: Any) -> Any:
                encoded = self.conv(values)
                if self.padding:
                    encoded = encoded[:, :, :-self.padding]
                return self.dropout(self.activation(encoded))

        class Network(nn.Module):
            def __init__(self):
                super().__init__()
                blocks = []
                inputs = channels
                for index in range(layers):
                    blocks.append(CausalBlock(inputs, 2**index))
                    inputs = width
                self.blocks = nn.Sequential(*blocks)
                self.output = nn.Linear(width, outputs)

            def forward(self, values: Any) -> Any:
                encoded = self.blocks(values.transpose(1, 2))
                return self.output(encoded[:, :, -1])

        return Network()


class _RecurrentHead:
    def __new__(
        cls, torch: Any, family: str, channels: int, width: int, outputs: int, layers: int, dropout: float
    ) -> Any:
        nn = torch.nn
        recurrent_type = nn.GRU if family == "gru" else nn.LSTM

        class Network(nn.Module):
            def __init__(self):
                super().__init__()
                self.recurrent = recurrent_type(
                    channels,
                    width,
                    num_layers=layers,
                    batch_first=True,
                    dropout=dropout if layers > 1 else 0.0,
                )
                self.output = nn.Linear(width, outputs)

            def forward(self, values: Any) -> Any:
                encoded, _ = self.recurrent(values)
                return self.output(encoded[:, -1, :])

        return Network()


class _CausalTransformer:
    def __new__(
        cls, torch: Any, channels: int, width: int, outputs: int, layers: int, dropout: float, heads: int
    ) -> Any:
        nn = torch.nn
        head_count = _valid_heads(width, heads)

        class Network(nn.Module):
            def __init__(self):
                super().__init__()
                self.input = nn.Linear(channels, width)
                layer = nn.TransformerEncoderLayer(
                    width, head_count, dim_feedforward=width * 4, dropout=dropout, batch_first=True, activation="gelu"
                )
                self.encoder = nn.TransformerEncoder(layer, num_layers=layers)
                self.output = nn.Linear(width, outputs)

            def forward(self, values: Any) -> Any:
                encoded = self.input(values)
                length = encoded.shape[1]
                encoded = encoded + sinusoidal_position_encoding(
                    length,
                    encoded.shape[2],
                    device=encoded.device,
                    dtype=encoded.dtype,
                ).unsqueeze(0)
                causal_mask = torch.triu(
                    torch.ones(length, length, device=encoded.device, dtype=torch.bool), diagonal=1
                )
                encoded = self.encoder(encoded, mask=causal_mask)
                return self.output(encoded[:, -1, :])

        return Network()


class _FTTransformer:
    def __new__(
        cls, torch: Any, features: int, width: int, outputs: int, layers: int, dropout: float, heads: int
    ) -> Any:
        nn = torch.nn
        head_count = _valid_heads(width, heads)

        class Network(nn.Module):
            def __init__(self):
                super().__init__()
                self.feature_weight = nn.Parameter(torch.empty(features, width))
                self.feature_bias = nn.Parameter(torch.zeros(features, width))
                self.cls = nn.Parameter(torch.zeros(1, 1, width))
                nn.init.xavier_uniform_(self.feature_weight)
                layer = nn.TransformerEncoderLayer(
                    width, head_count, dim_feedforward=width * 4, dropout=dropout, batch_first=True, activation="gelu"
                )
                self.encoder = nn.TransformerEncoder(layer, num_layers=layers)
                self.output = nn.Linear(width, outputs)

            def forward(self, values: Any) -> Any:
                tokens = values.unsqueeze(-1) * self.feature_weight + self.feature_bias
                cls_token = self.cls.expand(len(values), -1, -1)
                encoded = self.encoder(torch.cat((cls_token, tokens), dim=1))
                return self.output(encoded[:, 0, :])

        return Network()


def build_estimator(
    spec: str | ModelSpec,
    seed: int = 0,
    input_channels: int | None = None,
    output_dim: int = 1,
    **configuration: Any,
) -> Any:
    """Build one registered estimator without selecting it or implying merit."""

    model_id = spec.model_id if isinstance(spec, ModelSpec) else spec
    if model_id not in model_registry():
        raise ValueError(f"unknown model_id {model_id!r}")
    resolved_spec = model_registry()[model_id]
    for dependency in resolved_spec.dependencies:
        _require(dependency)
    if resolved_spec.control:
        return ControlRegressor(model_id, configuration.pop("feature_indices", (0,)))
    if model_id == "elastic_net":
        _require("numpy")
        _require("sklearn")
        from sklearn.linear_model import ElasticNet
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler

        return make_pipeline(
            StandardScaler(), ElasticNet(random_state=seed, max_iter=20_000, **configuration)
        )
    if model_id == "ridge":
        _require("numpy")
        _require("sklearn")
        from sklearn.linear_model import Ridge
        from sklearn.pipeline import make_pipeline
        from sklearn.preprocessing import StandardScaler

        return make_pipeline(StandardScaler(), Ridge(**configuration))
    if model_id == "shallow_tree":
        _require("numpy")
        _require("sklearn")
        from sklearn.tree import DecisionTreeRegressor

        defaults = {"max_depth": 4, "min_samples_leaf": 20, "random_state": seed}
        defaults.update(configuration)
        return DecisionTreeRegressor(**defaults)
    if model_id == "random_forest":
        _require("numpy")
        _require("sklearn")
        from sklearn.ensemble import RandomForestRegressor

        return RandomForestRegressor(random_state=seed, n_jobs=1, **configuration)
    if model_id == "extra_trees":
        _require("numpy")
        _require("sklearn")
        from sklearn.ensemble import ExtraTreesRegressor

        return ExtraTreesRegressor(random_state=seed, n_jobs=1, **configuration)
    if model_id == "hist_gradient_boosting":
        _require("numpy")
        _require("sklearn")
        from sklearn.ensemble import HistGradientBoostingRegressor

        parameters = {"random_state": seed, **configuration}
        return _SklearnMultiOutput(HistGradientBoostingRegressor, parameters)
    if model_id == "gpu_xgboost":
        _require("numpy")
        _require("xgboost")
        from xgboost import XGBRegressor

        defaults = {"tree_method": "hist", "device": "cuda", "random_state": seed, "n_jobs": 1}
        defaults.update(configuration)
        return _SklearnMultiOutput(XGBRegressor, defaults)
    return TorchRegressor(
        model_id,
        seed=seed,
        input_channels=input_channels,
        output_dim=output_dim,
        **configuration,
    )


__all__ = [
    "ControlRegressor",
    "GpuEnvironment",
    "InputKind",
    "ModelDependencyError",
    "ModelUnavailableError",
    "ModelSpec",
    "TorchRegressor",
    "build_estimator",
    "dependency_availability",
    "model_availability",
    "model_registry",
    "probe_gpu_environment",
    "set_deterministic_seed",
    "sinusoidal_position_encoding",
    "suggest_batch_size",
    "validate_input_shape",
]
