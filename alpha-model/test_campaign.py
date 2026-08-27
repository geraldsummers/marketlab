from __future__ import annotations

import json
import importlib.util
import math
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from marketlab_alpha.artifact_commands import freeze_candidate, register_archives, verify_frozen_inputs
from marketlab_alpha.artifacts import (
    iter_jsonl,
    read_json,
    sha256_file,
    write_once_json,
    write_once_records,
)
from marketlab_alpha.contracts import FAMILY_SCHEMA, RESULT_SCHEMA, canonical_sha256
from marketlab_alpha.panel import materialize_panel, temporal_windows
from marketlab_alpha.search import (
    _AdaptiveTrialRunner,
    _adaptive_trial_workers,
    _cgroup_working_set_bytes,
    _clustered_hac_p_value,
    _breadth_cells,
    _confirmation_marker_root,
    _checkpointed_trial,
    _rows_with_targets_inside_period,
    _stage_a_cells,
    chronological_folds,
    finalize_confirmation_family,
    holm_adjust,
    select_development_winners,
)
from marketlab_alpha.search import run_development_search


class ArtifactTest(unittest.TestCase):
    @unittest.skipUnless(importlib.util.find_spec("duckdb"), "Parquet round-trip requires pinned DuckDB")
    def test_nested_records_round_trip_through_immutable_parquet(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "panel.parquet"
            rows = [{
                "rowId": "BTC:1", "decisionTimeEpochMillis": 1, "symbol": "BTC",
                "features": {"latest_return": 0.1, "realized_variance": 0.01},
                "targets": {"5m": 0.2, "1h": None},
            }]
            digest = write_once_records(path, rows)
            self.assertEqual(sha256_file(path), digest)
            self.assertEqual(rows, list(iter_jsonl(path)))
            with self.assertRaises(FileExistsError):
                write_once_records(path, rows)

    def test_archive_registration_and_freeze_are_write_once(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            archive = root / "BTC.zip"
            archive.write_bytes(b"production bytes")
            archive_manifest = root / "archives.json"
            registered = register_archives([archive], archive_manifest)
            self.assertEqual(sha256_file(archive), registered["objects"][0]["sha256"])
            with self.assertRaises(FileExistsError):
                register_archives([archive], archive_manifest)

            campaign = Path(__file__).parents[1] / "research/alpha/campaigns/archive-directional-gpu-v1.lock.json"
            model = root / "model.pickle"
            model.write_bytes(b"trusted local model")
            dimensions = {
                "assets": ["dynamic-basket"],
                "basketSizes": [6],
                "factorRepresentations": ["btc"],
                "horizons": ["1h"],
                "targets": ["outright-return"],
                "informationSets": ["trade-flow"],
                "modelFamilies": ["ridge"],
                "variants": ["unrestricted-sign"],
            }
            manifest = {
                "schemaVersion": "marketlab.alpha-candidate-search-manifest.v1",
                "campaignId": "archive-directional-gpu-v1",
                "candidateId": "flow-v1",
                "mechanism": "trade-flow",
                "stage": "EXPLORATORY",
                "createdAt": "2026-08-05T00:00:00Z",
                "userConstraints": ["BTC and ETH are mandatory"],
                "designConventions": ["Liquidity is an operational screen"],
                "empiricalClaims": ["Flow may or may not predict returns"],
                "searchDimensions": dimensions,
                "maximumTrials": 1,
                "gpuIdentity": {"available": False, "unavailableReason": "test", "determinismNotes": []},
                "openedOutcomePeriods": [],
                "limitations": {"survivorship": "test", "sourceTransfer": "test"},
                "artifacts": [],
            }
            selection = {key: value[0] for key, value in dimensions.items()}
            family = {
                "schemaVersion": FAMILY_SCHEMA,
                "campaignId": "archive-directional-gpu-v1",
                "createdAt": "2026-08-05T00:00:00Z",
                "campaignLockFileSha256": sha256_file(campaign),
                "developmentPanelSha256s": ["2" * 64, "3" * 64, "4" * 64],
                "confirmationLedgerRoot": str((root / "confirmation-ledger").resolve()),
                "multiplicity": "Holm over the complete frozen confirmation family",
                "selectedCandidates": [{
                    "candidateId": "flow-v1",
                    "trialId": "trial-1",
                    "searchManifestSha256": canonical_sha256(manifest),
                }],
            }
            search = root / "search.json"
            write_once_json(search, {"confirmationFamily": family, "selected": [{
                "candidateId": "flow-v1",
                "trialId": "trial-1",
                "selection": selection,
                "featureSchemaSha256": "0" * 64,
                "searchManifest": manifest,
                "modelArtifactPath": str(model),
                "modelArtifactSha256": sha256_file(model),
                "panelSha256": "2" * 64,
            }]})
            confirmation_panel = root / "confirmation.jsonl"
            confirmation_panel.write_text("{}\n")
            confirmation_panel.with_suffix(".jsonl.manifest.json").write_text(json.dumps({
                "schemaVersion": "marketlab.directional-panel-manifest.v1",
                "panelSha256": sha256_file(confirmation_panel),
                "outcomePeriod": {
                    "startInclusive": "2025-06-01T00:00:00Z",
                    "endExclusive": "2026-08-01T00:00:00Z",
                },
                "basketSize": 6,
            }))
            frozen_path = root / "frozen.json"
            frozen = freeze_candidate(search, campaign, "flow-v1", confirmation_panel, frozen_path)
            self.assertTrue(frozen["confirmationMarker"]["mustNotExistBeforeOpen"])
            self.assertEqual((root / "confirmation-ledger").resolve(), _confirmation_marker_root(frozen))
            self.assertEqual(sha256_file(confirmation_panel), frozen["confirmationPanelSha256"])
            self.assertEqual("flow-v1", verify_frozen_inputs(frozen_path)["candidateId"])
            confirmation_panel.write_text('{"tampered":true}\n')
            with self.assertRaisesRegex(ValueError, "confirmation panel"):
                verify_frozen_inputs(frozen_path)
            confirmation_panel.write_text("{}\n")
            search.write_text("{}")
            with self.assertRaises(ValueError):
                verify_frozen_inputs(frozen_path)


class PanelTest(unittest.TestCase):
    def test_labels_are_future_only_and_temporal_axis_is_time(self):
        minute = 60_000
        bars = []
        for offset in range(40):
            for symbol, scale in (("BTC", 1.0), ("ETH", 2.0)):
                close = scale * (100.0 + offset)
                bars.append({
                    "symbol": symbol,
                    "eventTimeEpochMillis": offset * 5 * minute,
                    "availableTimeEpochMillis": (offset + 1) * 5 * minute,
                    "open": close - 0.5,
                    "high": close + 0.5,
                    "low": close - 1.0,
                    "close": close,
                    "quoteVolume": 1_000 + offset,
                    "tradeCount": 10,
                    "takerBuyQuoteVolume": 600,
                })
        rows = materialize_panel(
            bars,
            memberships={0: ("BTC", "ETH")},
            horizons=("5m", "15m", "1h"),
            base_interval_ms=5 * minute,
            lag_count=8,
        )
        first = rows[0]
        self.assertGreater(first["decisionTimeEpochMillis"], first["sourceEventTimeEpochMillis"])
        self.assertGreater(first["targets"]["5m"], 0.0)
        tensors, indices = temporal_windows(rows, ("latest_return", "realized_variance"), 3)
        self.assertEqual(3, len(tensors[0]))
        self.assertEqual(2, len(tensors[0][0]))
        self.assertEqual(len(tensors), len(indices))

    def test_gap_resets_lags_instead_of_creating_a_spurious_long_return(self):
        minute = 60_000
        bars = [
            {"symbol": "BTC", "eventTimeEpochMillis": step * 5 * minute,
             "availableTimeEpochMillis": (step + 1) * 5 * minute, "close": 100 + step,
             "quoteVolume": 1000}
            for step in (0, 1, 4, 5, 6)
        ]
        rows = materialize_panel(
            bars, memberships={0: ("BTC",)}, horizons=("5m",),
            base_interval_ms=5 * minute, lag_count=4,
        )
        self.assertEqual([], [row["decisionTimeEpochMillis"] for row in rows])


class SearchPolicyTest(unittest.TestCase):
    def test_disposable_step_checkpoints_once_and_replays_without_bundle_hashing(self):
        from unittest.mock import patch
        import marketlab_alpha.search as search_module

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary)
            manifest = {
                "campaignId": "step-v1", "candidateId": "candidate-v1",
                "searchDimensions": {"basketSizes": [4]},
            }
            arguments = {
                "output_directory": output, "manifest": manifest, "rows": [],
                "features": ["flow"], "horizon": "1h", "target": "outright-return",
                "factor": "none", "model_id": "ridge", "configuration": {"alpha": 1.0},
                "seed": 7, "outer_folds": 2, "rung": "BREADTH",
                "trial_id": "candidate-v1-ridge-0", "mechanism": "trade-flow",
            }
            metrics = {
                "meanOuterImprovement": 0.1, "positiveOuterFolds": 2, "fitCount": 4,
                "outerFoldImprovements": [0.1, 0.1], "trainingMetadata": {},
            }
            prior_active = search_module._SEARCH_STEP_ACTIVE
            prior_remaining = search_module._SEARCH_STEP_REMAINING
            try:
                search_module._SEARCH_STEP_ACTIVE = True
                search_module._SEARCH_STEP_REMAINING = 1
                with patch.object(search_module, "validate_trial_ledger_entry"), patch.object(
                    search_module, "_evaluate_with_market_baseline", return_value=(metrics, {"model": "test"})
                ):
                    trial, _ = search_module._checkpointed_trial(**arguments)
                    self.assertEqual("COMPLETED", trial["status"])
                    with patch.object(search_module, "sha256_file", side_effect=AssertionError("replay hashed bundle")):
                        replay, bundle = search_module._checkpointed_trial(**arguments)
                    self.assertEqual(trial["trialId"], replay["trialId"])
                    self.assertIsNone(bundle)
                    blocked = dict(arguments, trial_id="candidate-v1-ridge-1")
                    with self.assertRaises(search_module._SearchStepBoundary):
                        search_module._checkpointed_trial(**blocked)
            finally:
                search_module._SEARCH_STEP_ACTIVE = prior_active
                search_module._SEARCH_STEP_REMAINING = prior_remaining

    def test_trial_plans_are_deterministic_and_include_promotion_sources(self):
        import marketlab_alpha.search as search_module

        with tempfile.TemporaryDirectory() as temporary:
            task = {
                "output_directory": Path(temporary),
                "manifest": {"campaignId": "plan-v1", "candidateId": "candidate-v1"},
                "trial_id": "trial-v1", "configuration": {"alpha": 1.0}, "seed": 7,
                "horizon": "1h", "target": "outright-return", "factor": "none",
                "row_set": "full", "rung": "FULL_DEVELOPMENT",
                "promotion_source_hashes": ["a" * 64],
            }
            first = search_module._write_trial_plan("ridge", [task])
            second = search_module._write_trial_plan("ridge", [task])
            self.assertEqual(first, second)
            plan = read_json(first)
            self.assertEqual(["a" * 64], plan["orderedTasks"][0]["promotionSourceTrialSha256s"])

    def test_adaptive_workers_are_family_and_memory_bounded(self):
        gib = 1024**3
        self.assertEqual(
            16 * gib,
            _cgroup_working_set_bytes(39 * gib, f"anon {16 * gib}\ninactive_file {23 * gib}\n"),
        )
        roomy = (8 * gib, 49 * gib)
        pressured = (32 * gib, 49 * gib)
        self.assertEqual(2, _adaptive_trial_workers(
            "elastic_net", 3, 2, memory_usage=roomy,
        ))
        self.assertEqual(1, _adaptive_trial_workers(
            "elastic_net", 3, 2, memory_usage=pressured,
        ))
        self.assertEqual(2, _adaptive_trial_workers(
            "extra_trees", 3, 2, memory_usage=roomy,
        ))
        self.assertEqual(1, _adaptive_trial_workers(
            "extra_trees", 3, 2, memory_usage=pressured,
        ))
        self.assertEqual(1, _adaptive_trial_workers(
            "gpu_xgboost", 3, 2, memory_usage=roomy,
        ))

    def test_stage_a_breadth_covers_every_horizon_per_model_family(self):
        campaign = read_json(
            Path(__file__).parents[1] / "research/alpha/campaigns/archive-directional-gpu-v1.lock.json"
        )
        cells = _stage_a_cells(campaign, model_index=0, count=16)
        self.assertEqual(set(campaign["searchDimensions"]["horizons"]), {cell[0] for cell in cells})
        self.assertEqual(8, sum(cell[1] == "outright-return" for cell in cells))
        self.assertEqual(8, sum(cell[1] == "factor-residual-return" for cell in cells))

    def test_v2_breadth_rung_covers_every_horizon_target_and_factor_family(self):
        campaign = read_json(
            Path(__file__).parents[1] / "research/alpha/campaigns/archive-directional-gpu-v2.lock.json"
        )
        cells = _breadth_cells(campaign, model_index=0)
        self.assertEqual(set(campaign["searchDimensions"]["horizons"]), {cell[0] for cell in cells})
        self.assertEqual({"outright-return", "factor-residual-return"}, {cell[1] for cell in cells})
        self.assertEqual(set(campaign["searchDimensions"]["factorRepresentations"]), {cell[2] for cell in cells})

    def test_purge_selection_and_holm(self):
        times = [hour * 3_600_000 for hour in range(40)]
        folds = chronological_folds(times, horizon_ms=3_600_000, outer_folds=4, minimum_train_rows=5)
        self.assertTrue(all(max(times[index] for index in fold.train) < fold.test_start_inclusive - 3_600_000 for fold in folds))
        winners = select_development_winners([
            {"trialId": "a", "candidateId": "a", "mechanism": "flow", "outerFoldImprovements": [1.0, 1.0, -0.1]},
            {"trialId": "b", "candidateId": "b", "mechanism": "flow", "outerFoldImprovements": [2.0, 2.0, 2.0]},
            {"trialId": "c", "candidateId": "c", "mechanism": "social", "outerFoldImprovements": [0.2, 0.1, -0.1]},
        ])
        self.assertEqual(["b", "c"], [value["trialId"] for value in winners])
        self.assertEqual([0.03, 0.04, 0.04], holm_adjust([0.01, 0.04, 0.02]))

    def test_complete_confirmation_family_is_corrected_together(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            selected = [
                {"candidateId": f"candidate-{index}", "trialId": f"trial-{index}", "searchManifestSha256": str(index + 1) * 64}
                for index in range(2)
            ]
            family_manifest = {
                "schemaVersion": FAMILY_SCHEMA,
                "campaignId": "campaign-v1",
                "createdAt": "2026-08-05T00:00:00Z",
                "campaignLockFileSha256": "a" * 64,
                "developmentPanelSha256s": ["b" * 64],
                "confirmationLedgerRoot": str((root / "confirmation-ledger").resolve()),
                "multiplicity": "Holm over the complete frozen confirmation family",
                "selectedCandidates": selected,
            }
            family_hash = canonical_sha256(family_manifest)
            family_path = root / "merged-search.json"
            family_path.write_text(json.dumps({"confirmationFamily": family_manifest}))
            paths = []
            for index, (raw, improvement) in enumerate(((0.01, 0.1), (0.04, 0.2))):
                path = root / f"result-{index}.json"
                path.write_text(json.dumps({
                    "schemaVersion": RESULT_SCHEMA,
                    "campaignId": "campaign-v1", "candidateId": f"candidate-{index}",
                    "stage": "INCONCLUSIVE",
                    "selectedTrialId": f"trial-{index}",
                    "confirmationFamilySha256": family_hash,
                    "inference": {"rawPValue": raw, "status": "PENDING_CONFIRMATION_FAMILY_HOLM"},
                    "primaryMetric": {"improvement": improvement},
                    "decision": "PRIMARY_LOSS_IMPROVED",
                }))
                paths.append(path)
            hashes = [sha256_file(path) for path in paths]
            family = finalize_confirmation_family(paths, hashes, family_path, root / "family.json")
            self.assertEqual([0.02, 0.04], [item["holmAdjustedPValue"] for item in family["candidateResults"]])
            self.assertTrue(all(item["stage"] == "BLIND_VALIDATED" for item in family["candidateResults"]))
            with self.assertRaisesRegex(ValueError, "exactly match"):
                finalize_confirmation_family(
                    paths[:1], hashes[:1], family_path, root / "incomplete-family.json"
                )
            paths[0].write_text("{}")
            with self.assertRaisesRegex(ValueError, "externally registered"):
                finalize_confirmation_family(
                    paths, hashes, family_path, root / "tampered-family.json"
                )

    def test_period_boundaries_and_invalid_hac_are_conservative(self):
        hour = 3_600_000
        rows = [
            {"decisionTimeEpochMillis": hour, "targets": {"1h": 0.1}},
            {"decisionTimeEpochMillis": 2 * hour, "targets": {"1h": 0.2}},
            {"decisionTimeEpochMillis": 3 * hour, "targets": {"1h": 0.3}},
        ]
        bounded = _rows_with_targets_inside_period(rows, hour, 3 * hour, "1h")
        self.assertEqual([hour, 2 * hour], [row["decisionTimeEpochMillis"] for row in bounded])
        self.assertIsNone(_clustered_hac_p_value([hour, 2 * hour], [0.1, 0.1], hour))
        self.assertIsNone(_clustered_hac_p_value([hour, 2 * hour, 3 * hour], [0.1, 0.1, 0.1], hour))


@unittest.skipUnless(
    importlib.util.find_spec("numpy") and importlib.util.find_spec("sklearn"),
    "development search requires the pinned model-worker dependencies",
)
class DevelopmentSearchTest(unittest.TestCase):
    def test_adaptive_pool_matches_serial_trial_metrics_and_model_hashes(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            start = 1_577_836_800_000
            rows = []
            for step in range(120):
                flow = math.sin(step * 0.31) * 0.02
                for symbol_index, symbol in enumerate(("BTC", "ETH")):
                    value = math.sin(step / 7.0) * 0.01 + symbol_index * 0.0001
                    rows.append({
                        "rowId": f"{symbol}:{start + step * 3_600_000}",
                        "decisionTimeEpochMillis": start + step * 3_600_000,
                        "symbol": symbol,
                        "features": {
                            "latest_return": value,
                            "mean_return": value * 0.5,
                            "realized_variance": value * value + 1e-8,
                            "persistence_return_1h": value,
                            "flow_signal": flow,
                        },
                        "targets": {"1h": flow * 0.8},
                    })
            dimensions = {
                "assets": ["dynamic-basket"], "basketSizes": [2],
                "factorRepresentations": ["none"], "horizons": ["1h"],
                "targets": ["outright-return"], "informationSets": ["trade-flow"],
                "modelFamilies": ["ridge"], "variants": ["unrestricted-sign"],
            }
            manifest = {
                "schemaVersion": "marketlab.alpha-candidate-search-manifest.v1",
                "campaignId": "adaptive-equivalence-v1", "candidateId": "flow-v1",
                "mechanism": "trade-flow", "stage": "EXPLORATORY",
                "createdAt": "2026-08-15T00:00:00Z",
                "userConstraints": ["test"], "designConventions": ["test"],
                "empiricalClaims": ["test"], "searchDimensions": dimensions,
                "maximumTrials": 2, "trialTimeoutSeconds": 60,
                "gpuIdentity": {"available": False, "unavailableReason": "test", "determinismNotes": []},
                "openedOutcomePeriods": [],
                "limitations": {"survivorship": "test", "sourceTransfer": "test"},
                "artifacts": [],
            }

            def tasks(output: Path) -> list[dict[str, object]]:
                return [{
                    "row_set": "full", "output_directory": output,
                    "manifest": manifest, "features": ["flow_signal"],
                    "horizon": "1h", "target": "outright-return", "factor": "none",
                    "model_id": "ridge", "configuration": {"alpha": 1.0},
                    "seed": seed, "outer_folds": 2, "rung": "ROBUSTNESS",
                    "trial_id": f"flow-v1-2-ridge-robust-0-seed-{seed}",
                    "mechanism": "trade-flow", "load_bundle": True,
                } for seed in (7, 11)]

            adaptive = _AdaptiveTrialRunner(
                rows, rows, worker_ceiling=2, memory_usage=(1 * 1024**3, 49 * 1024**3),
            )
            adaptive_results = list(adaptive.run("ridge", tasks(root / "adaptive")))
            self.assertEqual(2, adaptive.last_worker_count)
            adaptive.close()
            serial = _AdaptiveTrialRunner(rows, rows, worker_ceiling=1)
            serial_results = list(serial.run("ridge", tasks(root / "serial")))
            serial.close()

            for (serial_trial, serial_bundle), (adaptive_trial, adaptive_bundle) in zip(
                serial_results, adaptive_results
            ):
                self.assertEqual("COMPLETED", serial_trial["status"])
                self.assertEqual(serial_trial["status"], adaptive_trial["status"])
                self.assertEqual(serial_trial["metrics"], adaptive_trial["metrics"])
                self.assertEqual(
                    serial_trial["outerFoldImprovements"],
                    adaptive_trial["outerFoldImprovements"],
                )
                for serial_model_bundle, adaptive_model_bundle in (
                    (serial_bundle, adaptive_bundle),
                    (serial_bundle["marketBaseline"], adaptive_bundle["marketBaseline"]),
                ):
                    serial_metadata = {
                        key: value for key, value in serial_model_bundle.items()
                        if key not in {"estimator", "marketBaseline"}
                    }
                    adaptive_metadata = {
                        key: value for key, value in adaptive_model_bundle.items()
                        if key not in {"estimator", "marketBaseline"}
                    }
                    self.assertEqual(serial_metadata, adaptive_metadata)
                    serial_estimator = serial_model_bundle["estimator"]
                    adaptive_estimator = adaptive_model_bundle["estimator"]
                    feature_count = int(serial_estimator.n_features_in_)
                    probe = [[0.0] * feature_count, [0.25] * feature_count]
                    self.assertEqual(
                        serial_estimator.predict(probe).tolist(),
                        adaptive_estimator.predict(probe).tolist(),
                    )

    def test_nested_search_emits_ledger_and_frozen_model_candidate(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            panel = root / "panel.jsonl"
            start = 1_577_836_800_000  # 2020-01-01 UTC
            rows = []
            symbols = ("BTC", "ETH", "SOL", "XRP")
            for step in range(180):
                latest = math.sin(step / 5.0) * 0.01
                flow = math.sin(step * 0.71) * 0.02
                for symbol_index, symbol in enumerate(symbols):
                    value = latest + symbol_index * 0.0001
                    rows.append({
                        "rowId": f"{symbol}:{start + step * 3_600_000}",
                        "decisionTimeEpochMillis": start + step * 3_600_000,
                        "symbol": symbol,
                        "features": {
                            "latest_return": value,
                            "mean_return": value * 0.5,
                            "realized_variance": value * value + 1e-8,
                            "log_quote_volume": 10.0,
                            "persistence_return_1h": value,
                            "flow_signal": flow,
                        },
                        "targets": {"1h": flow * 0.8},
                    })
            panel.write_text("".join(json.dumps(row) + "\n" for row in rows))
            (root / "panel.jsonl.manifest.json").write_text(json.dumps({
                "schemaVersion": "marketlab.directional-panel-manifest.v1",
                "panelSha256": sha256_file(panel),
                "basketSize": 4,
                "outcomePeriod": {
                    "startInclusive": "2020-01-01T00:00:00Z",
                    "endExclusive": "2020-02-01T00:00:00Z",
                },
            }))
            lock = root / "lock.json"
            lock.write_text(json.dumps({
                "schemaVersion": "marketlab.alpha-campaign-lock.v1",
                "campaignId": "test-search-v1",
                "stage": "EXPLORATORY",
                "createdAt": "2026-08-05T00:00:00Z",
                "userConstraints": ["BTC and ETH mandatory"],
                "designConventions": ["Test fixture"],
                "empiricalClaims": ["No model presumed superior"],
                "mechanisms": ["trade-flow"],
                "searchDimensions": {
                    "horizons": ["1h"], "basketSizes": [4],
                    "factorRepresentations": ["none"], "modelFamilies": ["ridge"],
                    "targets": ["outright-return"],
                },
                "searchBudget": {
                    "stageATrialsPerFamily": 1, "stageBAdditionalTrialsPerSurvivor": 1,
                    "stageBMaxFamiliesPerMechanism": 1, "maxFrozenPerMechanism": 1,
                    "maxFrozenOverall": 1, "stageASeeds": [7], "stageBSeeds": [7],
                },
                "validation": {
                    "method": "nested chronological", "primaryLoss": "mse",
                    "baselines": ["zero-return", "historical-mean", "persistence", "reversal"],
                    "purgeByHorizon": True, "multiplicity": "Holm",
                },
                "developmentPeriod": {
                    "startInclusive": "2020-01-01T00:00:00Z",
                    "endExclusive": "2020-02-01T00:00:00Z", "purpose": "test development",
                },
                "confirmation": {
                    "singleUse": True, "minimumCalendarDays": 1,
                    "minimumNonOverlappingTargets": 1,
                    "startInclusive": "2020-02-01T00:00:00Z",
                    "endExclusive": "2020-02-02T00:00:00Z", "purpose": "test confirmation",
                },
                "artifacts": [],
            }))
            result = run_development_search(
                panel,
                lock,
                root / "search",
                root / "confirmation-ledger",
                test_mode=True,
            )
            self.assertEqual(1, len(result["trialLedger"]))
            self.assertEqual("COMPLETED", result["trialLedger"][0]["status"])
            self.assertEqual(1, result["trialAccounting"]["ledgerEntries"])
            self.assertEqual(8, result["trialLedger"][0]["metrics"]["fitCount"])
            self.assertIn("configuration", result["trialLedger"][0])
            self.assertEqual(1, len(result["selected"]))
            self.assertTrue(Path(result["selected"][0]["modelArtifactPath"]).is_file())


if __name__ == "__main__":
    unittest.main()
