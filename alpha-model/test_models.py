import importlib.util
import pickle
import unittest

from marketlab_alpha import models


class ModelRegistryTest(unittest.TestCase):
    def test_registry_contains_declared_controls_classical_and_neural_families(self):
        expected = {
            "zero_return", "historical_mean", "persistence", "momentum", "reversal", "factor_only",
            "ridge", "elastic_net", "shallow_tree", "random_forest", "extra_trees",
            "hist_gradient_boosting", "gpu_xgboost", "mlp",
            "ft_transformer", "multitask_mlp", "tcn", "gru", "lstm", "causal_transformer",
        }
        self.assertEqual(expected, set(models.model_registry()))

    def test_temporal_models_require_real_three_dimensional_time_axis(self):
        for model_id in ("tcn", "gru", "lstm", "causal_transformer"):
            models.validate_input_shape(model_id, (12, 48, 7))
            with self.assertRaisesRegex(ValueError, r"sample, timestep, channel"):
                models.validate_input_shape(model_id, (12, 48))
        for model_id in ("mlp", "ft_transformer", "elastic_net"):
            models.validate_input_shape(model_id, (12, 48))
            with self.assertRaisesRegex(ValueError, r"sample, feature"):
                models.validate_input_shape(model_id, (12, 48, 1))

    def test_optional_dependencies_are_reported_without_import_failure(self):
        availability = models.model_availability()
        for model_id, item in availability.items():
            self.assertIsInstance(item["available"], bool, model_id)
            self.assertIsInstance(item["missingDependencies"], list, model_id)

    def test_gpu_probe_is_serializable_even_without_torch_or_cuda(self):
        result = models.probe_gpu_environment().to_dict()
        self.assertIn("available", result)
        self.assertIn("device_count", result)
        self.assertIsInstance(result["available"], bool)

    def test_batch_suggestion_is_bounded_power_of_two(self):
        environment = models.GpuEnvironment(
            available=True, device_count=1, total_memory_bytes=6 * 1024**3
        )
        batch = models.suggest_batch_size((96, 24), 1_000_000, environment, maximum=2048)
        self.assertGreaterEqual(batch, 1)
        self.assertLessEqual(batch, 2048)
        self.assertEqual(0, batch & (batch - 1))
        self.assertEqual(
            128,
            models.suggest_batch_size((24,), 100, models.GpuEnvironment(False, 0), cpu_default=128),
        )

    def test_unknown_model_and_bad_dimensions_fail_closed(self):
        with self.assertRaisesRegex(ValueError, "unknown model_id"):
            models.validate_input_shape("imaginary", (1, 2))
        with self.assertRaisesRegex(ValueError, "positive"):
            models.validate_input_shape("mlp", (0, 2))

    def test_builder_accepts_registry_specs_and_has_a_specific_unavailable_error(self):
        spec = models.model_registry()["zero_return"]
        if importlib.util.find_spec("numpy"):
            self.assertIsInstance(models.build_estimator(spec, seed=3), models.ControlRegressor)
        missing = next(
            (
                model_id
                for model_id, item in models.model_availability().items()
                if not item["available"]
            ),
            None,
        )
        if missing is not None:
            with self.assertRaises(models.ModelUnavailableError):
                models.build_estimator(missing)


@unittest.skipUnless(importlib.util.find_spec("numpy"), "numpy is an optional model-worker dependency")
class ControlModelTest(unittest.TestCase):
    def setUp(self):
        import numpy as np

        self.np = np
        self.x = np.asarray([[1.0, 0.5], [2.0, 0.5], [3.0, 1.5], [4.0, 1.5]])
        self.y = np.asarray([0.1, 0.2, 0.3, 0.4])

    def test_controls_fit_and_predict(self):
        np = self.np
        expected = {
            "zero_return": np.zeros(2),
            "historical_mean": np.full(2, 0.25),
            "persistence": np.asarray([1.0, 2.0]),
            "momentum": np.asarray([1.0, 2.0]),
            "reversal": np.asarray([-1.0, -2.0]),
        }
        for model_id, wanted in expected.items():
            actual = models.build_estimator(model_id).fit(self.x, self.y).predict(self.x[:2])
            np.testing.assert_allclose(wanted, actual)

    def test_factor_only_is_fit_on_supplied_columns(self):
        prediction = models.build_estimator("factor_only", feature_indices=(0,)).fit(self.x, self.y).predict(self.x)
        self.np.testing.assert_allclose(self.y, prediction, atol=1e-12)


