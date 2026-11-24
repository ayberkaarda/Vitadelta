"""Immutable engine configuration."""

from pydantic import BaseModel, ConfigDict, Field


class EngineConfig(BaseModel):
    """Tunable constants of the scoring engine."""

    model_config = ConfigDict(frozen=True)

    diminishing_beta: float = 0.35
    utility_ceiling: float = Field(default=1.5, ge=1)
    comparison_epsilon: float = 1e-9
    verdict_worth_it_max_ratio: float = 1.0
    verdict_marginal_max_ratio: float = 1.5


DEFAULT_CONFIG = EngineConfig()
