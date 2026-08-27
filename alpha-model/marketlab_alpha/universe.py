"""Point-in-time weekly universe reconstruction.

The selector deliberately accepts historical observations rather than a current
symbol catalogue.  Every selection is therefore a pure function of rows whose
``observed_at`` timestamp is strictly earlier than the decision timestamp.
"""

from __future__ import annotations

from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import math
from typing import Iterable, Sequence


SUPPORTED_BASKET_SIZES = (4, 6, 10)
DEFAULT_MANDATORY_SYMBOLS = ("BTC", "ETH")


def _utc(value: datetime, field: str) -> datetime:
    if value.tzinfo is None or value.utcoffset() is None:
        raise ValueError(f"{field} must be timezone-aware")
    return value.astimezone(timezone.utc)


def _symbol(value: str) -> str:
    normalized = value.strip().upper()
    if not normalized:
        raise ValueError("symbol must not be empty")
    return normalized


@dataclass(frozen=True, slots=True)
class HistoricalObservation:
    """One causally available market observation.

    ``eligible`` is the listing/trading state known at ``observed_at``.  A
    delisting or suspension can consequently be represented by a final row
    with ``eligible=False`` and zero notional.
    """

    symbol: str
    observed_at: datetime
    quote_notional: float
    eligible: bool = True

    def __post_init__(self) -> None:
        object.__setattr__(self, "symbol", _symbol(self.symbol))
        object.__setattr__(self, "observed_at", _utc(self.observed_at, "observed_at"))
        notional = float(self.quote_notional)
        if not math.isfinite(notional) or notional < 0.0:
            raise ValueError("quote_notional must be finite and non-negative")
        object.__setattr__(self, "quote_notional", notional)

    def to_dict(self) -> dict[str, object]:
        return {
            "symbol": self.symbol,
            "observedAt": self.observed_at.isoformat(),
            "quoteNotional": self.quote_notional,
            "eligible": self.eligible,
        }


@dataclass(frozen=True, slots=True)
class UniverseMember:
    symbol: str
    trailing_quote_notional: float
    liquidity_rank: int
    mandatory: bool

    def __post_init__(self) -> None:
        object.__setattr__(self, "symbol", _symbol(self.symbol))
        if self.liquidity_rank < 1:
            raise ValueError("liquidity_rank must be positive")
        notional = float(self.trailing_quote_notional)
        if not math.isfinite(notional) or notional < 0.0:
            raise ValueError("trailing_quote_notional must be finite and non-negative")
        object.__setattr__(self, "trailing_quote_notional", notional)

    def to_dict(self) -> dict[str, object]:
        return {
            "symbol": self.symbol,
            "trailingQuoteNotional": self.trailing_quote_notional,
            "liquidityRank": self.liquidity_rank,
            "mandatory": self.mandatory,
        }


@dataclass(frozen=True, slots=True)
class UniverseSnapshot:
    """An immutable basket selected at one weekly decision timestamp."""

    as_of: datetime
    basket_size: int
    members: tuple[UniverseMember, ...]
    history_window_start: datetime
    ranking_window_start: datetime

    def __post_init__(self) -> None:
        object.__setattr__(self, "as_of", _utc(self.as_of, "as_of"))
        object.__setattr__(
            self,
            "history_window_start",
            _utc(self.history_window_start, "history_window_start"),
        )
        object.__setattr__(
            self,
            "ranking_window_start",
            _utc(self.ranking_window_start, "ranking_window_start"),
        )
        object.__setattr__(self, "members", tuple(self.members))
        if self.basket_size != len(self.members):
            raise ValueError("basket_size must equal the number of members")
        symbols = self.symbols
        if len(set(symbols)) != len(symbols):
            raise ValueError("universe members must be unique")
        if not self.history_window_start < self.as_of:
            raise ValueError("history_window_start must precede as_of")
        if not self.ranking_window_start < self.as_of:
            raise ValueError("ranking_window_start must precede as_of")

    @property
    def symbols(self) -> tuple[str, ...]:
        return tuple(member.symbol for member in self.members)

    def to_dict(self) -> dict[str, object]:
        return {
            "asOf": self.as_of.isoformat(),
            "basketSize": self.basket_size,
            "historyWindowStart": self.history_window_start.isoformat(),
            "rankingWindowStart": self.ranking_window_start.isoformat(),
            "members": [member.to_dict() for member in self.members],
        }


