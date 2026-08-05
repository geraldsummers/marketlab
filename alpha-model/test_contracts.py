import copy
import hashlib
import json
import tempfile
import unittest
from pathlib import Path

from marketlab_alpha import contracts


ZERO_HASH = "0" * 64
ONE_HASH = "1" * 64


def campaign():
    return {
        "schemaVersion": contracts.CAMPAIGN_SCHEMA,
        "campaignId": "archive-first-alpha-v1",
        "stage": "EXPLORATORY",
        "createdAt": "2026-08-05T00:00:00Z",
        "userConstraints": ["BTC and ETH are mandatory basket members"],
        "designConventions": ["Liquidity ranking bounds the operational universe"],
        "empiricalClaims": ["No model or direction is presumed superior"],
        "mechanisms": ["transmission", "trade-flow"],
        "searchDimensions": {
            "horizons": ["5m", "1h"],
            "basketSizes": [4, 6],
            "factorRepresentations": ["none", "btc"],
            "modelFamilies": ["ridge", "xgboost-gpu"],
            "targets": ["outright-return", "factor-residual-return"],
        },
        "searchBudget": {
            "stageATrialsPerFamily": 16,
            "stageBAdditionalTrialsPerSurvivor": 32,
            "stageBMaxFamiliesPerMechanism": 2,
            "maxFrozenPerMechanism": 1,
            "maxFrozenOverall": 2,
            "stageASeeds": [7],
            "stageBSeeds": [7, 11, 17],
        },
        "validation": {
            "method": "nested chronological expanding windows",
            "primaryLoss": "mean squared return loss",
            "baselines": ["zero-return", "historical-mean"],
            "purgeByHorizon": True,
            "multiplicity": "Holm over the frozen confirmation family",
        },
        "developmentPeriod": {
            "startInclusive": "2020-01-01T00:00:00Z",
            "endExclusive": "2025-01-01T00:00:00Z",
            "purpose": "nested development",
        },
        "confirmation": {
            "singleUse": True,
            "minimumCalendarDays": 60,
            "minimumNonOverlappingTargets": 200,
            "startInclusive": "2025-01-01T00:00:00Z",
            "endExclusive": "2026-01-01T00:00:00Z",
            "purpose": "sealed confirmation",
        },
        "artifacts": [],
    }


def gpu_identity():
    return {
        "available": True,
        "deviceName": "NVIDIA GeForce RTX 3060",
        "deviceUuid": "GPU-test",
        "driverVersion": "580.65",
        "runtimeVersion": "12.8",
        "totalMemoryBytes": 12 * 1024**3,
        "imageDigest": "sha256:" + "2" * 64,
        "determinismNotes": ["Some reduction kernels may be nondeterministic"],
    }


def search_manifest():
    return {
        "schemaVersion": contracts.SEARCH_SCHEMA,
        "campaignId": "archive-first-alpha-v1",
        "candidateId": "transmission-v1",
        "mechanism": "transmission",
        "stage": "EXPLORATORY",
        "createdAt": "2026-08-05T01:00:00Z",
        "userConstraints": ["BTC and ETH are mandatory basket members"],
        "designConventions": ["The grid is a bounded coverage choice"],
        "empiricalClaims": ["BTC leadership is tested, not assumed"],
        "searchDimensions": {
            "assets": ["BTC", "ETH"],
            "basketSizes": [4, 6],
            "factorRepresentations": ["none", "btc"],
            "horizons": ["5m", "1h"],
            "targets": ["outright-return", "factor-residual-return"],
            "informationSets": ["market-only", "btc-transmission"],
            "modelFamilies": ["ridge", "xgboost-gpu"],
            "variants": ["unrestricted-sign", "placebo-time-shift"],
        },
        "maximumTrials": 32,
        "gpuIdentity": gpu_identity(),
        "openedOutcomePeriods": [],
        "limitations": {
            "survivorship": "Point-in-time membership is required",
            "sourceTransfer": "Binance evidence does not establish Hyperliquid transfer",
        },
        "artifacts": [],
    }


def trial():
    return {
        "schemaVersion": contracts.TRIAL_SCHEMA,
        "campaignId": "archive-first-alpha-v1",
        "candidateId": "transmission-v1",
        "trialId": "trial-0001",
        "startedAt": "2026-08-05T02:00:00Z",
        "completedAt": "2026-08-05T02:03:00Z",
        "status": "COMPLETED",
        "seed": 7,
        "selection": {
            "assets": "BTC",
            "basketSizes": 4,
            "factorRepresentations": "btc",
            "horizons": "1h",
            "targets": "outright-return",
            "informationSets": "btc-transmission",
            "modelFamilies": "ridge",
            "variants": "unrestricted-sign",
        },
        "metrics": {"outerMse": 0.0001},
        "artifactHashes": [],
    }


