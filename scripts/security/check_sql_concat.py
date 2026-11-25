"""Fails on SQL statements built from strings at run time (project specification section 15.3).

Java (services/api): the SQL argument of a JPA or JDBC call (createNativeQuery, createQuery,
JdbcClient.sql, JdbcTemplate query/update/execute, prepareStatement, ...) must be made of string
literals only. Joining literals with "+" (as the formatter wraps long statements) is allowed;
"+" next to anything that is not a literal, String.format, .formatted, String.join, concat and
StringBuilder are findings. An argument that is a single identifier is resolved to its String
declaration in the same file and checked the same way.

Python (services/scoring): an f-string, a .format() call or a "%" interpolation on a string that
contains SQL is a finding.

Files are the tracked and untracked, not ignored files of the repository (git ls-files), so build
output and virtual environments are never scanned.

Usage: check_sql_concat.py [repository root]          exit 0 clean, 1 findings, 2 usage error
       check_sql_concat.py --files <file>...           checks the named files (used by the tests)
"""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path

JAVA_ROOTS = ("services/api/src/main/",)
PYTHON_ROOTS = ("services/scoring/",)

# Methods whose first argument is always SQL.
ALWAYS_SQL_METHODS = {"sql", "createNativeQuery", "prepareStatement", "prepareCall", "addBatch"}
# Methods whose first argument is SQL when it looks like SQL (names shared with other APIs).
MAYBE_SQL_METHODS = {
    "createQuery",
    "query",
    "queryForObject",
    "queryForList",
    "queryForMap",
    "queryForRowSet",
    "queryForStream",
    "update",
    "batchUpdate",
    "execute",
    "executeQuery",
    "executeUpdate",
}
CALL_PATTERN = re.compile(r"\b(" + "|".join(sorted(ALWAYS_SQL_METHODS | MAYBE_SQL_METHODS)) + r")\s*\(")
SQL_TEXT = re.compile(
    r"\b(SELECT\b.*\bFROM|INSERT\s+INTO|UPDATE\b.*\bSET|DELETE\s+FROM|MERGE\s+INTO|"
    r"CREATE\s+(TABLE|INDEX|VIEW)|DROP\s+(TABLE|INDEX|VIEW)|ALTER\s+TABLE|TRUNCATE)\b",
    re.IGNORECASE | re.DOTALL,
)
UNSAFE_CALLS = re.compile(
    r"\bString\s*\.\s*(format|join)\s*\(|\.formatted\s*\(|\.concat\s*\(|StringBuilder|StringBuffer"
)


@dataclass(frozen=True)
class Finding:
    path: str
    line: int
    reason: str


# ---------------------------------------------------------------------------------------------
# Java


def strip_java_comments(source: str) -> str:
    """Replaces comments by spaces (newlines kept), leaving string and char literals intact."""
    out: list[str] = []
    i, n = 0, len(source)
    while i < n:
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            end = n if end < 0 else end + 3
            out.append(source[i:end])
            i = end
        elif source[i] in "\"'":
            quote = source[i]
            j = i + 1
            while j < n and source[j] != quote and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            out.append(source[i : j + 1])
            i = j + 1
        elif source.startswith("//", i):
            end = source.find("\n", i)
            end = n if end < 0 else end
            out.append(" " * (end - i))
            i = end
        elif source.startswith("/*", i):
            end = source.find("*/", i + 2)
            end = n if end < 0 else end + 2
            out.append(re.sub(r"[^\n]", " ", source[i:end]))
            i = end
        else:
            out.append(source[i])
            i += 1
    return "".join(out)


def tokenize_java(expr: str) -> list[tuple[str, str]]:
    """Coarse tokens: ("str", literal), ("op", "+"), ("other", text)."""
    tokens: list[tuple[str, str]] = []
    i, n = 0, len(expr)
    while i < n:
        c = expr[i]
        if c.isspace():
            i += 1
        elif expr.startswith('"""', i):
            end = expr.find('"""', i + 3)
            end = n if end < 0 else end + 3
            tokens.append(("str", expr[i:end]))
            i = end
        elif c == '"':
            j = i + 1
            while j < n and expr[j] != '"':
                j += 2 if expr[j] == "\\" else 1
            tokens.append(("str", expr[i : j + 1]))
            i = j + 1
        elif c == "+":
            tokens.append(("op", "+"))
            i += 1
        else:
            j = i
            while j < n and not expr[j].isspace() and expr[j] not in '"+':
                j += 1
            tokens.append(("other", expr[i:j]))
            i = j
    return tokens


def first_argument(source: str, open_paren: int) -> str:
    """Text of the first argument of the call whose "(" is at open_paren."""
    depth = 0
    i, n = open_paren, len(source)
    start = open_paren + 1
    while i < n:
        if source.startswith('"""', i):
            end = source.find('"""', i + 3)
            i = n if end < 0 else end + 3
            continue
        c = source[i]
        if c in "\"'":
            j = i + 1
            while j < n and source[j] != c and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                return source[start:i]
        elif c == "," and depth == 1:
            return source[start:i]
        i += 1
    return source[start:]