class PointInTimeUniverseBuilder:
    """Select deterministic liquidity baskets without consulting future rows."""

    def __init__(
        self,
        observations: Iterable[HistoricalObservation],
        *,
        required_history_days: int = 90,
        ranking_window_days: int = 30,
        freshness: timedelta = timedelta(days=1),
        mandatory_symbols: Sequence[str] = DEFAULT_MANDATORY_SYMBOLS,
    ) -> None:
        if required_history_days < 1:
            raise ValueError("required_history_days must be positive")
        if ranking_window_days < 1:
            raise ValueError("ranking_window_days must be positive")
        if ranking_window_days > required_history_days:
            raise ValueError("ranking_window_days cannot exceed required_history_days")
        if freshness <= timedelta(0):
            raise ValueError("freshness must be positive")

        mandatory = tuple(_symbol(symbol) for symbol in mandatory_symbols)
        if not mandatory or len(set(mandatory)) != len(mandatory):
            raise ValueError("mandatory_symbols must be non-empty and unique")

        indexed: dict[str, list[HistoricalObservation]] = defaultdict(list)
        seen: set[tuple[str, datetime]] = set()
        for observation in observations:
            if not isinstance(observation, HistoricalObservation):
                raise TypeError("observations must contain HistoricalObservation values")
            identity = (observation.symbol, observation.observed_at)
            if identity in seen:
                raise ValueError(
                    "duplicate symbol/timestamp observation is ambiguous: "
                    f"{observation.symbol} at {observation.observed_at.isoformat()}"
                )
            seen.add(identity)
            indexed[observation.symbol].append(observation)

        self._observations = {
            symbol: tuple(sorted(rows, key=lambda row: row.observed_at))
            for symbol, rows in indexed.items()
        }
        self.required_history_days = required_history_days
        self.ranking_window_days = ranking_window_days
        self.freshness = freshness
        self.mandatory_symbols = mandatory

    def select(self, as_of: datetime, basket_size: int) -> UniverseSnapshot:
        as_of = _utc(as_of, "as_of")
        if basket_size not in SUPPORTED_BASKET_SIZES:
            raise ValueError(
                f"basket_size must be one of {SUPPORTED_BASKET_SIZES}, got {basket_size}"
            )
        if basket_size < len(self.mandatory_symbols):
            raise ValueError("basket_size cannot be smaller than mandatory_symbols")

        history_start = as_of - timedelta(days=self.required_history_days)
        ranking_start = as_of - timedelta(days=self.ranking_window_days)
        notionals: dict[str, float] = {}

        for symbol, all_rows in self._observations.items():
            # Strict inequality is the core no-future-information invariant.
            prior_rows = tuple(row for row in all_rows if row.observed_at < as_of)
            if not prior_rows:
                continue
            latest = prior_rows[-1]
            if not latest.eligible or latest.observed_at < as_of - self.freshness:
                continue

            history_rows = tuple(
                row
                for row in prior_rows
                if row.eligible and history_start <= row.observed_at < as_of
            )
            observed_days = {row.observed_at.date() for row in history_rows}
            if len(observed_days) < self.required_history_days:
                continue

            ranking_rows = tuple(
                row for row in history_rows if ranking_start <= row.observed_at < as_of
            )
            if not ranking_rows:
                continue
            notionals[symbol] = math.fsum(row.quote_notional for row in ranking_rows)

        missing_mandatory = tuple(
            symbol for symbol in self.mandatory_symbols if symbol not in notionals
        )
        if missing_mandatory:
            raise ValueError(
                "mandatory symbols lack causal history or current eligibility at "
                f"{as_of.isoformat()}: {', '.join(missing_mandatory)}"
            )

        ranked = tuple(sorted(notionals, key=lambda symbol: (-notionals[symbol], symbol)))
        optional = tuple(
            symbol for symbol in ranked if symbol not in self.mandatory_symbols
        )
        optional_needed = basket_size - len(self.mandatory_symbols)
        if len(optional) < optional_needed:
            raise ValueError(
                f"only {len(notionals)} symbols qualify at {as_of.isoformat()}, "
                f"but basket_size={basket_size}"
            )

        selected = self.mandatory_symbols + optional[:optional_needed]
        ranks = {symbol: index + 1 for index, symbol in enumerate(ranked)}
        members = tuple(
            UniverseMember(
                symbol=symbol,
                trailing_quote_notional=notionals[symbol],
                liquidity_rank=ranks[symbol],
                mandatory=symbol in self.mandatory_symbols,
            )
            for symbol in selected
        )
        return UniverseSnapshot(
            as_of=as_of,
            basket_size=basket_size,
            members=members,
            history_window_start=history_start,
            ranking_window_start=ranking_start,
        )

    def weekly(
        self,
        first_as_of: datetime,
        last_as_of: datetime,
        *,
        basket_sizes: Sequence[int] = SUPPORTED_BASKET_SIZES,
    ) -> tuple[UniverseSnapshot, ...]:
        """Select each requested basket every seven days, endpoints inclusive.

        The caller owns the weekly decision clock; this method requires both
        endpoints to use the same UTC weekday and wall-clock time.
        """

        first = _utc(first_as_of, "first_as_of")
        last = _utc(last_as_of, "last_as_of")
        if last < first:
            raise ValueError("last_as_of must not precede first_as_of")
        if (first.weekday(), first.time()) != (last.weekday(), last.time()):
            raise ValueError("weekly endpoints must share a UTC weekday and time")

        sizes = tuple(basket_sizes)
        if not sizes or len(set(sizes)) != len(sizes):
            raise ValueError("basket_sizes must be non-empty and unique")
        unsupported = tuple(size for size in sizes if size not in SUPPORTED_BASKET_SIZES)
        if unsupported:
            raise ValueError(
                f"basket_sizes contains unsupported values {unsupported}; "
                f"supported={SUPPORTED_BASKET_SIZES}"
            )

        snapshots: list[UniverseSnapshot] = []
        decision_time = first
        while decision_time <= last:
            snapshots.extend(self.select(decision_time, size) for size in sizes)
            decision_time += timedelta(days=7)
        return tuple(snapshots)


def select_universe(
    observations: Iterable[HistoricalObservation],
    as_of: datetime,
    basket_size: int,
    **builder_options: object,
) -> UniverseSnapshot:
    """Convenience wrapper for a single point-in-time selection."""

    return PointInTimeUniverseBuilder(observations, **builder_options).select(
        as_of, basket_size
    )


def build_weekly_universes(
    observations: Iterable[HistoricalObservation],
    first_as_of: datetime,
    last_as_of: datetime,
    *,
    basket_sizes: Sequence[int] = SUPPORTED_BASKET_SIZES,
    **builder_options: object,
) -> tuple[UniverseSnapshot, ...]:
    """Convenience wrapper for immutable weekly basket reconstruction."""

    return PointInTimeUniverseBuilder(observations, **builder_options).weekly(
        first_as_of, last_as_of, basket_sizes=basket_sizes
    )
