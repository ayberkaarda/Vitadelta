import pytest
from fastapi.testclient import TestClient
from pydantic import ValidationError

from vitadelta_scoring.config import EngineConfig
from vitadelta_scoring.main import app

client = TestClient(app)


def test_health_body() -> None:
    response = client.get("/internal/v1/health")
    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "config": {
            "diminishing_beta": 0.35,
            "utility_ceiling": 1.5,
            "comparison_epsilon": 1e-9,
            "verdict_worth_it_max_ratio": 1.0,
            "verdict_marginal_max_ratio": 1.5,
        },
    }


def test_openapi_served_with_only_health_path() -> None:
    response = client.get("/internal/v1/openapi.json")
    assert response.status_code == 200
    document = response.json()
    assert list(document["paths"]) == ["/internal/v1/health"]
    operation = document["paths"]["/internal/v1/health"]["get"]
    assert operation["operationId"] == "getHealth"
    assert operation["tags"] == ["health"]


def test_docs_uis_disabled() -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404


def test_utility_ceiling_below_one_rejected() -> None:
    with pytest.raises(ValidationError):
        EngineConfig(utility_ceiling=0.99)


def test_config_is_immutable() -> None:
    config = EngineConfig()
    with pytest.raises(ValidationError):
        config.diminishing_beta = 0.5  # type: ignore[misc]
