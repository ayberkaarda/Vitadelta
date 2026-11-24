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


def test_openapi_served_under_the_base_path() -> None:
    response = client.get("/internal/v1/openapi.json")
    assert response.status_code == 200
    document = response.json()
    assert document["servers"] == [{"url": "/internal/v1"}]
    assert sorted(document["paths"]) == ["/compare", "/health", "/score"]
    operation = document["paths"]["/health"]["get"]
    assert operation["operationId"] == "getHealth"
    assert operation["tags"] == ["health"]


def test_docs_uis_disabled() -> None:
    assert client.get("/docs").status_code == 404
    assert client.get("/redoc").status_code == 404


def test_utility_ceiling_below_one_rejected() -> None:
    with pytest.raises(ValidationError):
        EngineConfig(utility_ceiling=0.99)


@pytest.mark.parametrize(
    "field", ["diminishing_beta", "comparison_epsilon", "verdict_worth_it_max_ratio"]
)
def test_non_positive_config_values_rejected(field: str) -> None:
    with pytest.raises(ValidationError):
        EngineConfig.model_validate({field: 0})


def test_health_is_json_with_a_request_id() -> None:
    response = client.get("/internal/v1/health", headers={"X-Request-Id": "health-check-1"})
    assert response.headers["content-type"] == "application/json"
    assert response.headers["x-request-id"] == "health-check-1"


def test_config_is_immutable() -> None:
    config = EngineConfig()
    with pytest.raises(ValidationError):
        config.diminishing_beta = 0.5  # type: ignore[misc]
