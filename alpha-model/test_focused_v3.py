import json
import pickle
import tempfile
import unittest
from pathlib import Path

from marketlab_alpha.artifacts import sha256_file, write_once_json
from marketlab_alpha.contracts import canonical_sha256
from marketlab_alpha.focused_v3 import (
    _bundle_schema,
    _hac_p_value,
    _load_universe_snapshots,
    _validate_lock,
    confirm_focused_v3,
    freeze_focused_v3,
)


FEATURES = [
    "basket_latest_return",
    "cross_section_return_rank",
    "latest_return",
    "log_quote_volume",
    "log_range",
    "log_trade_count",
    "mean_return",
    "realized_variance",
    "volume_change",
]


class DummyEstimator:
    def predict(self, values):
        return [0.0] * len(values)


class FocusedV3Test(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self):
        self.temporary.cleanup()

    def lock(self):
        return {
            "schemaVersion": "marketlab.alpha-focused-v3-lock.v1",
            "campaignId": "archive-directional-focused-v3",
            "candidateId": "binance-market-state-direction-v3",
            "sourceCandidateId": "market-state-direction-v1",
            "stage": "FROZEN_CANDIDATE",
            "selection": {"basketSize": 4, "horizon": "15m", "target": "outright-return", "factorRepresentation": "none", "modelId": "extra_trees", "seed": 19870403, "configuration": {"max_depth": 4, "min_samples_leaf": 5, "n_estimators": 400}},
            "sourceSelection": {"assets": "dynamic-basket", "basketSizes": 4, "factorRepresentations": "none", "horizons": "15m", "informationSets": "market-state", "modelFamilies": "extra_trees", "targets": "outright-return", "variants": "unrestricted-sign"},
            "featureNames": FEATURES,
            "confirmationPeriod": {"startInclusive": "2025-06-01T00:00:00Z", "endExclusive": "2026-08-01T00:00:00Z"},
            "acceptance": {"minimumRelativeMseImprovement": 0.0025, "maximumAdjustedPValue": 0.05, "hacLagFiveMinuteOrigins": 288, "minimumPositiveCalendarMonths": 9, "mandatoryPositiveAssets": ["BTC", "ETH"]},
            "transferDiagnostic": {"candidateId": "hyperliquid-market-state-transfer-v1", "sourceUri": "https://api.hyperliquid.xyz/info", "period": {"startInclusive": "2026-08-01T00:00:00Z", "endExclusive": "2026-08-25T00:00:00Z"}},
            "limitations": ["test"],
        }

    def fixture(self):
        audit = self.root / "audit.json"; audit.write_text("{}\n")
        development = self.root / "development.jsonl"; development.write_text("{}\n")
        confirmation = self.root / "confirmation.jsonl"; confirmation.write_text("not-json\n")
        baseline = {"estimator": DummyEstimator(), "featureNames": FEATURES, "modelId": "ridge", "configuration": {"alpha": 1.0}, "lookback": 2, "horizon": "15m", "target": "outright-return", "factor": "none", "historicalMean": 0.0, "symbols": ["BTC", "ETH"], "unknownSymbolPolicy": "other-bucket", "targetState": {"target": "outright-return"}}
        bundle = {"estimator": DummyEstimator(), "featureNames": FEATURES, "modelId": "extra_trees", "configuration": {"max_depth": 4, "min_samples_leaf": 5, "n_estimators": 400}, "lookback": 2, "horizon": "15m", "target": "outright-return", "factor": "none", "historicalMean": 0.0, "symbols": ["BTC", "ETH"], "unknownSymbolPolicy": "other-bucket", "targetState": {"target": "outright-return"}, "marketBaseline": baseline}
        model = self.root / "model.pickle"; model.write_bytes(pickle.dumps(bundle))
        selected = {"candidateId": "market-state-direction-v1", "trialId": "trial", "selection": self.lock()["sourceSelection"], "configuration": self.lock()["selection"]["configuration"], "seed": 19870403, "modelArtifactSha256": sha256_file(model), "featureSchemaSha256": canonical_sha256(_bundle_schema(bundle)), "timeShiftPlacebo": {"status": "COMPLETED", "meanOuterImprovement": 0.01, "outerFoldImprovements": [0.1, -0.1, -0.1], "positiveOuterFolds": 1}}
        search = self.root / "search.json"; write_once_json(search, {"selected": [selected]})
        manifest = self.root / "confirmation.manifest.json"
        write_once_json(manifest, {"panelSha256": sha256_file(confirmation), "basketSize": 4, "outcomePeriod": self.lock()["confirmationPeriod"]})
        lock = self.lock(); lock["artifacts"] = {"v2AuditSha256": sha256_file(audit), "v2SearchResultSha256": sha256_file(search), "modelArtifactSha256": sha256_file(model), "developmentPanelSha256": sha256_file(development), "confirmationPanelSha256": sha256_file(confirmation), "confirmationPanelManifestSha256": sha256_file(manifest)}
        lock_path = self.root / "lock.json"; write_once_json(lock_path, lock)
        ledger = self.root / "ledger"; ledger.mkdir()
        return lock_path, audit, search, model, development, confirmation, manifest, ledger

    def test_lock_rejects_any_selection_expansion(self):
        lock = self.lock(); _validate_lock(lock)
        lock["selection"]["horizon"] = "1h"
        with self.assertRaisesRegex(ValueError, "audited specification"):
            _validate_lock(lock)

    def test_freeze_binds_verified_schema_and_confirmation_marker_precedes_read(self):
        inputs = self.fixture(); frozen_path = self.root / "frozen.json"
        result = freeze_focused_v3(*inputs, frozen_path)
        self.assertEqual(64, len(result["artifactSha256"]))
        with self.assertRaises(json.JSONDecodeError):
            confirm_focused_v3(frozen_path, result["artifactSha256"], self.root / "result.json")
        marker = inputs[-1] / "focused-v3" / "binance-market-state-direction-v3-confirmation-1.json"
        self.assertTrue(marker.is_file())
        with self.assertRaises(FileExistsError):
            confirm_focused_v3(frozen_path, result["artifactSha256"], self.root / "result.json")

    def test_hac_is_positive_for_persistent_positive_differentials(self):
        times = list(range(400))
        values = [0.2 + (index % 7) * 0.001 for index in times]
        value = _hac_p_value(times, values, 20)
        self.assertIsNotNone(value)
        self.assertLess(value, 0.05)

    def test_point_in_time_transfer_universe_requires_mandatory_assets(self):
        universe = self.root / "universe"; path = universe / "snapshots" / "week" / "snapshot.json"
        write_once_json(path, {"schemaVersion": "marketlab.hyperliquid-social-universe.v1", "effectiveFrom": 0, "effectiveToExclusive": 100, "members": [{"symbol": value} for value in ("BTC", "ETH", "SOL", "HYPE")]})
        memberships, records = _load_universe_snapshots(universe, 10, 90)
        self.assertEqual(["BTC", "ETH", "SOL", "HYPE"], memberships[0])
        self.assertEqual(1, len(records))
        value = json.loads(path.read_text()); value["members"] = [{"symbol": "BTC"}, {"symbol": "SOL"}, {"symbol": "HYPE"}, {"symbol": "XRP"}]
        path.unlink(); write_once_json(path, value)
        with self.assertRaisesRegex(ValueError, "omits BTC or ETH"):
            _load_universe_snapshots(universe, 10, 90)


if __name__ == "__main__":
    unittest.main()
