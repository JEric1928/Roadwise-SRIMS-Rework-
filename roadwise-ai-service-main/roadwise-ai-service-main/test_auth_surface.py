import os
import pytest
from fastapi.testclient import TestClient
import main

def test_health_check_payload_omits_sensitive_config():
    client = TestClient(main.app)

    response = client.get("/")
    assert response.status_code in (200, 503)
    data = response.json()
    assert "service" in data
    assert "model_loaded" in data
    assert "csjdm_profile" not in data
    assert "auth_configured" not in data

def test_api_key_authentication(monkeypatch):
    monkeypatch.setattr(main, "AI_SERVICE_API_KEY", "test-secret-key")
    client = TestClient(main.app)

    res_missing = client.post("/predict")
    assert res_missing.status_code in (401, 422)

    res_invalid = client.post("/predict", headers={"X-API-Key": "wrong-key"})
    assert res_invalid.status_code == 401
    assert res_invalid.json()["detail"] == "Invalid or missing X-API-Key."

def test_docs_disabled_in_production(monkeypatch):
    monkeypatch.setenv("AI_ENV", "production")
    import importlib
    importlib.reload(main)

    prod_client = TestClient(main.app)
    assert prod_client.get("/docs").status_code == 404
    assert prod_client.get("/redoc").status_code == 404
    assert prod_client.get("/openapi.json").status_code == 404

    monkeypatch.delenv("AI_ENV", raising=False)
    importlib.reload(main)
