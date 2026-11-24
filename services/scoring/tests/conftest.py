"""Shared test configuration: Hypothesis runs derandomised so every run is reproducible."""

from hypothesis import settings

settings.register_profile("deterministic", derandomize=True, database=None, print_blob=True)
settings.load_profile("deterministic")
