"""Causal bar-panel materialization for multi-horizon return research."""

from __future__ import annotations

import math
from collections import defaultdict, deque
from dataclasses import dataclass
from typing import Any, Iterable, Mapping, Sequence


HORIZON_MILLIS = {
    "5m": 5 * 60_000,
    "15m": 15 * 60_000,
    "1h": 60 * 60_000,
    "4h": 4 * 60 * 60_000,
    "12h": 12 * 60 * 60_000,
    "1d": 24 * 60 * 60_000,
    "3d": 3 * 24 * 60 * 60_000,
    "7d": 7 * 24 * 60 * 60_000,
}


@dataclass(frozen=True)
class MarketBar:
    symbol: str
    event_time_ms: int
    available_time_ms: int
    close: float
    open: float | None = None
    high: float | None = None
    low: float | None = None
    quote_volume: float | None = None
    trade_count: int | None = None
    taker_buy_quote_volume: float | None = None
    fields: Mapping[str, float] | None = None

    @classmethod
    def from_mapping(cls, value: Mapping[str, Any]) -> "MarketBar":
        event = _integer(value, "eventTimeEpochMillis", "openTimeEpochMillis", "decisionTimeEpochMillis")
        available = _integer(value, "availableTimeEpochMillis", "closeTimeExclusiveEpochMillis", default=event)
        bar = cls(
            symbol=str(value["symbol"]).upper(),
            event_time_ms=event,
            available_time_ms=available,
            close=float(value["close"]),
            open=_number(value.get("open")),
            high=_number(value.get("high")),
            low=_number(value.get("low")),
            quote_volume=_number(value.get("quoteVolume", value.get("notional"))),
            trade_count=_optional_int(value.get("tradeCount")),
            taker_buy_quote_volume=_number(value.get("takerBuyQuoteVolume")),
            fields={str(k): float(v) for k, v in value.get("fields", {}).items()},
        )
        if not bar.symbol or not math.isfinite(bar.close) or bar.close <= 0:
            raise ValueError("bar symbol and positive finite close are required")
        if bar.available_time_ms < bar.event_time_ms:
            raise ValueError("bar cannot be available before its event time")
        return bar


