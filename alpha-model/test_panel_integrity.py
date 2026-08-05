import math
import unittest

from marketlab_alpha.panel import (
    MarketBar,
    materialize_panel,
    resample_bars,
    temporal_windows,
)


MINUTE = 60_000


def bar(step: int, *, available_step: int | None = None) -> dict[str, object]:
    available = (step + 1) * 5 if available_step is None else available_step
    return {
        "symbol": "BTC",
        "eventTimeEpochMillis": step * 5 * MINUTE,
        "availableTimeEpochMillis": available * MINUTE,
        "open": 100.0 + step,
        "high": 102.0 + step,
        "low": 99.0 + step,
        "close": 101.0 + step,
        "quoteVolume": 1_000.0 + step,
        "tradeCount": 10 + step,
        "takerBuyQuoteVolume": 600.0 + step,
    }


class PanelCausalIntegrityTest(unittest.TestCase):
    def test_delayed_earlier_bar_cannot_leak_into_an_earlier_decision(self) -> None:
        rows = [bar(0, available_step=20), bar(1, available_step=10)]

        with self.assertRaisesRegex(ValueError, "availability time must be strictly increasing"):
            materialize_panel(rows, None, ("5m",), 5 * MINUTE, lag_count=2)

    def test_full_lag_count_is_required_before_features_are_emitted(self) -> None:
        rows = [bar(step) for step in range(5)]

        panel = materialize_panel(rows, None, ("5m",), 5 * MINUTE, lag_count=4)

        self.assertEqual(1, len(panel))
        self.assertEqual(25 * MINUTE, panel[0]["decisionTimeEpochMillis"])
        expected_returns = [
            math.log((101.0 + right) / (101.0 + left))
            for left, right in zip(range(4), range(1, 5))
        ]
        self.assertAlmostEqual(sum(expected_returns) / 4, panel[0]["features"]["mean_return"])
        self.assertAlmostEqual(
            math.log((101.0 + 4) / (101.0 + 3)),
            panel[0]["features"]["persistence_return_5m"],
        )

    def test_missing_mandatory_cross_asset_quarantines_the_decision_time(self) -> None:
        rows = [bar(step) for step in range(5)]

        panel = materialize_panel(
            rows,
            None,
            ("5m",),
            5 * MINUTE,
            lag_count=4,
            mandatory_cross_assets=("BTC", "ETH"),
        )

        self.assertEqual([], panel)

    def test_gap_resets_warmup_and_assigns_a_new_continuity_segment(self) -> None:
        rows = [bar(step) for step in (0, 1, 2, 3, 4, 6, 7, 8, 9, 10)]

        panel = materialize_panel(rows, None, ("5m",), 5 * MINUTE, lag_count=4)

        self.assertEqual([25 * MINUTE, 55 * MINUTE], [row["decisionTimeEpochMillis"] for row in panel])
        self.assertEqual([0, 1], [row["continuitySegmentId"] for row in panel])

    def test_temporal_windows_skip_time_gaps_and_membership_segments(self) -> None:
        rows = [
            self._temporal_row(5, 0, 1.0),
            self._temporal_row(10, 0, 2.0),
            self._temporal_row(20, 0, 3.0),
            self._temporal_row(25, 1, 4.0),
            self._temporal_row(30, 1, 5.0),
        ]

        tensors, indices = temporal_windows(rows, ("x",), 2)

        self.assertEqual([1, 4], indices)
        self.assertEqual([[[1.0], [2.0]], [[4.0], [5.0]]], tensors)

    def test_membership_exit_and_reentry_reset_scalar_and_temporal_history(self) -> None:
        rows = [bar(step) for step in range(9)]
        memberships = {
            0: ("BTC",),
            20 * MINUTE: (),
            30 * MINUTE: ("BTC",),
        }

        panel = materialize_panel(
            rows,
            memberships,
            ("5m",),
            5 * MINUTE,
            lag_count=2,
        )
        tensors, indices = temporal_windows(panel, ("latest_return",), 2)

        self.assertEqual(
            [15 * MINUTE, 40 * MINUTE, 45 * MINUTE],
            [row["decisionTimeEpochMillis"] for row in panel],
        )
        self.assertEqual([0, 2, 2], [row["continuitySegmentId"] for row in panel])
        self.assertEqual([2], indices)
        self.assertEqual(1, len(tensors))

    def test_temporal_rows_must_carry_interval_and_segment_identity(self) -> None:
        incomplete = [{
            "symbol": "BTC",
            "decisionTimeEpochMillis": 5 * MINUTE,
            "features": {"x": 1.0},
        }]

        with self.assertRaisesRegex(ValueError, "baseIntervalMillis"):
            temporal_windows(incomplete, ("x",), 2)

    def test_minute_bars_are_aggregated_causally_into_complete_five_minute_bars(self) -> None:
        bars = [
            MarketBar(
                symbol="BTC",
                event_time_ms=step * MINUTE,
                available_time_ms=(step + 1) * MINUTE,
                open=100.0 + step,
                high=102.0 + step,
                low=99.0 + step,
                close=101.0 + step,
                quote_volume=10.0,
                trade_count=2,
                taker_buy_quote_volume=6.0,
            )
            for step in range(10)
        ]

        aggregated = resample_bars(bars, 5 * MINUTE)

        self.assertEqual(2, len(aggregated))
        self.assertEqual((0, 5 * MINUTE), (aggregated[0].event_time_ms, aggregated[0].available_time_ms))
        self.assertEqual((100.0, 106.0, 99.0, 105.0), (
            aggregated[0].open,
            aggregated[0].high,
            aggregated[0].low,
            aggregated[0].close,
        ))
        self.assertEqual((50.0, 10, 30.0), (
            aggregated[0].quote_volume,
            aggregated[0].trade_count,
            aggregated[0].taker_buy_quote_volume,
        ))

    def test_materializer_accepts_a_complete_minute_archive_on_a_five_minute_grid(self) -> None:
        rows = [
            {
                "symbol": "BTC",
                "eventTimeEpochMillis": step * MINUTE,
                "availableTimeEpochMillis": (step + 1) * MINUTE,
                "open": 100.0 + step,
                "high": 102.0 + step,
                "low": 99.0 + step,
                "close": 101.0 + step,
                "quoteVolume": 10.0,
                "tradeCount": 2,
                "takerBuyQuoteVolume": 6.0,
            }
            for step in range(20)
        ]

        panel = materialize_panel(
            rows,
            None,
            ("5m",),
            5 * MINUTE,
            lag_count=2,
        )

        self.assertEqual([15 * MINUTE, 20 * MINUTE], [
            row["decisionTimeEpochMillis"] for row in panel
        ])

    def test_incomplete_resample_bucket_is_quarantined_not_filled(self) -> None:
        bars = [
            MarketBar("BTC", step * MINUTE, (step + 1) * MINUTE, 100.0 + step)
            for step in (0, 1, 3, 4, 5, 6, 7, 8, 9)
        ]

        aggregated = resample_bars(bars, 5 * MINUTE)

        self.assertEqual([5 * MINUTE], [item.event_time_ms for item in aggregated])

    def test_systematically_missing_minute_rows_are_not_misread_as_a_coarser_source(self) -> None:
        bars = [
            MarketBar("BTC", step * MINUTE, (step + 1) * MINUTE, 100.0 + step)
            for step in range(0, 20, 2)
        ]

        with self.assertRaisesRegex(ValueError, "finer-bar interval is ambiguous"):
            resample_bars(bars, 10 * MINUTE)

        bars_at_target_spacing = [
            MarketBar("BTC", step * MINUTE, (step + 1) * MINUTE, 100.0 + step)
            for step in range(0, 20, 5)
        ]
        with self.assertRaisesRegex(ValueError, "systematically missing finer bars"):
            resample_bars(bars_at_target_spacing, 5 * MINUTE)

    def test_already_aggregated_mechanism_fields_are_preserved(self) -> None:
        bars = [
            MarketBar(
                "BTC",
                step * 5 * MINUTE,
                (step + 1) * 5 * MINUTE,
                100.0 + step,
                fields={"funding_change": float(step)},
            )
            for step in range(3)
        ]

        self.assertEqual(bars, resample_bars(bars, 5 * MINUTE))

    @staticmethod
    def _temporal_row(minute: int, segment: int, value: float) -> dict[str, object]:
        return {
            "symbol": "BTC",
            "decisionTimeEpochMillis": minute * MINUTE,
            "baseIntervalMillis": 5 * MINUTE,
            "continuitySegmentId": segment,
            "features": {"x": value},
        }


if __name__ == "__main__":
    unittest.main()