@unittest.skipUnless(
    importlib.util.find_spec("numpy") and importlib.util.find_spec("sklearn"),
    "sklearn is an optional model-worker dependency",
)
class SklearnModelTest(unittest.TestCase):
    def test_classical_estimators_fit_and_predict(self):
        import numpy as np

        x = np.asarray([[float(row), float(row % 3)] for row in range(30)])
        y = x[:, 0] * 0.01
        configurations = {
            "ridge": {"alpha": 1.0},
            "elastic_net": {"alpha": 1e-4, "l1_ratio": 0.1},
            "shallow_tree": {"max_depth": 3, "min_samples_leaf": 2},
            "random_forest": {"n_estimators": 8, "max_depth": 3},
            "extra_trees": {"n_estimators": 8, "max_depth": 3},
            "hist_gradient_boosting": {"max_iter": 8, "max_leaf_nodes": 7},
        }
        for model_id, configuration in configurations.items():
            prediction = models.build_estimator(model_id, seed=7, **configuration).fit(x, y).predict(x[:4])
            self.assertEqual((4,), prediction.shape, model_id)
            self.assertTrue(np.isfinite(prediction).all(), model_id)


@unittest.skipUnless(
    importlib.util.find_spec("numpy") and importlib.util.find_spec("torch"),
    "torch is an optional model-worker dependency",
)
class TorchModelTest(unittest.TestCase):
    def test_seed_enforces_strict_deterministic_algorithms(self):
        import torch

        models.set_deterministic_seed(37)
        self.assertTrue(torch.are_deterministic_algorithms_enabled())

    def test_mlp_depth_changes_the_registered_architecture(self):
        shallow = models._make_torch_module(
            "mlp", channels=4, output_dim=1, hidden_size=8, layers=1, dropout=0.0, heads=1
        )
        deep = models._make_torch_module(
            "mlp", channels=4, output_dim=1, hidden_size=8, layers=3, dropout=0.0, heads=1
        )
        self.assertGreater(
            sum(parameter.numel() for parameter in deep.parameters()),
            sum(parameter.numel() for parameter in shallow.parameters()),
        )

    def test_causal_transformer_has_deterministic_order_signal(self):
        import torch

        first = models.sinusoidal_position_encoding(5, 8)
        second = models.sinusoidal_position_encoding(5, 8)
        self.assertEqual((5, 8), tuple(first.shape))
        torch.testing.assert_close(first, second)
        self.assertFalse(torch.equal(first[0], first[1]))

        torch.manual_seed(41)
        network = models._make_torch_module(
            "causal_transformer", channels=2, output_dim=1,
            hidden_size=8, layers=1, dropout=0.0, heads=2,
        ).eval()
        sequence = torch.tensor([[[1.0, 0.0], [2.0, 1.0], [3.0, -1.0], [0.5, 0.5]]])
        reordered = sequence[:, [2, 0, 1, 3], :]
        with torch.no_grad():
            original_output = network(sequence)
            reordered_output = network(reordered)
        self.assertFalse(torch.allclose(original_output, reordered_output, atol=1e-8, rtol=1e-8))

    def test_tabular_models_fit_without_treating_features_as_time(self):
        import numpy as np

        x = np.random.default_rng(3).normal(size=(16, 5)).astype(np.float32)
        y = x[:, 0] * 0.2
        for model_id in ("mlp", "ft_transformer"):
            estimator = models.build_estimator(
                model_id, seed=4, hidden_size=8, epochs=1, batch_size=8, heads=2
            ).fit(x, y)
            self.assertEqual((3,), estimator.predict(x[:3]).shape)

    def test_temporal_models_fit_true_time_channel_tensors(self):
        import numpy as np

        x = np.random.default_rng(5).normal(size=(12, 6, 3)).astype(np.float32)
        y = x[:, -1, 0] * 0.1
        for model_id in ("tcn", "gru", "lstm", "causal_transformer"):
            estimator = models.build_estimator(
                model_id, seed=6, hidden_size=8, epochs=1, batch_size=6, heads=2
            ).fit(x, y)
            self.assertEqual((2,), estimator.predict(x[:2]).shape, model_id)
            with self.assertRaisesRegex(ValueError, r"sample, timestep, channel"):
                estimator.predict(x[:2, -1, :])

    def test_multitask_shape_is_preserved(self):
        import numpy as np

        x = np.random.default_rng(8).normal(size=(16, 4)).astype(np.float32)
        y = np.column_stack((x[:, 0], x[:, 1]))
        estimator = models.build_estimator(
            "multitask_mlp",
            seed=9,
            hidden_size=8,
            output_dim=2,
            epochs=1,
            batch_size=4,
            gradient_accumulation_steps=2,
        ).fit(x, y)
        self.assertEqual((3, 2), estimator.predict(x[:3]).shape)
        metadata = estimator.training_metadata()
        self.assertEqual("tabular", metadata["inputKind"])
        self.assertEqual(4, metadata["trainingBatchSize"])
        self.assertEqual(2, metadata["gradientAccumulationSteps"])
        self.assertGreater(metadata["parameterCount"], 0)