def materialize_panel(
    values: Iterable[Mapping[str, Any]],
    memberships: Mapping[int, Sequence[str]] | None,
    horizons: Sequence[str],
    base_interval_ms: int,
    lag_count: int = 32,
    mandatory_cross_assets: Sequence[str] = (),
) -> list[dict[str, Any]]:
    """Create causal rows; labels are attached only to separate target fields.

    `memberships` maps effective weekly timestamps to eligible symbols.  The
    latest snapshot no later than a decision time is used.  Feature values use
    only bars whose recorded availability is no later than that decision.
    """
    if base_interval_ms <= 0 or lag_count < 2:
        raise ValueError("positive base interval and at least two lags are required")
    unknown = set(horizons) - set(HORIZON_MILLIS)
    if unknown:
        raise ValueError(f"unsupported horizons: {sorted(unknown)}")
    steps = {name: _exact_steps(HORIZON_MILLIS[name], base_interval_ms) for name in horizons}
    bars = sorted(
        (MarketBar.from_mapping(value) for value in values),
        key=lambda x: (x.event_time_ms, x.symbol),
    )
    by_symbol: dict[str, list[MarketBar]] = defaultdict(list)
    for bar in bars:
        if by_symbol[bar.symbol] and bar.event_time_ms <= by_symbol[bar.symbol][-1].event_time_ms:
            raise ValueError(f"duplicate or reversed bar time for {bar.symbol}")
        if (
            by_symbol[bar.symbol]
            and bar.available_time_ms <= by_symbol[bar.symbol][-1].available_time_ms
        ):
            raise ValueError(
                f"availability time must be strictly increasing for {bar.symbol}; "
                "a delayed earlier bar cannot enter a later decision"
            )
        by_symbol[bar.symbol].append(bar)
    by_symbol = {
        symbol: resample_bars(symbol_bars, base_interval_ms)
        for symbol, symbol_bars in by_symbol.items()
    }
    membership_times = sorted(memberships or {})
    rows: list[dict[str, Any]] = []
    for symbol, symbol_bars in sorted(by_symbol.items()):
        closes: deque[float] = deque(maxlen=lag_count + 1)
        returns: deque[float] = deque(maxlen=lag_count)
        volumes: deque[float] = deque(maxlen=lag_count)
        prior_event_time: int | None = None
        prior_eligible: bool | None = None
        continuity_segment = 0
        segment_start_index = 0
        for index, bar in enumerate(symbol_bars):
            decision = bar.available_time_ms
            eligible = (
                memberships is None
                or symbol in _members_at(decision, memberships, membership_times)
            )
            event_gap = (
                prior_event_time is not None
                and bar.event_time_ms != prior_event_time + base_interval_ms
            )
            membership_reset = prior_eligible is not None and eligible != prior_eligible
            if event_gap or membership_reset:
                closes.clear()
                returns.clear()
                volumes.clear()
                continuity_segment += 1
                segment_start_index = index
            prior_event_time = bar.event_time_ms
            prior_eligible = eligible
            prior_close = closes[-1] if closes else None
            closes.append(bar.close)
            if prior_close is not None:
                returns.append(math.log(bar.close / prior_close))
            volumes.append(bar.quote_volume or 0.0)
            if not eligible:
                continue
            if len(returns) < lag_count or len(volumes) < lag_count:
                continue
            if steps and index - max(steps.values()) < segment_start_index:
                continue
            features = _features(bar, returns, volumes)
            for name, offset in steps.items():
                past = symbol_bars[index - offset]
                expected_past = bar.event_time_ms - offset * base_interval_ms
                if past.event_time_ms != expected_past:
                    raise ValueError("same-horizon persistence feature crosses a panel discontinuity")
                features[f"persistence_return_{name}"] = math.log(bar.close / past.close)
            targets: dict[str, float | None] = {}
            for name, offset in steps.items():
                target_index = index + offset
                if target_index >= len(symbol_bars):
                    targets[name] = None
                    continue
                future = symbol_bars[target_index]
                expected = bar.event_time_ms + offset * base_interval_ms
                targets[name] = math.log(future.close / bar.close) if future.event_time_ms == expected else None
            rows.append({
                "rowId": f"{symbol}:{decision}",
                "decisionTimeEpochMillis": decision,
                "symbol": symbol,
                "features": features,
                "targets": targets,
                "sourceEventTimeEpochMillis": bar.event_time_ms,
                "baseIntervalMillis": base_interval_ms,
                "continuitySegmentId": continuity_segment,
            })
    return enrich_cross_asset_features(
        sorted(rows, key=lambda row: (row["decisionTimeEpochMillis"], row["symbol"])),
        mandatory_cross_assets,
    )


def enrich_cross_asset_features(
    rows: list[dict[str, Any]], mandatory_cross_assets: Sequence[str] = ()
) -> list[dict[str, Any]]:
    """Attach contemporaneous, already-available basket controls without labels."""
    by_time: dict[int, list[dict[str, Any]]] = defaultdict(list)
    for row in rows:
        by_time[int(row["decisionTimeEpochMillis"])].append(row)
    result: list[dict[str, Any]] = []
    required = {str(symbol).upper() for symbol in mandatory_cross_assets}
    for contemporaneous in by_time.values():
        latest = {
            str(row["symbol"]): float(row["features"]["latest_return"])
            for row in contemporaneous
        }
        if not required.issubset(latest):
            continue
        basket = sum(latest.values()) / len(latest)
        ordered = sorted(latest.items(), key=lambda item: (item[1], item[0]))
        ranks = {
            symbol: (index / (len(ordered) - 1) if len(ordered) > 1 else 0.5)
            for index, (symbol, _) in enumerate(ordered)
        }
        for row in contemporaneous:
            symbol = str(row["symbol"])
            features = row["features"]
            features["btc_latest_return"] = latest.get("BTC", basket)
            features["eth_latest_return"] = latest.get("ETH", basket)
            features["basket_latest_return"] = basket
            features["btc_divergence"] = latest[symbol] - latest.get("BTC", basket)
            features["cross_section_return_rank"] = ranks[symbol]
            result.append(row)
    return sorted(result, key=lambda row: (row["decisionTimeEpochMillis"], row["symbol"]))


