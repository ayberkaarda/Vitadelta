"""Immutable engine configuration."""

from typing import Self

from pydantic import BaseModel, ConfigDict, Field, model_validator


class EngineConfig(BaseModel):
    """Tunable constants of the scoring engine.

    The defaults are the values of the project specification (sections 6.4 and 6.8). The
    health endpoint returns them unchanged; the calibration script evaluates variations.
    """

    # Every key is always present in the health payload, hence required in its schema.
    model_config = ConfigDict(
        frozen=True,
        extra="forbid",
        json_schema_extra={
            "required": [
                "diminishing_beta",
                "utility_ceiling",
                "comparison_epsilon",
                "verdict_worth_it_max_ratio",
                "verdict_marginal_max_ratio",
            ]
        },
    )

    diminishing_beta: float = Field(
        default=0.35,
        gt=0,
        description="Slope `beta` of the logarithmic segment of the utility curve.",
    )
    utility_ceiling: float = Field(default=1.5, ge=1, description="Utility ceiling `u_max`.")
    comparison_epsilon: float = Field(
        default=1e-9,
        gt=0,
        description="Relative tolerance of the epsilon equality rule.",
    )
    verdict_worth_it_max_ratio: float = Field(
        default=1.0,
        gt=0,
        description="Largest `ratio` that still yields `WORTH_IT`.",
    )
    verdict_marginal_max_ratio: float = Field(
        default=1.5,
        gt=0,
        description="Largest `ratio` that still yields `MARGINAL`.",
    )

    @model_validator(mode="after")
    def _thresholds_are_ordered(self) -> Self:
        if self.verdict_marginal_max_ratio < self.verdict_worth_it_max_ratio:
            raise ValueError("verdict_marginal_max_ratio must be >= verdict_worth_it_max_ratio")
        return self


DEFAULT_CONFIG = EngineConfig()