def scan_top_level(expr: str) -> list[tuple[int, str]]:
    """Positions and characters of expr outside string literals and brackets."""
    out: list[tuple[int, str]] = []
    depth = 0
    i, n = 0, len(expr)
    while i < n:
        if expr.startswith('"""', i):
            end = expr.find('"""', i + 3)
            i = n if end < 0 else end + 3
            continue
        c = expr[i]
        if c in "\"'":
            j = i + 1
            while j < n and expr[j] != c:
                j += 2 if expr[j] == "\\" else 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif depth == 0:
            out.append((i, c))
        i += 1
    return out


def split_top_level(expr: str, separator: str) -> list[str]:
    cuts = [i for i, c in scan_top_level(expr) if c == separator]
    parts, last = [], 0
    for cut in cuts:
        parts.append(expr[last:cut])
        last = cut + 1
    parts.append(expr[last:])
    return parts


def string_declarations(source: str, name: str) -> list[str]:
    """Initializers of every "String <name> = ...;" declaration in the file."""
    found: list[str] = []
    for match in re.finditer(r"\bString\s+" + re.escape(name) + r"\s*=(?!=)\s*", source):
        rest = source[match.end() :]
        ends = [i for i, c in scan_top_level(rest) if c == ";"]
        found.append(rest[: ends[0]] if ends else rest)
    return found


def literal_only(expr: str, source: str, depth: int = 0) -> bool:
    """True when the expression can only evaluate to text written in this file.

    Allowed: string literals and text blocks joined with "+", a conditional whose branches are
    both literal-only, and identifiers of String variables or constants of the same file that are
    assigned exactly once, by their declaration, from a literal-only expression.
    """
    expr = expr.strip()
    if depth > 8 or not expr or UNSAFE_CALLS.search(expr):
        return False
    # One pair of parentheses around the whole expression is transparent.
    while expr.startswith("(") and expr.endswith(")") and not scan_top_level(expr):
        expr = expr[1:-1].strip()
    marks = scan_top_level(expr)
    question = next((i for i, c in marks if c == "?"), None)
    if question is not None:
        nested = 0
        for i, c in marks:
            if i <= question:
                continue
            if c == "?":
                nested += 1
            elif c == ":":
                if nested == 0:
                    return literal_only(expr[question + 1 : i], source, depth + 1) and literal_only(
                        expr[i + 1 :], source, depth + 1
                    )
                nested -= 1
        return False
    operands = split_top_level(expr, "+")
    if len(operands) > 1:
        return all(literal_only(operand, source, depth + 1) for operand in operands)
    if expr.startswith('"') and expr.endswith('"') and len(expr) >= 2:
        return True
    if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", expr):
        initializers = string_declarations(source, expr)
        assignments = len(re.findall(r"(?<![.\w])" + re.escape(expr) + r"\s*(\+=|=(?!=))", source))
        return (
            bool(initializers)
            and assignments == len(initializers)
            and all(literal_only(init, source, depth + 1) for init in initializers)
        )
    return False


def expression_problem(expr: str, source: str = "") -> str | None:
    """Reason why the SQL expression is built at run time, or None when it is literal-only."""
    if literal_only(expr, source):
        return None
    if UNSAFE_CALLS.search(expr):
        return "SQL built with String.format/formatted/join/concat or a StringBuilder"
    return "SQL built from a non-literal value"


LAMBDA = re.compile(r"^(\(?\s*[\w\s,]*\)?\s*->|[\w.]+::\w+$)")
PRIVATE_METHOD = re.compile(r"\bprivate\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],\s]+?\s(\w+)\s*\(([^)]*)\)")


def call_arguments(source: str, open_paren: int) -> list[str]:
    """Top-level arguments of the call whose "(" is at open_paren."""
    depth, i, n = 0, open_paren, len(source)
    end = None
    while i < n:
        if source.startswith('"""', i):
            close = source.find('"""', i + 3)
            i = n if close < 0 else close + 3
            continue
        c = source[i]
        if c in "\"'":
            j = i + 1
            while j < n and source[j] != c and source[j] != "\n":
                j += 2 if source[j] == "\\" else 1
            i = j + 1
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
            if depth == 0:
                end = i
                break
        i += 1
    if end is None:
        return []
    inner = source[open_paren + 1 : end]
    return [part.strip() for part in split_top_level(inner, ",")] if inner.strip() else []