def temporal_windows(
    rows: Sequence[Mapping[str, Any]],
    feature_names: Sequence[str],
    lookback: int,
    base_interval_ms: int | None = None,
) -> tuple[list[list[list[float]]], list[int]]:
    """Return exact, time-major windows without crossing causal resets.

    Panel rows carry their materialization interval and continuity segment.  A
    candidate window is emitted only when every decision is exactly one base
    interval after the previous decision and all rows share one segment.
    """
    if lookback < 2 or not feature_names:
        raise ValueError("temporal windows need a lookback >= 2 and named channels")
    if base_interval_ms is not None and base_interval_ms <= 0:
        raise ValueError("base_interval_ms must be positive")
    by_symbol: dict[str, list[tuple[int, Mapping[str, Any]]]] = defaultdict(list)
    for index, row in enumerate(rows):
        row_interval = row.get("baseIntervalMillis", base_interval_ms)
        if row_interval is None:
            raise ValueError("temporal rows must declare baseIntervalMillis")
        if int(row_interval) <= 0:
            raise ValueError("temporal row baseIntervalMillis must be positive")
        if base_interval_ms is not None and int(row_interval) != base_interval_ms:
            raise ValueError("temporal row interval differs from base_interval_ms")
        if row.get("continuitySegmentId") is None:
            raise ValueError("temporal rows must declare continuitySegmentId")
        by_symbol[str(row["symbol"])].append((index, row))
    tensors: list[list[list[float]]] = []
    indices: list[int] = []
    for values in by_symbol.values():
        values.sort(key=lambda item: int(item[1]["decisionTimeEpochMillis"]))
        decision_times = [int(item[1]["decisionTimeEpochMillis"]) for item in values]
        if len(decision_times) != len(set(decision_times)):
            raise ValueError("temporal rows contain a duplicate symbol decision time")
        for end in range(lookback - 1, len(values)):
            window = values[end - lookback + 1 : end + 1]
            interval = int(window[0][1].get("baseIntervalMillis", base_interval_ms))
            if any(
                int(item[1].get("baseIntervalMillis", base_interval_ms)) != interval
                for item in window
            ):
                continue
            times = [int(item[1]["decisionTimeEpochMillis"]) for item in window]
            if any(right != left + interval for left, right in zip(times, times[1:])):
                continue
            segments = {item[1]["continuitySegmentId"] for item in window}
            if len(segments) != 1:
                continue
            tensors.append([
                [float(item[1]["features"][feature]) for feature in feature_names]
                for item in window
            ])
            indices.append(values[end][0])
    order = sorted(range(len(indices)), key=indices.__getitem__)
    return [tensors[index] for index in order], [indices[index] for index in order]


def resample_bars(bars: Sequence[MarketBar], target_interval_ms: int) -> list[MarketBar]:
    """Causally aggregate a finer regular OHLCV grid to ``target_interval_ms``.

    Only standard bar fields have an unambiguous aggregation. Arbitrary
    point-in-time fields are rejected instead of silently applying a semantic
    guess. Incomplete buckets are quarantined by omission.
    """

    if target_interval_ms <= 0:
        raise ValueError("target_interval_ms must be positive")
    if not bars:
        return []
    symbols = {bar.symbol for bar in bars}
    if len(symbols) != 1:
        raise ValueError("resample_bars accepts exactly one symbol")
    if len(bars) == 1:
        return list(bars)

    differences = [
        right.event_time_ms - left.event_time_ms
        for left, right in zip(bars, bars[1:])
    ]
    if any(value <= 0 for value in differences):
        raise ValueError("bar event times must be strictly increasing")
    source_interval_ms = min(differences)
    if source_interval_ms > target_interval_ms:
        raise ValueError("source bars are coarser than the requested base interval")
    if source_interval_ms == target_interval_ms:
        durations = {
            bar.available_time_ms - bar.event_time_ms for bar in bars
        }
        if not any(bar.fields for bar in bars) and durations not in (
            {0},
            {target_interval_ms},
        ):
            raise ValueError(
                "bar duration conflicts with the requested base interval; "
                "the source may contain systematically missing finer bars"
            )
        return list(bars)
    if any(bar.fields for bar in bars):
        raise ValueError("bars with arbitrary fields require explicit aggregation semantics")
    if target_interval_ms % source_interval_ms:
        raise ValueError("source interval must divide the requested base interval exactly")
    source_durations = {
        bar.available_time_ms - bar.event_time_ms for bar in bars
    }
    if source_durations != {source_interval_ms}:
        raise ValueError(
            "finer-bar interval is ambiguous: availability duration must equal "
            "the inferred event interval"
        )

    expected_count = target_interval_ms // source_interval_ms
    buckets: dict[int, list[MarketBar]] = defaultdict(list)
    for bar in bars:
        bucket = bar.event_time_ms - (bar.event_time_ms % target_interval_ms)
        buckets[bucket].append(bar)

    result: list[MarketBar] = []
    for bucket, members in sorted(buckets.items()):
        expected_times = tuple(
            bucket + position * source_interval_ms
            for position in range(expected_count)
        )
        actual_times = tuple(bar.event_time_ms for bar in members)
        if actual_times != expected_times:
            continue
        result.append(_aggregate_bucket(members, bucket))
    return result


