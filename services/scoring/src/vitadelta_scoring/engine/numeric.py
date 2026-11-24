"""Numerical helpers shared by the engine (spec 6.3, 6.7, 6.8, 6.12).

All arithmetic is IEEE-754 double precision; nothing here uses ``Decimal``.
"""

import math
from collections.abc import Sequence

from vitadelta_scoring.engine.validation import EngineValidationError, Issue

SORT_KEY_DECIMALS = 10


def eq(x: float, y: float, epsilon: float) -> bool:
    """Epsilon rule: ``|x - y| <= epsilon * max(1, |x|, |y|)``."""
    return abs(x - y) <= epsilon * max(1.0, abs(x), abs(y))


def is_positive(x: float, epsilon: float) -> bool:
    """``x > 0`` and not equal to 0 under the epsilon rule."""
    return x > 0.0 and not eq(x, 0.0, epsilon)


def snap_zero(x: float, epsilon: float) -> float:
    """Return exactly ``0.0`` when ``x`` equals 0 under the epsilon rule."""
    return 0.0 if eq(x, 0.0, epsilon) else x


def median(values: Sequence[float]) -> float:
    """Middle value for an odd count, mean of the two middle values for an even count.

    The mean is overflow-safe: when ``a + b`` is not finite, ``a / 2 + b / 2`` is used.
    """
    ordered = sorted(values)
    count = len(ordered)
    if count == 0:
        raise ValueError("median of an empty sequence")
    middle = count // 2
    if count % 2:
        return float(ordered[middle])
    low, high = ordered[middle - 1], ordered[middle]
    total = low + high
    if math.isfinite(total):
        return total / 2.0
    return low / 2.0 + high / 2.0


def require_finite(value: float, field: str, what: str) -> float:
    """Reject a derived value that cannot be represented as a finite double."""
    if not math.isfinite(value):
        raise EngineValidationError(
            [Issue(field, "value_out_of_range", f"{what} is not representable as a finite number.")]
        )
    return value


def sort_key(x: float) -> float:
    """Numeric sort keys are rounded to 10 decimals so that noise falls through to tie-breaks."""
    return round(x, SORT_KEY_DECIMALS)