def confirmation_family():
    return {
        "schemaVersion": contracts.FAMILY_SCHEMA,
        "campaignId": "archive-first-alpha-v1",
        "createdAt": "2026-08-05T02:30:00Z",
        "campaignLockFileSha256": "3" * 64,
        "developmentPanelSha256s": ["4" * 64],
        "confirmationLedgerRoot": "/tmp/marketlab-test-confirmation-ledger",
        "multiplicity": "Holm over the frozen confirmation family",
        "selectedCandidates": [{
            "candidateId": "transmission-v1",
            "trialId": "trial-0001",
            "searchManifestSha256": contracts.canonical_sha256(search_manifest()),
        }],
    }


def frozen_lock():
    family = confirmation_family()
    return {
        "schemaVersion": contracts.FROZEN_SCHEMA,
        "campaignId": "archive-first-alpha-v1",
        "candidateId": "transmission-v1",
        "stage": "FROZEN_CANDIDATE",
        "frozenAt": "2026-08-05T03:00:00Z",
        "searchManifestSha256": contracts.canonical_sha256(search_manifest()),
        "selectedTrialId": "trial-0001",
        "selectedSpecification": dict(trial()["selection"]),
        "featureSchemaSha256": ONE_HASH,
        "availabilityRules": ["Every value must be known by the decision timestamp"],
        "developmentPeriod": copy.deepcopy(campaign()["developmentPeriod"]),
        "confirmationPeriod": {
            "startInclusive": "2025-01-01T00:00:00Z",
            "endExclusive": "2026-01-01T00:00:00Z",
            "purpose": "sealed confirmation",
        },
        "purgeEmbargo": {"purge": "target horizon", "embargo": "one target horizon"},
        "baselines": ["zero-return", "historical-mean"],
        "primaryLoss": "mean squared return loss",
        "inference": {"dependence": "HAC", "multiplicity": "Holm"},
        "costs": {"role": "diagnostic only", "doubleCostStress": True},
        "acceptanceThresholds": {"beatsStrongestBaseline": True, "minimumPositiveFolds": 4},
        "confirmationMarker": {
            "confirmationId": "transmission-v1-confirmation-1",
            "relativePath": "confirmation/transmission-v1-confirmation-1.json",
            "mustNotExistBeforeOpen": True,
        },
        "confirmationPanelPath": "/immutable/confirmation.jsonl",
        "confirmationPanelSha256": "5" * 64,
        "confirmationFamily": family,
        "confirmationFamilySha256": contracts.canonical_sha256(family),
        "openedOutcomePeriods": [],
        "limitations": {
            "survivorship": "Audited from archived listings",
            "sourceTransfer": "No claim beyond Binance",
        },
        "artifacts": [],
    }