@unittest.skipUnless(importlib.util.find_spec("numpy"), "serialization tests require numpy")
class FrozenEstimatorSerializationTest(unittest.TestCase):
    def test_every_available_non_control_estimator_round_trips_after_fit(self):
        import numpy as np

        rng = np.random.default_rng(31)
        tabular = rng.normal(size=(24, 4)).astype(np.float32)
        temporal = rng.normal(size=(24, 6, 4)).astype(np.float32)
        target = tabular[:, 0] * 0.1 + tabular[:, 1] * -0.03
        multi_target = np.column_stack((target, tabular[:, 2] * 0.05))
        configurations = {
            "ridge": {"alpha": 1.0},
            "elastic_net": {"alpha": 1e-4, "l1_ratio": 0.1},
            "shallow_tree": {"max_depth": 2, "min_samples_leaf": 2},
            "random_forest": {"n_estimators": 4, "max_depth": 2, "min_samples_leaf": 2},
            "extra_trees": {"n_estimators": 4, "max_depth": 2, "min_samples_leaf": 2},
            "hist_gradient_boosting": {"max_iter": 4, "max_leaf_nodes": 7},
            "gpu_xgboost": {"n_estimators": 4, "max_depth": 2, "device": "cpu"},
            "mlp": {"hidden_size": 8, "epochs": 1, "batch_size": 8},
            "ft_transformer": {"hidden_size": 8, "epochs": 1, "batch_size": 8, "heads": 2},
            "multitask_mlp": {"hidden_size": 8, "epochs": 1, "batch_size": 8, "output_dim": 2},
            "tcn": {"hidden_size": 8, "epochs": 1, "batch_size": 8},
            "gru": {"hidden_size": 8, "epochs": 1, "batch_size": 8},
            "lstm": {"hidden_size": 8, "epochs": 1, "batch_size": 8},
            "causal_transformer": {"hidden_size": 8, "epochs": 1, "batch_size": 8, "heads": 2},
        }
        available = {
            model_id
            for model_id, spec in models.model_registry().items()
            if not spec.control and models.model_availability()[model_id]["available"]
        }
        self.assertEqual(available, available & set(configurations))
        for model_id in sorted(available):
            spec = models.model_registry()[model_id]
            x = temporal if spec.input_kind is models.InputKind.TEMPORAL else tabular
            y = multi_target if model_id == "multitask_mlp" else target
            estimator = models.build_estimator(
                model_id,
                seed=37,
                input_channels=x.shape[-1],
                **configurations[model_id],
            ).fit(x, y)
            before = np.asarray(estimator.predict(x[:3]))
            restored = pickle.loads(pickle.dumps(estimator, protocol=5))
            after = np.asarray(restored.predict(x[:3]))
            np.testing.assert_allclose(before, after, rtol=1e-7, atol=1e-9, err_msg=model_id)


if __name__ == "__main__":
    unittest.main()
