"""Pure, deterministic and stateless scoring engine (no HTTP concerns).

Entry points: :func:`score` (spec 6.1-6.7, 6.9-6.10) and :func:`compare` (spec 6.8).
"""

from vitadelta_scoring.engine.compare import compare
from vitadelta_scoring.engine.scoring import score
from vitadelta_scoring.engine.validation import EngineValidationError, Issue

__all__ = ["EngineValidationError", "Issue", "compare", "score"]
