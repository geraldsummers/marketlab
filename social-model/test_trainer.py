import argparse
import importlib.util
import json
import math
import tempfile
import unittest
import sys
import numpy as np
from datetime import datetime, timedelta, timezone
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("marketlab_social_trainer", Path(__file__).with_name("trainer.py"))
trainer = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = trainer
SPEC.loader.exec_module(trainer)


class TrainerTest(unittest.TestCase):
    def test_all_model_families_fit_and_predict(self):
        x = np.asarray([[float(row), float(row % 3)] for row in range(24)])
        y = x[:, 0] * 0.01
        for family in ("elastic_net", "gradient_boosted_trees", "temporal_convolution", "gated_recurrent_unit"):
            model = trainer.estimator(family, trainer.config_for(family, 0), 11).fit(x, y)
            prediction = model.predict(x[:3])
            self.assertEqual((3,), prediction.shape)
            self.assertTrue(np.isfinite(prediction).all())

    def test_torch_regressor_accumulates_across_micro_batches(self):
        x = np.asarray([[float(row), float(row % 5)] for row in range(23)])
        y = x[:, 0] * 0.01
        original_batch_size = trainer.TorchRegressor.BATCH_SIZE
        trainer.TorchRegressor.BATCH_SIZE = 7
        try:
            for family in ("temporal_convolution", "gated_recurrent_unit"):
                model = trainer.TorchRegressor(family, width=8, learning_rate=1e-3, epochs=2, seed=17)
                prediction = model.fit(x, y).predict(x)
                self.assertEqual((23,), prediction.shape)
                self.assertTrue(np.isfinite(prediction).all())
        finally:
            trainer.TorchRegressor.BATCH_SIZE = original_batch_size

    def test_train_freeze_score_and_blind_evaluate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            training = root / "training.jsonl"
            blind = root / "blind.jsonl"
            self.write_rows(training, datetime(2025, 11, 3, tzinfo=timezone.utc), 240, labels=True)
            self.write_rows(blind, datetime(2026, 7, 1, tzinfo=timezone.utc), 31, labels=True)
            lock = root / "search-lock.json"
            lock.write_text(json.dumps({
                "maximumTrialsPerFamilyAndTarget": 1,
                "families": ["elastic_net"],
                "developmentPeriod": {"endExclusive": "2026-07-01T00:00:00Z"},
            }))
            feature_lock = root / "feature-lock.json"
            feature_lock.write_text("{}\n")
            feature_manifest = root / "feature-manifest.json"
            feature_manifest.write_text(json.dumps({
                "schemaVersion": "marketlab.social-functional-feature-manifest.v1",
                "outputSha256": trainer.sha256(training),
                "featureLockSha256": trainer.sha256(feature_lock),
            }))
            output = root / "models"
            trainer.train(argparse.Namespace(
                features=str(training), feature_manifest=str(feature_manifest), feature_lock=str(feature_lock),
                search_lock=str(lock), output=str(output), trials=1, seed=7, allow_test_trials=True,
            ))
            frozen = output / "frozen-models.json"
            manifest = json.loads(frozen.read_text())
            frozen_hash = trainer.sha256(frozen)
            self.assertEqual(4, len(manifest["models"]))
            self.assertEqual(4, len(json.loads((output / "trial-ledger.json").read_text())["trials"]))

            unlabeled = root / "shadow.jsonl"
            self.write_rows(unlabeled, datetime(2026, 8, 1, tzinfo=timezone.utc), 1, labels=False)
            forecasts = root / "forecasts.jsonl"
            trainer.score(argparse.Namespace(features=str(unlabeled), frozen=str(frozen), frozen_sha256=frozen_hash, output=str(forecasts)))
            with forecasts.open() as handle:
                self.assertEqual(40, sum(1 for _ in handle))

            report = root / "report.json"
            trainer.evaluate(argparse.Namespace(features=str(blind), frozen=str(frozen), frozen_sha256=frozen_hash, search_lock=str(lock), output=str(report)))
            result = json.loads(report.read_text())
            self.assertEqual(4, len(result["results"]))
            self.assertTrue(all(item["positiveAssets"] >= 6 for item in result["results"]))

    def write_rows(self, path, start, days, labels):
        symbols = ["BTC", "ETH", "HYPE", "LIT", "NEAR", "PUMP", "SOL", "WLD", "XRP", "ZEC"]
        with path.open("w") as handle:
            for day in range(days):
                decision = start + timedelta(days=day)
                for index, symbol in enumerate(symbols):
                    attention = math.sin(day / 9.0) + index / 10.0
                    features = {
                        "post_count_15m": attention,
                        "post_count_1h": attention * 2,
                        "post_count_6h": attention * 3,
                        "post_count_24h": attention * 4,
                        "has_posts_15m": 1.0,
                        "unique_authors_1h": 2.0,
                        "unique_authors_24h": 5.0,
                        "attention_burst_30d": attention,
                        "minutes_since_post": 1.0,
                        "latest_return": 0.0,
                        "btc_latest_return": 0.0,
                        "log_rv_1h": -10.0,
                        "log_rv_24h": -8.0,
                        "hour_sin": 0.0,
                        "hour_cos": 1.0,
                        "day_sin": 0.0,
                        "day_cos": 1.0,
                    }
                    for lag in range(1, 17): features[f"post_count_15m_lag_{lag}"] = attention
                    row = {
                        "rowId": f"{symbol}:{int(decision.timestamp() * 1000)}",
                        "decisionTimeEpochMillis": int(decision.timestamp() * 1000),
                        "symbol": symbol,
                        "features": features,
                    }
                    if labels:
                        row.update({
                            "next15mReturn": attention * 0.01,
                            "next1hRealizedVariance": math.exp(-10.0 + attention * 0.05),
                            "next1dReturn": attention * 0.02,
                            "next1dRealizedVariance": math.exp(-8.0 + attention * 0.05),
                        })
                    handle.write(json.dumps(row) + "\n")


if __name__ == "__main__":
    unittest.main()