class ContractsTest(unittest.TestCase):
    def test_campaign_freezes_breadth_budget_and_boundaries(self):
        validated = contracts.validate_campaign_lock(campaign())
        self.assertEqual([4, 6], validated["searchDimensions"]["basketSizes"])

        bad = campaign()
        del bad["searchDimensions"]["horizons"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "missing"):
            contracts.validate_campaign_lock(bad)

        bad = campaign()
        bad["confirmation"]["startInclusive"] = "2024-12-31T00:00:00Z"
        with self.assertRaisesRegex(contracts.ContractValidationError, "overlap"):
            contracts.validate_campaign_lock(bad)

    def test_manifest_partitions_assumptions_and_bounds_every_dimension(self):
        contracts.validate_candidate_search_manifest(search_manifest(), campaign())

        bad = search_manifest()
        del bad["empiricalClaims"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "empiricalClaims"):
            contracts.validate_candidate_search_manifest(bad)

        bad = search_manifest()
        del bad["searchDimensions"]["factorRepresentations"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "incomplete"):
            contracts.validate_candidate_search_manifest(bad)

        bad = search_manifest()
        bad["searchDimensions"]["horizons"] = ["unsupported"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "campaign lock"):
            contracts.validate_candidate_search_manifest(bad, campaign())

    def test_trial_selection_cannot_escape_declared_search(self):
        contracts.validate_trial_ledger_entry(trial(), search_manifest())
        bad = trial()
        bad["selection"]["modelFamilies"] = "causal-transformer"
        with self.assertRaisesRegex(contracts.ContractValidationError, "outside"):
            contracts.validate_trial_ledger_entry(bad, search_manifest())

        failed = trial()
        failed["status"] = "FAILED"
        failed["metrics"] = {}
        contracts.validate_trial_ledger_entry(failed, search_manifest())

    def test_frozen_lock_is_complete_and_has_unopened_confirmation(self):
        contracts.validate_frozen_candidate_lock(frozen_lock(), campaign())

        bound = frozen_lock()
        bound["searchManifestSha256"] = contracts.canonical_sha256(search_manifest())
        contracts.validate_frozen_candidate_lock(bound, campaign(), search_manifest())
        tampered = search_manifest()
        tampered["empiricalClaims"] = ["A different claim"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "canonical document"):
            contracts.validate_frozen_candidate_lock(bound, campaign(), tampered)

        bad = frozen_lock()
        bad["openedOutcomePeriods"] = [{
            "startInclusive": "2025-01-01T00:00:00Z",
            "endExclusive": "2026-01-01T00:00:00Z",
            "purpose": "sealed confirmation",
            "openedAt": "2026-08-05T04:00:00Z",
        }]
        with self.assertRaisesRegex(contracts.ContractValidationError, "newly frozen"):
            contracts.validate_frozen_candidate_lock(bad)

    def test_confirmation_preflight_is_read_only_and_marker_is_bound_to_lock(self):
        lock = frozen_lock()
        digest = contracts.canonical_sha256(lock)
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            expected = root / lock["confirmationMarker"]["relativePath"]
            self.assertEqual(expected, contracts.validate_confirmation_preflight(lock, root))
            self.assertFalse(expected.exists())

            marker = contracts.confirmation_marker_payload(lock, digest, "2026-08-05T04:00:00Z")
            contracts.validate_confirmation_marker(marker, lock, digest)
            expected.parent.mkdir()
            expected.write_text(json.dumps(marker))
            with self.assertRaisesRegex(contracts.ContractValidationError, "already"):
                contracts.validate_confirmation_preflight(lock, root)

    def test_confirmation_result_records_the_single_opened_period(self):
        lock = frozen_lock()
        digest = contracts.canonical_sha256(lock)
        marker = contracts.confirmation_marker_payload(lock, digest, "2026-08-05T04:00:00Z")
        result = {
            "schemaVersion": contracts.RESULT_SCHEMA,
            "campaignId": lock["campaignId"],
            "candidateId": lock["candidateId"],
            "stage": "INCONCLUSIVE",
            "completedAt": "2026-08-05T05:00:00Z",
            "frozenLockSha256": digest,
            "confirmationId": marker["confirmationId"],
            "selectedTrialId": lock["selectedTrialId"],
            "confirmationPanelSha256": lock["confirmationPanelSha256"],
            "confirmationFamilySha256": lock["confirmationFamilySha256"],
            "openedOutcomePeriods": [{
                **copy.deepcopy(lock["confirmationPeriod"]),
                "openedAt": marker["openedAt"],
            }],
            "decision": "Primary improvement was not distinguishable from zero",
            "primaryMetric": {"name": "mse", "value": 0.2, "baseline": 0.19, "improvement": -0.01},
            "baselineMetrics": {"zero-return": 0.19},
            "inference": {"adjustedPValue": 0.8},
            "limitations": copy.deepcopy(lock["limitations"]),
            "artifacts": [],
        }
        contracts.validate_confirmation_result(result, lock, digest, marker)

        bad = copy.deepcopy(result)
        bad["stage"] = "EXPLORATORY"
        with self.assertRaisesRegex(contracts.ContractValidationError, "confirmation outcome"):
            contracts.validate_confirmation_result(bad, lock, digest, marker)

    def test_artifact_hashes_verify_bytes_and_reject_path_escape(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            content = b"immutable evidence\n"
            (root / "result.json").write_bytes(content)
            artifact = [{
                "path": "result.json",
                "sha256": hashlib.sha256(content).hexdigest(),
                "sizeBytes": len(content),
            }]
            contracts.verify_artifact_hashes(artifact, root)
            artifact[0]["sha256"] = ZERO_HASH
            with self.assertRaisesRegex(contracts.ContractValidationError, "does not match"):
                contracts.verify_artifact_hashes(artifact, root)

            escaped = [{"path": "../result.json", "sha256": ZERO_HASH, "sizeBytes": 0}]
            with self.assertRaisesRegex(contracts.ContractValidationError, "normalized relative"):
                contracts.verify_artifact_hashes(escaped, root)

    def test_non_finite_json_and_incomplete_gpu_identity_are_rejected(self):
        bad = trial()
        bad["metrics"]["outerMse"] = float("nan")
        with self.assertRaisesRegex(contracts.ContractValidationError, "finite"):
            contracts.validate_trial_ledger_entry(bad)

        bad_gpu = gpu_identity()
        del bad_gpu["imageDigest"]
        with self.assertRaisesRegex(contracts.ContractValidationError, "imageDigest"):
            contracts.validate_gpu_identity(bad_gpu)


if __name__ == "__main__":
    unittest.main()
