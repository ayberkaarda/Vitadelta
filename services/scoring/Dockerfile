FROM ghcr.io/astral-sh/uv:0.13.9 AS uv

FROM python:3.14.10-slim-trixie

COPY --from=uv /uv /usr/local/bin/uv

ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy \
    UV_PYTHON_DOWNLOADS=never \
    PYTHONUNBUFFERED=1 \
    PATH="/app/.venv/bin:$PATH"

RUN groupadd --system --gid 10001 app \
    && useradd --system --uid 10001 --gid app --no-create-home app

WORKDIR /app
COPY pyproject.toml uv.lock .python-version ./
COPY src ./src
RUN uv sync --locked --no-dev

USER app
EXPOSE 8000
CMD ["uvicorn", "vitadelta_scoring.main:app", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
