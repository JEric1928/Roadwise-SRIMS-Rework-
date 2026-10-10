FROM python:3.10-slim

WORKDIR /app

# Install system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install python dependencies
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy application files and model
COPY . .

# Supply-chain hardening: keep the ONNX model outside user-writable paths and
# run as a non-root user. Set MODEL_SHA256 at deploy time to enable checksum
# verification of the model file at startup (fail-closed on mismatch).
RUN chown -R root:root /app && chmod -R a-w /app/*.onnx || true
USER nobody

# Hugging Face Spaces listens on port 7860
EXPOSE 7860

# Request body ceiling enforced at the app layer (streamed read); uvicorn
# limits bound connection handling resources.
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "7860", "--limit-concurrency", "32", "--backlog", "64", "--timeout-keep-alive", "5"]