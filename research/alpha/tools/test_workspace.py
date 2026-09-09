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
    def test_new_agenda_has_four_equal_priority_feasibility_contracts(self):
        ready = workspace.ready_work()
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


if __name__ == "__main__":
    unittest.main()
