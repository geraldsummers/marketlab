from pathlib import Path
import os
import sys
import unittest
from unittest.mock import patch

import campaign
from marketlab_alpha import focused_v3, variance_exposure, worker


class BudgetEntrypointTest(unittest.TestCase):
    def test_campaign_refuses_before_opening_confirmation_files(self):
        args = ['campaign.py', 'confirm', '--panel', '/missing-panel', '--frozen', '/missing-lock',
                '--frozen-sha256', '0' * 64, '--output', '/missing-output']
        with patch.dict(os.environ, {}, clear=True), patch.object(sys, 'argv', args):
            with self.assertRaisesRegex(ValueError, 'start and run'):
                campaign.main()

    def test_focused_confirmation_refuses_before_opening_outcomes(self):
        args = ['confirm-focused-v3', '--frozen', '/missing-lock', '--frozen-sha256', '0' * 64, '--output', '/missing-output']
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'start and run'):
            focused_v3.main(args)

    def test_exposure_acquisition_requires_budget(self):
        args = ['acquire-funding', '--frozen', '/missing-lock', '--frozen-sha256', '0' * 64, '--output', '/missing-output']
        with patch.dict(os.environ, {}, clear=True), self.assertRaisesRegex(ValueError, 'start and run'):
            variance_exposure.main(args)

    def test_worker_uses_earlier_experiment_deadline(self):
        request = {'protocolVersion': 1, 'runtime': 'PYTHON', 'deadline': 9999999999999}
        with patch.dict(os.environ, {'MARKETLAB_EXPERIMENT_WORK_DEADLINE': '1'}):
            with self.assertRaisesRegex(ValueError, 'deadline has expired'):
                worker.execute({'protocolVersion': 1}, request, Path('/unused'))

    def test_supervised_search_is_serial(self):
        from marketlab_alpha.search import _adaptive_trial_workers
        with patch.dict(os.environ, {'MARKETLAB_EXPERIMENT_ID': 'test'}):
            self.assertEqual(1, _adaptive_trial_workers('ridge', 20, 8, memory_usage=(0, 1024**4)))


if __name__ == '__main__':
    unittest.main()
