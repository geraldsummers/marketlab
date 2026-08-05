from __future__ import annotations

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import workspace


class AlphaWorkspaceTest(unittest.TestCase):
    def test_repository_workspace_is_valid(self) -> None:
        self.assertEqual([], workspace.validate())

    def test_inventory_has_unique_candidates_and_ready_work(self) -> None:
        value = workspace.inventory()
        candidates = [candidate["id"] for space in value["spaces"] for candidate in space["candidates"]]
        self.assertEqual(len(candidates), len(set(candidates)))
        self.assertGreaterEqual(len(value["spaces"]), 8)
        self.assertTrue(workspace.ready_work())

    def test_repository_inventory_covers_theories_modules_and_evidence(self) -> None:
        self.assertEqual(20, len(workspace.inventory_rows("theories")))
        self.assertEqual(8, len(workspace.inventory_rows("evidence")))
        self.assertEqual(11, len(workspace.inventory_rows("data")))
        self.assertEqual(23, len(workspace.inventory_rows("components")))
        self.assertEqual(12, len(workspace.inventory_rows("operations")))

if __name__ == "__main__":
    unittest.main()
