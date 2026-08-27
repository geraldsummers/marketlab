import json
import tempfile
import unittest
from pathlib import Path

from marketlab_alpha.artifacts import canonical_json_bytes, write_once_json
from marketlab_alpha.audit import audit_development


class DevelopmentAuditTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.lock = self.root / "lock.json"
        write_once_json(
            self.lock,
            {
                "schemaVersion": "marketlab.alpha-campaign-lock.v1",
                "campaignId": "campaign",
                "searchDimensions": {"basketSizes": [4, 6, 10]},
                "developmentPeriod": {"startInclusive": "2020-01-01T00:00:00Z", "endExclusive": "2021-01-01T00:00:00Z"},
            },
        )
        self.confirmation = self.root / "confirmation"
        self.confirmation.mkdir()

    def tearDown(self):
        self.temporary.cleanup()

    def trial(self, basket, seed, *, status="COMPLETED", candidate="candidate", folds=None):
        folds = [0.3, 0.2, 0.1, -0.1, 0.2] if folds is None else folds
        trial_id = f"{candidate}-{basket}-{seed}-{status.lower()}"
        record = {
            "schemaVersion": "marketlab.alpha-trial-ledger-entry.v1",
            "campaignId": "campaign",
            "trialId": trial_id,
            "candidateId": candidate,
            "mechanism": "mechanism",
            "status": status,
            "searchStage": "ROBUSTNESS",
            "seed": seed,
            "configurationIndex": 1,
            "configuration": {"depth": 2},
            "selection": {
                "basketSizes": basket,
                "modelFamilies": "extra_trees",
                "horizons": "15m",
                "targets": "outright-return",
                "factorRepresentations": "none",
                "informationSets": "mechanism",
                "variants": "unrestricted-sign",
            },
            "outerFoldImprovements": folds if status == "COMPLETED" else [],
            "outerFoldMarketOnlyImprovements": folds if status == "COMPLETED" else [],
            "outerFoldNaiveImprovements": folds if status == "COMPLETED" else [],
            "outerFoldCandidateLosses": [10.0] * len(folds) if status == "COMPLETED" else [],
        }
        path = self.root / f"search-{basket}" / "checkpoints" / "trials" / trial_id / "trial.json"
        write_once_json(path, record)
        return record

    def result(self, basket, record, placebo=None):
        candidate = dict(record)
        candidate.pop("configurationIndex", None)
        candidate["timeShiftPlacebo"] = {
            "status": "COMPLETED",
            "outerFoldImprovements": [0.1, -0.01, -0.01, -0.01, -0.01] if placebo is None else placebo,
        }
        write_once_json(
            self.root / f"search-{basket}" / "search-result.json",
            {"campaignId": "campaign", "basketSize": basket, "developmentCandidates": [candidate]},
        )

    def complete_family(self):
        for basket in (4, 6):
            records = [self.trial(basket, seed) for seed in (19870403, 230511, 910237)]
            self.result(basket, records[0])

    def test_focused_v3_requires_matching_complete_cross_basket_evidence(self):
        self.complete_family()
        result = audit_development(
            self.lock,
            {4: self.root / "search-4", 6: self.root / "search-6"},
            self.confirmation,
            self.root / "audit",
        )
        self.assertEqual("FOCUSED_V3_JUSTIFIED", result["recommendation"])
        audit = json.loads((self.root / "audit" / "audit.json").read_text())
        self.assertEqual([10], audit["untestedBaskets"])
        self.assertEqual(1, len(audit["focusedV3Eligibility"]["eligibleSpecifications"]))
        self.assertEqual(canonical_json_bytes(audit), (self.root / "audit" / "audit.json").read_bytes())

    def test_partial_ledgers_and_failures_are_accounted_as_insufficient(self):
        self.trial(4, 19870403)
        self.trial(4, 230511, status="FAILED")
        self.trial(6, 19870403)
        result = audit_development(
            self.lock,
            {4: self.root / "search-4", 6: self.root / "search-6"},
            self.confirmation,
            self.root / "audit",
        )
        self.assertEqual("INSUFFICIENT_EVIDENCE", result["recommendation"])
        audit = json.loads((self.root / "audit" / "audit.json").read_text())
        self.assertEqual(1, audit["searches"][0]["failedTrials"])

    def test_complete_family_with_failed_gate_is_rejected(self):
        for basket in (4, 6):
            records = [self.trial(basket, seed, folds=[-0.1] * 5) for seed in (19870403, 230511, 910237)]
            self.result(basket, records[0], placebo=[0.1] * 5)
        result = audit_development(
            self.lock,
            {4: self.root / "search-4", 6: self.root / "search-6"},
            self.confirmation,
            self.root / "audit",
        )
        self.assertEqual("REJECT", result["recommendation"])
        self.assertEqual("REJECTED", result["stage"])

    def test_refuses_open_confirmation_duplicate_trials_and_existing_output(self):
        self.complete_family()
        (self.confirmation / "opened.json").write_text("{}")
        with self.assertRaisesRegex(ValueError, "not sealed"):
            audit_development(self.lock, {4: self.root / "search-4", 6: self.root / "search-6"}, self.confirmation, self.root / "audit")
        (self.confirmation / "opened.json").unlink()
        duplicate = json.loads(next((self.root / "search-4").glob("checkpoints/trials/*/trial.json")).read_text())
        duplicate["selection"]["basketSizes"] = 6
        write_once_json(self.root / "search-6" / "checkpoints" / "trials" / "duplicate" / "trial.json", duplicate)
        with self.assertRaisesRegex(ValueError, "duplicate trial id"):
            audit_development(self.lock, {4: self.root / "search-4", 6: self.root / "search-6"}, self.confirmation, self.root / "audit")
        (self.root / "audit").mkdir()
        with self.assertRaises(FileExistsError):
            audit_development(self.lock, {4: self.root / "search-4", 6: self.root / "search-6"}, self.confirmation, self.root / "audit")

    def test_refuses_modified_immutable_plan(self):
        self.trial(4, 19870403)
        self.trial(6, 19870403)
        plan = {
            "schemaVersion": "marketlab.alpha-trial-plan.v1",
            "campaignId": "campaign",
            "orderedTasks": [{"trialId": "planned", "promotionSourceTrialSha256s": []}],
        }
        write_once_json(self.root / "search-6" / "checkpoints" / "plans" / "wrong-name.json", plan)
        with self.assertRaisesRegex(ValueError, "modified immutable plan"):
            audit_development(self.lock, {4: self.root / "search-4", 6: self.root / "search-6"}, self.confirmation, self.root / "audit")


if __name__ == "__main__":
    unittest.main()
