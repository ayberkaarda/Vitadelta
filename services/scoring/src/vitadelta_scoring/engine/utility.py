"""Coverage ratio and utility curve (spec 6.4)."""

import math

PRESENCE_UTILITY = 0.5
PRESENCE_FLOOR_FRACTION_DIVISOR = 10.0


def utility(r: float, beta: float, u_max: float) -> tuple[float, bool]:
    """Return ``(u(r), capped_at_u_max)``.

    ``u(r) = r`` for ``0 <= r <= 1`` and ``min(1 + beta * ln(r), u_max)`` for ``r > 1``.
    """
    if r <= 1.0:
        return r, False
    raw = 1.0 + beta * math.log(r)
    if raw > u_max:
        return u_max, True
    return raw, False


def ceiling_ratio(beta: float, u_max: float) -> float:
    """Coverage ratio at which the ceiling is reached: ``exp((u_max - 1) / beta)``."""
    return math.exp((u_max - 1.0) / beta)


def presence_utility(amount_per_day: float, presence_floor_per_day: float | None) -> float:
    """Fixed PRESENCE_ONLY utility: 0.5, or 0 below 10 % of a known presence floor."""
    if is_below_presence_floor(amount_per_day, presence_floor_per_day):
        return 0.0
    return PRESENCE_UTILITY


def is_below_presence_floor(amount_per_day: float, presence_floor_per_day: float | None) -> bool:
    if presence_floor_per_day is None:
        return False
    return amount_per_day < presence_floor_per_day / PRESENCE_FLOOR_FRACTION_DIVISOR
