from __future__ import annotations

import sys
import unittest
from subprocess import CompletedProcess
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import workspace


class AlphaWorkspaceTest(unittest.TestCase):
    def test_bias_tolerant_discovery_policy_keeps_strict_ceiling(self):
        policy = workspace.load_policy()
        self.assertTrue(policy["discoveryPolicy"]["availabilityBiasedHistoricalDiscoveryAllowed"])
        self.assertFalse(policy["discoveryPolicy"]["targetLeakageAllowed"])
        self.assertEqual("EXPLORATORY", policy["discoveryPolicy"]["promotionCeiling"])
        self.assertFalse(policy["prospectiveActivationPolicy"]["ordinaryFutureWaitingAllowed"])

    def test_repository_workspace_is_valid(self) -> None:
        self.assertEqual([], workspace.validate())

    def test_inventory_has_unique_candidates_and_ready_work(self) -> None:
        value = workspace.inventory()
        candidates = [candidate["id"] for space in value["spaces"] for candidate in space["candidates"]]
        self.assertEqual(len(candidates), len(set(candidates)))
        self.assertGreaterEqual(len(value["spaces"]), 8)
        self.assertTrue(workspace.ready_work())

    def test_repository_inventory_covers_theories_modules_and_evidence(self) -> None:
        self.assertGreaterEqual(len(workspace.inventory_rows("theories")), 20)
        self.assertGreaterEqual(len(workspace.inventory_rows("evidence")), 12)
        self.assertGreaterEqual(len(workspace.inventory_rows("data")), 11)
        self.assertGreaterEqual(len(workspace.inventory_rows("components")), 23)
        self.assertGreaterEqual(len(workspace.inventory_rows("operations")), 12)
        self.assertTrue(workspace.inventory_rows("compute"))

    def test_historical_mode_is_default_and_preserves_prospective_work(self) -> None:
        historical = workspace.ready_work()
        prospective = workspace.ready_work(mode="PROSPECTIVE", all_priorities=True)
        self.assertFalse(any(item["id"] == "prospective-social-materialization" for item in historical))
        self.assertTrue(any(item["id"] == "prospective-social-materialization" for item in prospective))
        self.assertEqual("ALL", workspace.inventory(mode="ALL")["mode"])

    def test_default_ready_view_exposes_only_the_highest_runnable_priority(self) -> None:
        ready = workspace.ready_work()
        all_ready = workspace.ready_work(all_priorities=True)
        self.assertTrue(ready)
        highest = min(workspace.TASK_PRIORITIES[item["priority"]] for item in all_ready)
        self.assertTrue(all(workspace.TASK_PRIORITIES[item["priority"]] == highest for item in ready))
        self.assertEqual(
            sorted(item["id"] for item in ready),
            sorted(item["id"] for item in all_ready if workspace.TASK_PRIORITIES[item["priority"]] == highest),
        )

    def test_task_contract_is_complete_and_compute_bounded(self) -> None:
        task = workspace.task_inventory("conditional-sentiment-direction-thesis")
        self.assertEqual(["event-conditioned-social-direction-v1"], task["candidateIds"])
        self.assertEqual("NONE", task["outcomeAccess"])
        self.assertEqual(0, task["computeLimits"]["maxTrials"])
        self.assertTrue(task["deliverables"])
        self.assertTrue(task["acceptanceCriteria"])
        self.assertTrue(task["claimSurfaces"])

    def test_outcome_inventory_exposes_consumed_v3_period(self) -> None:
        value = workspace.outcome_inventory(candidate_id="binance-market-state-direction-v3")
        self.assertEqual(1, len(value["outcomes"]))
        self.assertEqual("2025-06-01T00:00:00Z", value["outcomes"][0]["startInclusive"])
        filtered = workspace.outcome_inventory(start="2026-07-15T00:00:00Z", end="2026-08-02T00:00:00Z")
        self.assertTrue(any(row["id"] == "focused-v3-binance-confirmation" for row in filtered["outcomes"]))

    def test_synthesis_is_deterministic_and_compute_aware(self) -> None:
        first = workspace.synthesis()
        self.assertEqual(first, workspace.synthesis())
        self.assertEqual("HISTORICAL", first["mode"])
        self.assertTrue(first["computeRuns"])
        self.assertIn("REJECTED", first["stages"])

    def test_artifact_verifier_reports_remote_pass_and_mismatch(self) -> None:
        rows = [{"id": "example", "artifacts": [{"role": "report", "sha256": "a" * 64, "location": "/remote/report.json", "verification": "REMOTE_FILE"}]}]
        def passing_runner(*args: object, **kwargs: object) -> CompletedProcess[str]:
            return CompletedProcess([], 0, stdout=f"{'a' * 64}  /remote/report.json\n", stderr="")
        results, exit_code = workspace.verify_artifacts(rows, "host", runner=passing_runner)
        self.assertEqual((0, "PASS"), (exit_code, results[0]["status"]))
        def mismatch_runner(*args: object, **kwargs: object) -> CompletedProcess[str]:
            return CompletedProcess([], 0, stdout=f"{'b' * 64}  /remote/report.json\n", stderr="")
        results, exit_code = workspace.verify_artifacts(rows, "host", runner=mismatch_runner)
        self.assertEqual((1, "MISMATCH"), (exit_code, results[0]["status"]))
    def test_broad_agenda_remains_available_after_focused_audits(self):
        ready = workspace.ready_work()
        ready = [t for t in ready if t["selectionRank"] == 100]
        self.assertEqual(4, len(ready))
        self.assertEqual({"P1"}, {task["priority"] for task in ready})
        self.assertEqual({"conventional", "onchain", "prediction-markets", "usd-stablecoins"},
                         {domain for task in ready for domain in task["domains"]})
        for task in ready:
            contract = workspace.load_json(workspace.REPO_ROOT / task["experimentContract"])
            self.assertEqual("NONE", contract["outcomeAccess"])
            self.assertLess(contract["budgetSeconds"], 43200)
            self.assertEqual(0, contract["maxTrials"])

    def test_invalid_new_contract_is_rejected_by_workspace(self):
        from unittest.mock import patch
        original = workspace.load_json
        def changed(path):
            value = original(path)
            if path.name == "experiment.json":
                value["budgetSeconds"] = 43200
            return value
        with patch.object(workspace, "load_json", side_effect=changed):
            self.assertTrue(any("invalid experiment contract" in error for error in workspace.validate()))

    def test_historical_locks_and_legacy_candidates_remain_readable(self):
        spaces, candidates = workspace.discover()
        legacy = [c for _, c in candidates if not c.get("experimentContract")]
        self.assertEqual(38, len(legacy))
        for path in workspace.REPO_ROOT.glob("research/**/*.lock.json"):
            self.assertIsInstance(workspace.load_json(path), dict)


    def test_focused_audits_have_bounded_budgets_and_failure_sampling(self):
        tasks = workspace.ready_work()[:3]
        self.assertEqual([10, 20, 30], [t["selectionRank"] for t in tasks])
        for task in tasks:
            contract = workspace.validate_contract(workspace.load_json(workspace.REPO_ROOT / task["experimentContract"]))
            self.assertEqual((7200, 900, 0, "NONE"), tuple(contract[k] for k in ("budgetSeconds", "reportReserveSeconds", "maxTrials", "outcomeAccess")))
            self.assertTrue(any("ordinary comparison periods" in x and "failed/cancelled" in x for x in task["acceptanceCriteria"]))
        _, candidates = workspace.discover()
        new = [c for _, c in candidates if c["id"] in {t["candidateIds"][0] for t in tasks}]
        self.assertTrue(all("years" in c["horizon"] and c["inspirationRefs"] for c in new))
        # Horizon does not participate in wall-clock accounting or expand the contract.
        self.assertTrue(all(c["stage"] == "DATA_FEASIBILITY" for c in new))

    def test_task_inspection_exposes_rank_and_inspiration(self):
        value = workspace.task_inventory("corporate-event-terms-feasibility")
        self.assertEqual(10, value["selectionRank"])
        self.assertIn("research/inspiration/cases/frontera-tender-amendment.json", value["inspirationRefs"])
        legacy = workspace.task_inventory("conventional-market-feasibility")
        self.assertEqual((100, []), (legacy["selectionRank"], legacy["inspirationRefs"]))

    def test_invalid_rank_and_provenance_are_rejected(self):
        from unittest.mock import patch
        from copy import deepcopy
        original = workspace.discover()
        for bad in (-1, True, "10"):
            spaces, candidates = deepcopy(original)
            spaces[0][1]["readyWork"][0]["selectionRank"] = bad
            with patch.object(workspace, "discover", return_value=(spaces, candidates)):
                self.assertTrue(any("selectionRank" in e for e in workspace.validate()))
        for bad in ("research/inspiration/cases/missing.json", "../../outside.json", "/outside.json"):
            spaces, candidates = deepcopy(original)
            candidates[0][1]["inspirationRefs"] = [bad]
            candidates[0][1]["transferAssessment"] = "Unverified transfer"
            with patch.object(workspace, "discover", return_value=(spaces, candidates)):
                self.assertTrue(any("inspiration" in e for e in workspace.validate()))

    def test_inspiration_is_not_validation_and_requires_counterevidence(self):
        from unittest.mock import patch
        original = workspace.load_json
        def changed(path):
            value = original(path)
            if value.get("schemaVersion") == "marketlab.inspiration.v1":
                value["stage"] = "BLIND_VALIDATED"
            return value
        with patch.object(workspace, "load_json", side_effect=changed):
            self.assertTrue(any("IDEA stage" in e for e in workspace.validate()))
        def without_counterexample(path):
            value = original(path)
            if value.get("schemaVersion") == "marketlab.inspiration.v1":
                value["counterevidence"] = ""
            return value
        with patch.object(workspace, "load_json", side_effect=without_counterexample):
            self.assertTrue(any("counterevidence" in e for e in workspace.validate()))

    def test_rank_never_overrides_priority_and_old_tasks_default_to_100(self):
        from unittest.mock import patch
        from copy import deepcopy
        spaces, candidates = deepcopy(workspace.discover())
        target = next(t for _, s in spaces for t in s["readyWork"] if t["id"] == "conventional-market-feasibility")
        target["priority"] = "P0"
        with patch.object(workspace, "discover", return_value=(spaces, candidates)):
            ready = workspace.ready_work()
            self.assertEqual(["conventional-market-feasibility"], [t["id"] for t in ready])
            self.assertEqual(100, ready[0]["selectionRank"])


if __name__ == "__main__":
    unittest.main()