def parameter_arguments(source: str, position: int, name: str) -> list[tuple[int, str]] | None:
    """When name is a String parameter of the private method enclosing position, the values
    passed for it at every call site in the file, as (line, expression); otherwise None."""
    enclosing = None
    for method in PRIVATE_METHOD.finditer(source):
        if method.start() < position:
            enclosing = method
        else:
            break
    if enclosing is None:
        return None
    parameters = [p.strip() for p in enclosing.group(2).split(",")]
    index = next(
        (i for i, p in enumerate(parameters) if re.fullmatch(r"(final\s+)?String\s+" + re.escape(name), p)),
        None,
    )
    if index is None:
        return None
    values: list[tuple[int, str]] = []
    for call in re.finditer(r"(?<![\w.])" + re.escape(enclosing.group(1)) + r"\s*\(", source):
        if call.start() == enclosing.start(1):
            continue
        arguments = call_arguments(source, call.end() - 1)
        line = source.count("\n", 0, call.start()) + 1
        values.append((line, arguments[index] if index < len(arguments) else ""))
    return values


def looks_like_sql(expr: str) -> bool:
    return any(kind == "str" and SQL_TEXT.search(text) for kind, text in tokenize_java(expr))


def check_java(path: str, raw: str) -> list[Finding]:
    source = strip_java_comments(raw)
    findings: list[Finding] = []
    for match in CALL_PATTERN.finditer(source):
        method = match.group(1)
        argument = first_argument(source, match.end() - 1).strip()
        if not argument:
            continue
        if LAMBDA.match(argument):
            # A callback (for example TransactionTemplate.execute); the calls inside it are
            # checked on their own.
            continue
        expr = argument
        if re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", argument):
            declared = string_declarations(source, argument)
            if not declared:
                callers = parameter_arguments(source, match.start(), argument)
                if callers is None or not callers:
                    if method in ALWAYS_SQL_METHODS:
                        line = source.count("\n", 0, match.start()) + 1
                        findings.append(
                            Finding(path, line, f"{method}(...): SQL from a value not declared here")
                        )
                    continue
                for caller_line, value in callers:
                    if not literal_only(value, source):
                        findings.append(
                            Finding(
                                path,
                                caller_line,
                                f"{method}(...) through a parameter: SQL built from a non-literal value",
                            )
                        )
                continue
            expr = " ".join(declared)
        if method not in ALWAYS_SQL_METHODS and not looks_like_sql(expr):
            continue
        expr = argument
        reason = expression_problem(expr, source)
        if reason:
            line = source.count("\n", 0, match.start()) + 1
            findings.append(Finding(path, line, f"{method}(...): {reason}"))
    return findings


# ---------------------------------------------------------------------------------------------
# Python

PY_STRING = re.compile(
    r"(?P<prefix>(?<!\w)[rRbBuUfF]{0,2})(?P<quote>\"\"\"|'''|\"|')(?P<body>.*?)(?P=quote)",
    re.DOTALL,
)


def check_python(path: str, source: str) -> list[Finding]:
    findings: list[Finding] = []
    for match in PY_STRING.finditer(source):
        body = match.group("body")
        if not SQL_TEXT.search(body):
            continue
        prefix = match.group("prefix").lower()
        after = source[match.end() : match.end() + 40]
        reason = None
        if "f" in prefix:
            reason = "f-string SQL"
        elif re.match(r"\s*\.\s*format\s*\(", after):
            reason = "SQL built with str.format"
        elif re.match(r"\s*%\s*[\w(\[{]", after):
            reason = "SQL built with % interpolation"
        if reason:
            line = source.count("\n", 0, match.start()) + 1
            findings.append(Finding(path, line, reason))
    return findings


# ---------------------------------------------------------------------------------------------


def check_file(path: Path, display: str) -> list[Finding]:
    text = path.read_text(encoding="utf-8", errors="replace")
    if display.endswith(".java"):
        return check_java(display, text)
    if display.endswith(".py"):
        return check_python(display, text)
    return []


def repository_files(root: Path) -> list[str]:
    listed = subprocess.run(
        [
            "git",
            "ls-files",
            "-z",
            "--cached",
            "--others",
            "--exclude-standard",
            "--",
            *JAVA_ROOTS,
            *PYTHON_ROOTS,
        ],
        cwd=root,
        check=True,
        capture_output=True,
    ).stdout.decode("utf-8")
    names = sorted({name for name in listed.split("\0") if name})
    return [
        name
        for name in names
        if (name.endswith(".java") and name.startswith(JAVA_ROOTS))
        or (name.endswith(".py") and name.startswith(PYTHON_ROOTS))
    ]


def main(argv: list[str]) -> int:
    if argv[:1] == ["--files"]:
        targets = [(Path(name), name.replace("\\", "/")) for name in argv[1:]]
    elif len(argv) <= 1:
        root = Path(argv[0] if argv else ".").resolve()
        targets = [(root / name, name) for name in repository_files(root) if (root / name).is_file()]
    else:
        print(__doc__, file=sys.stderr)
        return 2
    findings: list[Finding] = []
    for path, display in targets:
        findings.extend(check_file(path, display))
    for finding in findings:
        print(f"{finding.path}:{finding.line}: {finding.reason}")
    print(f"checked {len(targets)} file(s), {len(findings)} finding(s)")
    return 1 if findings else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
