# Triplex CUDA call processor plus the local Chime browser-media gateway.
FROM nvidia/cuda:13.0.2-cudnn-runtime-ubuntu24.04 AS system

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        ca-certificates \
        curl \
        espeak-ng \
        gnupg \
        libsox-fmt-all \
        python3 \
        python3-pip \
        python3-venv \
        sox \
    && curl -fsSL https://dl.google.com/linux/linux_signing_key.pub \
        | gpg --dearmor -o /usr/share/keyrings/google-chrome.gpg \
    && echo "deb [arch=amd64 signed-by=/usr/share/keyrings/google-chrome.gpg] https://dl.google.com/linux/chrome/deb/ stable main" \
        > /etc/apt/sources.list.d/google-chrome.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends google-chrome-stable \
    && rm -rf /var/lib/apt/lists/*

FROM system AS builder

WORKDIR /build
COPY pyproject.toml README.md ./
COPY src ./src
RUN python3 -m venv /opt/triplex-build \
    && /opt/triplex-build/bin/pip install --no-cache-dir \
        build setuptools==83.0.0 wheel \
    && /opt/triplex-build/bin/python -m build \
        --wheel --no-isolation --outdir /dist

FROM system AS runtime

RUN useradd --create-home --uid 10001 triplex
WORKDIR /app

COPY requirements-linux-py312.lock ./
COPY --from=builder /dist/triplex-*.whl /tmp/triplex.whl
RUN python3 -m venv /opt/triplex \
    && /opt/triplex/bin/pip install --no-cache-dir --upgrade pip \
    && /opt/triplex/bin/pip install --no-cache-dir -r requirements-linux-py312.lock \
    && /opt/triplex/bin/pip install --no-cache-dir --no-deps /tmp/triplex.whl \
    && chown -R triplex:triplex /app /home/triplex

ENV PATH=/opt/triplex/bin:$PATH \
    PYTHONUNBUFFERED=1 \
    CHROME_EXECUTABLE=/usr/bin/google-chrome \
    MEETING_GATEWAY_URL=http://127.0.0.1:8765

USER triplex
HEALTHCHECK --interval=30s --timeout=5s --start-period=120s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8765/healthz', timeout=3)" || exit 1

CMD ["python", "-m", "triplex.runtime"]
