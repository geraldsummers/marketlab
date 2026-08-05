import json
import unittest
from datetime import datetime, timedelta, timezone

from marketlab_alpha.universe import (
    HistoricalObservation,
    PointInTimeUniverseBuilder,
    build_weekly_universes,
    select_universe,
)


UTC = timezone.utc


def history(
    symbol: str,
    as_of: datetime,
    daily_notional: float,
    *,
    days: int = 90,
) -> list[HistoricalObservation]:
    return [
        HistoricalObservation(
            symbol=symbol,
            observed_at=as_of - timedelta(days=offset),
            quote_notional=daily_notional,
        )
        for offset in range(days, 0, -1)
    ]


class PointInTimeUniverseTest(unittest.TestCase):
    def setUp(self) -> None:
        self.as_of = datetime(2025, 4, 7, tzinfo=UTC)  # Monday
        self.rows: list[HistoricalObservation] = []
        notionals = {
            "BTC": 2.0,
            "ETH": 1.0,
            "SOL": 12.0,
            "XRP": 11.0,
            "ADA": 10.0,
            "DOGE": 9.0,
            "AVAX": 8.0,
            "LINK": 7.0,
            "DOT": 6.0,
            "LTC": 5.0,
            "ATOM": 4.0,
            "UNI": 3.0,
        }
        for symbol, notional in notionals.items():
            self.rows.extend(history(symbol, self.as_of, notional))

    def test_mandatory_assets_are_kept_while_optional_assets_follow_liquidity(self) -> None:
        snapshot = select_universe(self.rows, self.as_of, 6)

        self.assertEqual(
            snapshot.symbols,
            ("BTC", "ETH", "SOL", "XRP", "ADA", "DOGE"),
        )
        self.assertTrue(snapshot.members[0].mandatory)
        self.assertTrue(snapshot.members[1].mandatory)
        self.assertEqual(snapshot.members[2].liquidity_rank, 1)
        self.assertEqual(snapshot.members[2].trailing_quote_notional, 360.0)

    def test_all_supported_basket_sizes_are_reconstructed_weekly(self) -> None:
        second_as_of = self.as_of + timedelta(days=7)
        rows = list(self.rows)
        for symbol in {row.symbol for row in self.rows}:
            rows.extend(history(symbol, second_as_of, 1.0, days=7))

        snapshots = build_weekly_universes(rows, self.as_of, second_as_of)

        self.assertEqual(
            tuple((snapshot.as_of, snapshot.basket_size) for snapshot in snapshots),
            (
                (self.as_of, 4),
                (self.as_of, 6),
                (self.as_of, 10),
                (second_as_of, 4),
                (second_as_of, 6),
                (second_as_of, 10),
            ),
        )
        self.assertTrue(all(len(snapshot.symbols) == snapshot.basket_size for snapshot in snapshots))

    def test_future_rows_cannot_change_a_past_snapshot(self) -> None:
        before = select_universe(self.rows, self.as_of, 4)
        future_rows = list(self.rows)
        future_rows.extend(
            (
                HistoricalObservation("UNI", self.as_of, 1_000_000.0),
                HistoricalObservation("SOL", self.as_of + timedelta(seconds=1), 0.0, False),
            )
        )

        after = select_universe(future_rows, self.as_of, 4)

        self.assertEqual(before, after)

    def test_new_listing_with_less_than_ninety_days_never_qualifies(self) -> None:
        rows = list(self.rows)
        rows.extend(history("NEW", self.as_of, 1_000_000.0, days=89))

        snapshot = select_universe(rows, self.as_of, 4)

        self.assertNotIn("NEW", snapshot.symbols)

    def test_delisting_state_and_stale_observations_remove_assets(self) -> None:
        rows = list(self.rows)
        rows.append(
            HistoricalObservation(
                "SOL",
                self.as_of - timedelta(hours=1),
                0.0,
                eligible=False,
            )
        )
        stale_rows = [
            row
            for row in rows
            if not (row.symbol == "XRP" and row.observed_at == self.as_of - timedelta(days=1))
        ]

        snapshot = select_universe(stale_rows, self.as_of, 4)

        self.assertNotIn("SOL", snapshot.symbols)
        self.assertNotIn("XRP", snapshot.symbols)
        self.assertEqual(snapshot.symbols, ("BTC", "ETH", "ADA", "DOGE"))

    def test_ties_are_broken_by_symbol_independent_of_input_order(self) -> None:
        forward = select_universe(self.rows, self.as_of, 4)
        reverse = select_universe(reversed(self.rows), self.as_of, 4)

        self.assertEqual(forward, reverse)

    def test_snapshot_is_json_serializable_via_explicit_wire_mapping(self) -> None:
        snapshot = select_universe(self.rows, self.as_of, 4)

        payload = json.loads(json.dumps(snapshot.to_dict(), sort_keys=True))

        self.assertEqual(payload["asOf"], "2025-04-07T00:00:00+00:00")
        self.assertEqual([row["symbol"] for row in payload["members"]], list(snapshot.symbols))

    def test_missing_mandatory_history_fails_instead_of_silently_changing_universe(self) -> None:
        rows = [row for row in self.rows if row.symbol != "ETH"]

        with self.assertRaisesRegex(ValueError, "mandatory symbols.*ETH"):
            select_universe(rows, self.as_of, 4)

    def test_duplicate_timestamp_is_rejected_as_ambiguous(self) -> None:
        duplicate = self.rows[0]

        with self.assertRaisesRegex(ValueError, "duplicate symbol/timestamp"):
            PointInTimeUniverseBuilder([*self.rows, duplicate])

    def test_weekly_schedule_must_keep_same_utc_decision_clock(self) -> None:
        builder = PointInTimeUniverseBuilder(self.rows)

        with self.assertRaisesRegex(ValueError, "UTC weekday and time"):
            builder.weekly(self.as_of, self.as_of + timedelta(days=8))


if __name__ == "__main__":
    unittest.main()
