# Multi-stage production build for PACT backend
FROM python:3.11-slim as builder

# Install build dependencies for PyNaCl and C-extensions
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libffi-dev \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# Build wheels for dependencies
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir --user -r requirements.txt


# Final slim runtime image
FROM python:3.11-slim

# Create unprivileged application user
RUN groupadd -r pact && useradd -r -g pact -d /app pact

WORKDIR /app

# Copy installed python packages from builder
COPY --from=builder /root/.local /home/pact/.local
ENV PATH=/home/pact/.local/bin:$PATH
ENV PYTHONPATH=/app
ENV PYTHONUNBUFFERED=1

# Copy backend application, pact protocol rules, and dashboard static assets
COPY backend/ ./backend/
COPY pact/ ./pact/
COPY dashboard/ ./dashboard/

# Create and set permissions for SQLite data directory
RUN mkdir -p /app/backend/data && chown -R pact:pact /app

USER pact

VOLUME ["/app/backend/data"]

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health/status')" || exit 1

CMD ["uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8000"]
