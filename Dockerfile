FROM python:3.11-slim

# Install system dependencies for building PyNaCl / cryptography
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    libffi-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Create unprivileged application user
RUN groupadd -r pact && useradd -r -g pact -d /app pact

WORKDIR /app

# Install Python dependencies globally
COPY backend/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt

# Copy backend application, pact protocol rules, dao helpers, and dashboard assets
COPY backend/ ./backend/
COPY pact/ ./pact/
COPY dao/ ./dao/
COPY dashboard/ ./dashboard/

# Setup permissions for SQLite data volume and app files
RUN mkdir -p /app/backend/data && chown -R pact:pact /app

USER pact

ENV PYTHONPATH=/app
ENV PYTHONUNBUFFERED=1

VOLUME ["/app/backend/data"]

EXPOSE 8000

HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
    CMD python3 -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8000/api/health/status')" || exit 1

CMD ["python3", "-m", "uvicorn", "backend.main:app", "--host", "0.0.0.0", "--port", "8000"]
