"""Tests of the SQL-concatenation guard of scripts/security-scan.sh."""

from __future__ import annotations

import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from check_sql_concat import check_java, check_python


def java(body: str) -> list[str]:
    source = "class Repo {\n" + body + "\n}\n"
    return [finding.reason for finding in check_java("Repo.java", source)]


def test_literal_concatenation_is_allowed() -> None:
    assert java('void a() { jdbc.sql("SELECT id FROM t" + " WHERE id = ?").param(id); }') == []


def test_text_block_is_allowed() -> None:
    assert java('void a() { jdbc.sql("""\n SELECT id FROM t WHERE id = ?\n """).param(id); }') == []


def test_constant_of_literals_is_allowed() -> None:
    body = (
        'private static final String COLUMNS = "id, name";\n'
        'void a() { jdbc.sql("SELECT " + COLUMNS + " FROM t WHERE id = ?"); }'
    )
    assert java(body) == []


def test_local_built_from_literals_and_conditional_is_allowed() -> None:
    body = (
        "void a(String q) {\n"
        '  String where = q == null ? "" : " WHERE name ILIKE ?";\n'
        '  jdbc.sql("SELECT count(*) FROM t" + where);\n'
        "}"
    )
    assert java(body) == []


def test_concatenated_user_value_is_reported() -> None:
    assert java('void a(String name) { jdbc.sql("SELECT id FROM t WHERE name = \'" + name + "\'"); }') == [
        "sql(...): SQL built from a non-literal value"
    ]


def test_native_query_with_format_is_reported() -> None:
    body = 'void a(String t) { em.createNativeQuery(String.format("SELECT * FROM %s", t)); }'
    assert java(body) == [
        "createNativeQuery(...): SQL built with String.format/formatted/join/concat or a StringBuilder"
    ]


def test_formatted_text_block_is_reported() -> None:
    body = 'void a(String t) { jdbc.update("DELETE FROM %s WHERE id = ?".formatted(t), id); }'
    assert java(body) == [
        "update(...): SQL built with String.format/formatted/join/concat or a StringBuilder"
    ]


def test_reassigned_local_is_reported() -> None:
    body = (
        "void a(String extra) {\n"
        '  String sql = "SELECT id FROM t";\n'
        "  sql = sql + extra;\n"
        "  jdbc.sql(sql);\n"
        "}"
    )
    assert java(body) == ["sql(...): SQL built from a non-literal value"]


def test_identifier_assigned_from_a_parameter_is_reported() -> None:
    body = "void a(String input) {\n  String sql = input;\n  jdbc.sql(sql);\n}"
    assert java(body) == ["sql(...): SQL built from a non-literal value"]


def test_private_helper_is_checked_at_its_call_sites() -> None:
    safe = (
        'boolean a() { return call(c, "SELECT pg_try_advisory_lock(?)", 1L); }\n'
        "private static boolean call(Connection c, String sql, long key) { return c.prepareStatement(sql) != null; }"
    )
    unsafe = (
        'boolean a(String t) { return call(c, "SELECT * FROM " + t, 1L); }\n'
        "private static boolean call(Connection c, String sql, long key) { return c.prepareStatement(sql) != null; }"
    )
    assert java(safe) == []
    assert java(unsafe) == ["prepareStatement(...) through a parameter: SQL built from a non-literal value"]


def test_non_sql_calls_with_the_same_name_are_ignored() -> None:
    assert java('void a(int x) { counter.update("total" + x); template.execute(status -> 1); }') == []


def test_comments_are_ignored() -> None:
    assert java('// jdbc.sql("SELECT * FROM t WHERE a = " + a);\nvoid b() {}') == []


def python(source: str) -> list[str]:
    return [finding.reason for finding in check_python("module.py", source)]


def test_python_fstring_sql_is_reported() -> None:
    assert python('cursor.execute(f"SELECT * FROM t WHERE id = {value}")\n') == ["f-string SQL"]


def test_python_format_and_percent_sql_are_reported() -> None:
    source = 'a = "DELETE FROM t WHERE id = {}".format(x)\nb = "SELECT a FROM t WHERE b = %s" % (y,)\n'
    assert python(source) == ["SQL built with str.format", "SQL built with % interpolation"]


def test_python_plain_text_is_allowed() -> None:
    assert python('message = f"deleted {count} rows"\nquery = "SELECT a FROM t WHERE b = ?"\n') == []
