"""The generated OpenAPI document is semantically equal to the contract (spec 9.2).

Both documents are normalised before comparison: ``$ref`` and ``allOf`` are resolved,
nullable unions (``type: [x, 'null']`` and ``anyOf: [x, {type: 'null'}]``) are unified,
``const`` is treated as a one-value ``enum``, and annotations that do not change the meaning
of a schema (titles, descriptions, defaults, examples) are ignored. Paths, operations,
parameters, request bodies, responses, headers, schemas, required fields, enums, numeric
and string constraints and the ``x-money`` markers are compared.

Responses of the running service are also validated against the contract schemas.
"""

from functools import cache
from pathlib import Path
from typing import Any

import pytest
import yaml
from jsonschema import Draft202012Validator
from referencing import Registry, Resource
from referencing.jsonschema import DRAFT202012

from builders import (
    client,
    compare_payload,
    compare_request,
    gold_a,
    gold_b,
    score_payload,
    score_request,
)
from vitadelta_scoring.engine.model import RankingMode

Json = dict[str, Any]

CONTRACT = Path(__file__).resolve().parents[3] / "contracts" / "scoring-api.openapi.yaml"
ANNOTATIONS = frozenset(
    {"title", "description", "default", "examples", "example", "summary", "deprecated"}
)
METHODS = ("get", "put", "post", "delete", "patch", "options", "head")


@cache
def contract() -> Json:
    with CONTRACT.open(encoding="utf-8") as handle:
        document: Json = yaml.safe_load(handle)
    return document


@cache
def generated() -> Json:
    response = client.get("/internal/v1/openapi.json")
    assert response.status_code == 200
    document: Json = response.json()
    return document


def resolve(document: Json, schema: Any) -> Any:
    while isinstance(schema, dict) and "$ref" in schema:
        pointer = schema["$ref"]
        assert pointer.startswith("#/"), pointer
        target: Any = document
        for part in pointer[2:].split("/"):
            target = target[part]
        siblings = {k: v for k, v in schema.items() if k != "$ref"}
        schema = {**target, **siblings}
    return schema


def normalise(document: Json, schema: Any) -> Any:
    """Canonical, comparable form of a JSON schema."""
    schema = resolve(document, schema)
    if isinstance(schema, bool):
        return schema
    assert isinstance(schema, dict), schema
    schema = dict(schema)

    merged: Json = {}
    for part in schema.pop("allOf", []):
        for key, value in normalise(document, part).items():
            assert key not in merged or merged[key] == value, f"allOf conflict on {key}"
            merged[key] = value

    any_of = schema.pop("anyOf", None)
    if any_of is not None:
        parts = [normalise(document, p) for p in any_of]
        nulls = [p for p in parts if p == {"type": ("null",)}]
        others = [p for p in parts if p != {"type": ("null",)}]
        if nulls and len(others) == 1:
            nullable = dict(others[0])
            nullable["type"] = tuple(sorted({*nullable.get("type", ()), "null"}))
            merged.update(nullable)
        else:
            merged["anyOf"] = sorted(repr(p) for p in parts)

    result: Json = dict(merged)
    for key, value in schema.items():
        if key in ANNOTATIONS:
            continue
        if key == "type":
            result["type"] = tuple(
                sorted({*result.get("type", ()), *([value] if isinstance(value, str) else value)})
            )
        elif key in ("enum", "const"):
            values = value if key == "enum" else [value]
            result["enum"] = tuple(sorted(str(v) for v in values))
        elif key == "properties":
            result["properties"] = {
                name: normalise(document, sub) for name, sub in sorted(value.items())
            }
        elif key in ("items", "propertyNames", "not"):
            result[key] = normalise(document, value)
        elif key == "additionalProperties":
            result[key] = value if isinstance(value, bool) else normalise(document, value)
        elif key == "required":
            result["required"] = tuple(sorted(value))
        elif key == "dependentRequired":
            result[key] = {k: tuple(sorted(v)) for k, v in sorted(value.items())}
        elif isinstance(value, (int, float)) and not isinstance(value, bool):
            result[key] = float(value)
        else:
            result[key] = value
    if result.get("additionalProperties") is True:
        del result["additionalProperties"]
    return result


def differences(expected: Any, actual: Any, path: str = "$") -> list[str]:
    if isinstance(expected, dict) and isinstance(actual, dict):
        found = []
        for key in sorted(expected.keys() | actual.keys(), key=str):
            if key not in actual:
                found.append(f"{path}.{key}: missing in the generated document")
            elif key not in expected:
                found.append(f"{path}.{key}: not in the contract")
            else:
                found.extend(differences(expected[key], actual[key], f"{path}.{key}"))
        return found
    if expected != actual:
        return [f"{path}: contract {expected!r} != generated {actual!r}"]
    return []


def operation_view(document: Json, operation: Json) -> Json:
    view: Json = {
        "operationId": operation.get("operationId"),
        "tags": tuple(operation.get("tags", ())),
        "parameters": {},
        "responses": {},
    }
    for parameter in operation.get("parameters", []):
        parameter = resolve(document, parameter)
        view["parameters"][(parameter["in"], parameter["name"])] = {
            "required": parameter.get("required", False),
            "schema": normalise(document, parameter["schema"]),
        }
    body = operation.get("requestBody")
    if body is not None:
        body = resolve(document, body)
        view["requestBody"] = {
            "required": body.get("required", False),
            "content": {
                media: normalise(document, spec["schema"])
                for media, spec in sorted(body["content"].items())
            },
        }
    for status, response in sorted(operation.get("responses", {}).items()):
        response = resolve(document, response)
        view["responses"][str(status)] = {
            "content": {
                media: normalise(document, spec["schema"])
                for media, spec in sorted(response.get("content", {}).items())
            },
            "headers": {
                name: {
                    "required": resolve(document, header).get("required", False),
                    "schema": normalise(document, resolve(document, header)["schema"]),
                }
                for name, header in sorted(response.get("headers", {}).items())
            },
        }
    return view