def _aggregate_bucket(members: Sequence[MarketBar], event_time_ms: int) -> MarketBar:
    return MarketBar(
        symbol=members[0].symbol,
        event_time_ms=event_time_ms,
        available_time_ms=max(bar.available_time_ms for bar in members),
        open=_first_optional(members, "open"),
        high=_extreme_optional(members, "high", max),
        low=_extreme_optional(members, "low", min),
        close=members[-1].close,
        quote_volume=_sum_optional(members, "quote_volume"),
        trade_count=_sum_optional_int(members, "trade_count"),
        taker_buy_quote_volume=_sum_optional(members, "taker_buy_quote_volume"),
        fields=None,
    )


def _optional_values(bars: Sequence[MarketBar], field: str) -> list[Any]:
    values = [getattr(bar, field) for bar in bars]
    present = [value for value in values if value is not None]
    if present and len(present) != len(values):
        raise ValueError(f"cannot aggregate partially missing {field}")
    return present


def _first_optional(bars: Sequence[MarketBar], field: str) -> float | None:
    values = _optional_values(bars, field)
    return None if not values else float(values[0])


def _extreme_optional(
    bars: Sequence[MarketBar], field: str, operation: Any
) -> float | None:
    values = _optional_values(bars, field)
    return None if not values else float(operation(values))


def _sum_optional(bars: Sequence[MarketBar], field: str) -> float | None:
    values = _optional_values(bars, field)
    return None if not values else math.fsum(float(value) for value in values)


def _sum_optional_int(bars: Sequence[MarketBar], field: str) -> int | None:
    values = _optional_values(bars, field)
    return None if not values else sum(int(value) for value in values)


def _features(bar: MarketBar, returns: Sequence[float], volumes: Sequence[float]) -> dict[str, float]:
    latest = returns[-1]
    recent = list(returns)
    variance = sum(value * value for value in recent) / len(recent)
    total_volume = sum(volumes)
    features = {
        "latest_return": latest,
        "mean_return": sum(recent) / len(recent),
        "realized_variance": variance,
        "log_quote_volume": math.log1p(max(0.0, total_volume)),
        "volume_change": (volumes[-1] / volumes[-2] - 1.0) if volumes[-2] > 0 else 0.0,
    }
    if bar.high is not None and bar.low is not None and bar.low > 0:
        features["log_range"] = math.log(bar.high / bar.low)
    if bar.quote_volume and bar.taker_buy_quote_volume is not None:
        features["taker_imbalance"] = 2.0 * bar.taker_buy_quote_volume / bar.quote_volume - 1.0
    if bar.trade_count is not None:
        features["log_trade_count"] = math.log1p(max(0, bar.trade_count))
    features.update(bar.fields or {})
    if not all(math.isfinite(value) for value in features.values()):
        raise ValueError(f"non-finite feature for {bar.symbol} at {bar.event_time_ms}")
    return features


def _members_at(time_ms: int, memberships: Mapping[int, Sequence[str]], keys: Sequence[int]) -> set[str]:
    eligible = [key for key in keys if key <= time_ms]
    return set(memberships[max(eligible)]) if eligible else set()


def _exact_steps(horizon_ms: int, interval_ms: int) -> int:
    if horizon_ms % interval_ms:
        raise ValueError("every horizon must be an exact multiple of the base interval")
    return horizon_ms // interval_ms


def _integer(value: Mapping[str, Any], *names: str, default: int | None = None) -> int:
    for name in names:
        if value.get(name) is not None:
            return int(value[name])
    if default is None:
        raise ValueError(f"missing one of {names}")
    return default


def _number(value: Any) -> float | None:
    return None if value is None else float(value)


def _optional_int(value: Any) -> int | None:
    return None if value is None else int(value)