def test_servers_and_paths_match() -> None:
    expected, actual = contract(), generated()
    assert [s["url"] for s in actual["servers"]] == [s["url"] for s in expected["servers"]]
    assert sorted(actual["paths"]) == sorted(expected["paths"])
    for path, operations in expected["paths"].items():
        assert sorted(m for m in operations if m in METHODS) == sorted(
            m for m in actual["paths"][path] if m in METHODS
        ), path


@pytest.mark.parametrize(
    ("path", "method"), [("/health", "get"), ("/score", "post"), ("/compare", "post")]
)
def test_operations_are_semantically_equal(path: str, method: str) -> None:
    expected = operation_view(contract(), contract()["paths"][path][method])
    actual = operation_view(generated(), generated()["paths"][path][method])
    assert differences(expected, actual) == []


def _is_structural(schema: Json) -> bool:
    return schema.get("type") == "object" or "enum" in schema


def test_component_schemas_are_semantically_equal() -> None:
    expected_schemas = contract()["components"]["schemas"]
    actual_schemas = generated()["components"]["schemas"]
    # Pure string aliases (NutrientCode, FormCode, ProductId, CurrencyCode) are inlined by
    # the generator; their constraints are compared wherever they are used.
    structural = {name for name, schema in expected_schemas.items() if _is_structural(schema)}
    assert sorted(actual_schemas) == sorted(structural)
    problems = []
    for name in sorted(structural):
        problems.extend(
            differences(
                normalise(contract(), expected_schemas[name]),
                normalise(generated(), actual_schemas[name]),
                name,
            )
        )
    assert problems == []


def test_normalisation_detects_differences() -> None:
    """Guards the comparison itself: a changed enum, bound or requirement is reported."""
    document: Json = {"components": {"schemas": {}}}
    base = {"type": "object", "required": ["a"], "properties": {"a": {"enum": ["X", "Y"]}}}
    changed_enum = {"type": "object", "required": ["a"], "properties": {"a": {"enum": ["X"]}}}
    changed_required = {"type": "object", "properties": {"a": {"enum": ["X", "Y"]}}}
    bound = {"type": "number", "minimum": 0}
    assert differences(normalise(document, base), normalise(document, changed_enum))
    assert differences(normalise(document, base), normalise(document, changed_required))
    assert differences(normalise(document, bound), normalise(document, {"type": "number"}))
    nullable = normalise(document, {"type": ["number", "null"], "minimum": 0})
    union = normalise(document, {"anyOf": [{"type": "number", "minimum": 0}, {"type": "null"}]})
    assert nullable == union


# --- Live responses validate against the contract -------------------------------------------


@cache
def _registry() -> Registry[Any]:
    resource: Resource[Any] = Resource.from_contents(contract(), default_specification=DRAFT202012)
    registry: Registry[Any] = Registry().with_resource("urn:contract", resource)
    return registry


def validator(component: str) -> Draft202012Validator:
    schema = {"$ref": f"urn:contract#/components/schemas/{component}"}
    return Draft202012Validator(
        schema, registry=_registry(), format_checker=Draft202012Validator.FORMAT_CHECKER
    )


def assert_valid(component: str, instance: Any) -> None:
    errors = [
        f"{list(e.absolute_path)}: {e.message}" for e in validator(component).iter_errors(instance)
    ]
    assert errors == []


@pytest.mark.parametrize("mode", list(RankingMode))
def test_score_responses_follow_the_contract(mode: RankingMode) -> None:
    request = score_request([gold_a(), gold_b()], mode=mode)
    payload = score_payload(request)
    assert_valid("ScoreRequest", payload)
    response = client.post("/internal/v1/score", json=payload)
    assert response.status_code == 200
    assert_valid("ScoreResponse", response.json())


def test_compare_responses_follow_the_contract() -> None:
    payload = compare_payload(compare_request(gold_b(), gold_a()))
    assert_valid("CompareRequest", payload)
    response = client.post("/internal/v1/compare", json=payload)
    assert_valid("CompareResponse", response.json())


def test_health_and_problem_responses_follow_the_contract() -> None:
    assert_valid("HealthResponse", client.get("/internal/v1/health").json())
    invalid = client.post("/internal/v1/score", json={"nutrients": []})
    assert invalid.status_code == 422
    assert_valid("Problem", invalid.json())


def test_contract_examples_are_reproduced() -> None:
    examples = contract()["components"]["examples"]
    pairs = [
        ("score", "Gt1ScoreRequest", "Gt1ScoreResponse"),
        ("compare", "Gt1CompareRequest", "Gt1CompareResponse"),
    ]
    for path, request_name, response_name in pairs:
        response = client.post(f"/internal/v1/{path}", json=examples[request_name]["value"])
        assert response.status_code == 200, response.text
        assert response.json() == examples[response_name]["value"]
