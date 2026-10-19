import hashlib
import logging
import io
import os
import numpy as np
import onnxruntime as ort
from PIL import Image
from fastapi import FastAPI, File, Form, UploadFile, HTTPException, Header, Depends
from fastapi.responses import JSONResponse
from collections import OrderedDict
import threading
from pydantic import BaseModel, Field
from typing import Optional, List, Dict

import forecasting_engines as fe
import validation as V
from csjdm_parameters import CSJDM_GEOMORPHIC_PROFILE, SoilType
import multi_hazard as mh

logger = logging.getLogger("roadwise.forecasting")

app = FastAPI(
    title="RoadWise AI & Advanced Mathematical Forecasting Service",
    version="2.0.0",
    description="ISO/IEC 25010 compliant analytical microservice hosting 5 continuous-time stochastic and differential forecasting engines."
)

# 1. Load ONNX Model on Startup (with supply-chain checksum verification)
MODEL_PATH = "rdd2022_d4_resnet38.onnx"
AI_ENV = os.environ.get("AI_ENV", "").strip().lower()

if AI_ENV == "production" and not os.environ.get("MODEL_SHA256", "").strip():
    # Fail closed in production: without a pinned digest the deployed artifact
    # is not verifiable, so predictions are refused until MODEL_SHA256 is set.
    print(">>> [AI ERROR] MODEL_SHA256 must be pinned in production; refusing to load the model.")
    session = None
    input_name = None
elif not V.verify_model_file(MODEL_PATH):
    print(f">>> [AI ERROR] Model checksum verification failed for '{MODEL_PATH}'; refusing to load.")
    session = None
    input_name = None
else:
    try:
        opts = ort.SessionOptions()
        opts.intra_op_num_threads = 1
        opts.inter_op_num_threads = 1
        session = ort.InferenceSession(MODEL_PATH, sess_options=opts, providers=['CPUExecutionProvider'])
        input_name = session.get_inputs()[0].name
        print(f">>> [AI READY] Model '{MODEL_PATH}' loaded successfully.")
    except Exception as e:
        print(f">>> [AI ERROR] Failed to load model: {e}")
        session = None
        input_name = None

# Service-to-service authentication. Fail closed when the shared secret is not
# configured: the service refuses to serve predictions rather than running open.
AI_SERVICE_API_KEY = os.environ.get("AI_SERVICE_API_KEY", "").strip()


def require_api_key(x_api_key: Optional[str] = Header(default=None)):
    if not AI_SERVICE_API_KEY:
        raise HTTPException(status_code=503,
                            detail="AI service authentication is not configured (AI_SERVICE_API_KEY missing).")
    if not x_api_key or x_api_key != AI_SERVICE_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid or missing X-API-Key.")

SEVERITY_LEVELS = ["Low", "Medium", "High"]

# Numeric class for each severity label, so aggregation is an integer max rather
# than string comparison. 1=Low, 2=Medium, 3=High; 0 means "not assessed".
SEVERITY_SCORES = {"Low": 1, "Medium": 2, "High": 3}

# One report carries at most three photos: close-up, wide angle, perpendicular.
MAX_REPORT_IMAGES = 3
DEFAULT_PERSPECTIVES = ("CLOSE_UP", "WIDE_ANGLE", "PERPENDICULAR")
CONFIDENCE_FLOOR = 0.40
MODEL_VERSION = "rdd2022-d4-resnet38-v2"
MAX_CACHE_SIZE = 1000

CACHE_LOCK = threading.Lock()
PREDICTION_CACHE: OrderedDict[str, dict] = OrderedDict()


def cache_get(key: str) -> Optional[dict]:
    with CACHE_LOCK:
        if key in PREDICTION_CACHE:
            PREDICTION_CACHE.move_to_end(key)
            return PREDICTION_CACHE[key]
        return None


def cache_set(key: str, value: dict):
    with CACHE_LOCK:
        if key in PREDICTION_CACHE:
            PREDICTION_CACHE.move_to_end(key)
        PREDICTION_CACHE[key] = value
        if len(PREDICTION_CACHE) > MAX_CACHE_SIZE:
            PREDICTION_CACHE.popitem(last=False)  # Evict LRU item


# ==============================================================================
# ROAD-SCENE GATE
# ==============================================================================
# validation.RoadSceneGate needs a pre-classifier embedding from the ONNX graph.
# The shipped rdd2022_d4_resnet38.onnx exports exactly one tensor (logits [B, 3]),
# so embed_to_vector() returns None and evaluate() returns REVIEW_REQUIRED
# unconditionally - wiring it directly would quarantine every upload. Modes let
# the gate be adopted without that cliff:
#   off     : heuristic prescreen only (pre-gate behaviour)
#   report  : run the gate, surface its verdict, never block publication (default)
#   enforce : gate verdicts are authoritative (needs a 2-output model + calibration)
ROAD_GATE_MODE = os.environ.get("ROAD_GATE_MODE", "report").strip().lower()
if ROAD_GATE_MODE not in ("off", "report", "enforce"):
    print(f">>> [ROAD GATE] Unknown ROAD_GATE_MODE='{ROAD_GATE_MODE}'; using 'report'.")
    ROAD_GATE_MODE = "report"

# Index of the embedding tensor within session.run() output. Must match the export
# contract documented in calibrate_road_gate.py ("export it with TWO outputs").
EMBEDDING_OUTPUT_INDEX = 1

ROAD_GATE = V.RoadSceneGate()

if ROAD_GATE_MODE != "off":
    if not ROAD_GATE.loaded:
        print(">>> [ROAD GATE] Calibration unavailable; the gate cannot decide and will "
              "report UNAVAILABLE. Do not set ROAD_GATE_MODE=enforce until "
              "road_calibration.npz ships alongside a 2-output model export.")
    print(f">>> [ROAD GATE] Mode={ROAD_GATE_MODE}, embedding output index={EMBEDDING_OUTPUT_INDEX}.")


def evaluate_road_gate(outputs) -> dict:
    """Return the road-gate verdict as a response-payload fragment.

    Never raises: an unusable gate degrades to a reported status rather than
    failing the prediction. Only 'enforce' mode may force manual review.
    """
    if ROAD_GATE_MODE == "off":
        return {"roadGate": "DISABLED"}

    embedding = ROAD_GATE.embed_to_vector(outputs, EMBEDDING_OUTPUT_INDEX)
    if not ROAD_GATE.loaded or embedding is None:
        # The gate cannot form an opinion. Say so, rather than passing off its
        # fail-closed REVIEW_REQUIRED verdict as if a review had actually happened.
        reason = ("road_calibration.npz not loaded" if not ROAD_GATE.loaded
                  else f"model exports no embedding at output index {EMBEDDING_OUTPUT_INDEX}")
        return {"roadGate": "UNAVAILABLE", "roadGateMessage": reason}

    result = ROAD_GATE.evaluate(embedding)

    payload = {"roadGate": result.decision}
    if result.road_confidence:
        payload["roadGateConfidence"] = round(float(result.road_confidence) * 100.0, 1)
    if result.mahalanobis_distance is not None:
        payload["roadGateDistance"] = round(float(result.mahalanobis_distance), 4)
    if result.decision != "ROAD_CONFIRMED" and result.message:
        payload["roadGateMessage"] = result.message
    return payload

# ==============================================================================
# REQUEST & RESPONSE PYDANTIC MODELS
# ==============================================================================
class PavementDecayRequest(BaseModel):
    road_id: str = Field(default="RD-001", description="Road Identifier")
    # Typed as the registered series rather than a free string: an unknown soil is
    # now a 422 at the boundary instead of a silent fallback to stable parameters.
    soil_type: SoilType = Field(default=SoilType.SIBUL_CLAY, description="Subgrade soil type")
    initial_pci: float = Field(default=100.0, ge=0.0, le=100.0)
    years_horizon: int = Field(default=10, ge=1, le=30)
    fractional_alpha: float = Field(default=0.35, ge=0.01, le=0.99)


class HydrologicalShockRequest(BaseModel):
    road_id: str = Field(default="RD-102")
    soil_type: SoilType = Field(default=SoilType.SIBUL_CLAY)
    # Upper bounds keep the engines from being driven into excessive allocation
    # or runtime by hostile inputs.
    rainfall_mm: float = Field(default=180.0, ge=0.0, le=2000.0)
    slope_degrees: float = Field(default=22.0, ge=0.0, le=90.0)
    drainage_capacity_mm: float = Field(default=50.0, ge=0.0, le=1000.0)


class DefectPropagationRequest(BaseModel):
    num_nodes: int = Field(default=10, ge=3, le=50)
    timesteps: int = Field(default=24, ge=1, le=120)
    diffusion_rate: float = Field(default=0.15, ge=0.0, le=1.0)
    growth_rate: float = Field(default=0.08, ge=0.0, le=1.0)


class FinancialRiskRequest(BaseModel):
    base_budget_php: float = Field(default=15000000.0, ge=100000.0)
    confidence_level: float = Field(default=0.95, ge=0.80, le=0.999)
    alpha_tail: float = Field(default=1.4, ge=1.01, le=2.0)
    beta_skew: float = Field(default=0.75, ge=-1.0, le=1.0)
    num_simulations: int = Field(default=5000, ge=500, le=20000)


class DataAssimilationRequest(BaseModel):
    vision_score: float = Field(default=0.65, ge=0.0, le=1.0)
    vision_confidence: float = Field(default=0.85, ge=0.0, le=1.0)
    sensor_telemetry_score: float = Field(default=0.40, ge=0.0, le=1.0)
    geomechanical_baseline: float = Field(default=0.50, ge=0.0, le=1.0)
    tradeoff_alpha: float = Field(default=0.5, ge=0.0, le=1.0)

class MultiHazardRequest(BaseModel):
    """Roadmap scaffold (§2.D): caller-supplied normalised hazard severities."""

    flood: float = Field(default=0.0, ge=0.0, le=1.0)
    landslide: float = Field(default=0.0, ge=0.0, le=1.0)
    seismic: float = Field(default=0.0, ge=0.0, le=1.0)
    erosion: float = Field(default=0.0, ge=0.0, le=1.0)



def _engine_error(engine: str, exc: Exception) -> HTTPException:
    """
    Map a forecasting failure onto a sanitised HTTPException.

    The engine endpoints previously interpolated ``str(exc)`` straight into the
    response detail, which leaked internal paths and stack detail to any caller
    holding the service key. Client-caused ``ValueError`` (e.g. an unregistered
    soil reaching the registry) still returns its message as a 4xx; everything
    else is logged with a traceback and reported opaquely.
    """
    if isinstance(exc, ValueError):
        logger.warning("%s rejected input: %s", engine, exc)
        return HTTPException(status_code=422, detail=str(exc))
    logger.exception("%s failed", engine)
    return HTTPException(status_code=500, detail="Internal forecasting processing error.")


# ==============================================================================
# API ENDPOINTS
# ==============================================================================
@app.get("/")
def health_check():
    payload = {
        "service": "RoadWise AI Inference & Mathematical Forecasting Engine",
        "model_loaded": session is not None,
        "auth_configured": bool(AI_SERVICE_API_KEY),
        "csjdm_profile": CSJDM_GEOMORPHIC_PROFILE
    }
    if session is None:
        payload["status"] = "degraded"
        # Model-aware readiness: a process that is up but cannot serve
        # predictions must not report healthy to orchestrators.
        return JSONResponse(status_code=503, content=payload)
    payload["status"] = "online"
    return payload


def _unassessed(status: str, message: str, image_quality: str = "UNKNOWN") -> dict:
    return {
        "severity": "Unassessed",
        "severityScore": 0,
        "confidence": 0.0,
        "status": status,
        "message": message,
        "modelVersion": MODEL_VERSION,
        "reviewRequired": True,
        "imageQuality": image_quality,
        # No class distribution was produced, so the Stage-1 gate cannot pass.
        "pMax": None,
        "entropy": None,
        "gatePassed": False,
        "gateReason": message,
    }


def _severity_score(label: str) -> int:
    """Map a severity label to its 1-3 class number; 0 means 'not assessed'."""
    return SEVERITY_SCORES.get(label, 0)


def _perspective_label(raw: Optional[str], index: int) -> str:
    """Normalize a client-supplied capture angle, falling back by position.

    Position is the fallback rather than a hard error: the widget uploads in
    close-up → wide → perpendicular order, so an older client that sends no
    perspective labels still produces a correctly labelled set.
    """
    if raw:
        normalized = raw.strip().upper().replace("-", "_").replace(" ", "_")
        if normalized in DEFAULT_PERSPECTIVES:
            return normalized
    if index < len(DEFAULT_PERSPECTIVES):
        return DEFAULT_PERSPECTIVES[index]
    return DEFAULT_PERSPECTIVES[-1]


async def _read_bounded(upload: UploadFile) -> bytes:
    """Read at most the size cap, so an oversized body is never fully buffered."""
    contents = await upload.read(V.MAX_FILE_SIZE_BYTES + 1)
    if contents is None or len(contents) == 0:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")
    if len(contents) > V.MAX_FILE_SIZE_BYTES:
        # Cap was hit before the full body was buffered in memory.
        raise HTTPException(status_code=413, detail="Uploaded file exceeds the maximum allowed size.")
    return contents


def infer_image(contents: bytes, declared_mime: Optional[str]) -> dict:
    """Run the layered single-image pipeline and return the response payload.

    Extracted from the original /predict handler so the single-image and
    multi-image endpoints cannot drift apart: both run the same five layers in the
    same order, and the multi-image path only adds Stage-1 gating and aggregation on
    top of what this returns.

    Adds four keys beyond the historic payload — ``severityScore`` (1-3, 0 when
    unassessed), ``pMax``, ``entropy`` and ``gatePassed`` — so callers can reason
    about the decisiveness gate without recomputing the distribution. Existing keys
    are unchanged.

    Raises HTTPException(400/413) for a body that cannot be read; every *content*
    rejection is returned as an ``Unassessed`` payload so the historic contract of
    /predict (HTTP 200 + status field) is preserved.
    """
    if session is None:
        raise HTTPException(status_code=503, detail="AI Model session is not initialized.")

    # Layer 1: file / security validation (size, MIME, magic bytes).
    err_status, err_message = V.validate_upload_bytes(contents, declared_mime)
    if err_status:
        return _unassessed(err_status, err_message, "REJECTED")

    img_hash = hashlib.sha256(contents).hexdigest()
    cached_res = cache_get(img_hash)
    if cached_res is not None:
        return cached_res

    # Layer 2: safe decode + structural / decompression-bomb checks.
    raw_image, err_status, err_message = V.safe_decode_image(contents)
    if raw_image is None:
        return _unassessed(err_status or "INVALID_INPUT", err_message or "Image rejected.", "REJECTED")

    # Layer 3: quality / road-scene heuristics.
    quality = V.prescreen_quality(raw_image)
    if not quality.ok:
        res = _unassessed(quality.status, quality.message, quality.image_quality)
        cache_set(img_hash, res)
        return res
    image_quality = quality.image_quality

    try:
        image_resized = raw_image.convert("RGB").resize((224, 224))
        img_np = np.array(image_resized, dtype=np.float32) / 255.0
        img_np = np.transpose(img_np, (2, 0, 1))
        img_np = np.expand_dims(img_np, axis=0)

        outputs = session.run(None, {input_name: img_np})

        # Layer 4: validate raw model outputs before interpreting them.
        logits, model_err = V.validate_model_outputs(outputs, expected_num_classes=len(SEVERITY_LEVELS))
        if model_err:
            res = _unassessed("MODEL_ERROR", model_err, image_quality)
            cache_set(img_hash, res)
            return res

        # Layer 5: road-scene gate. The verdict is always reported in the payload;
        # it only blocks publication in 'enforce' mode (see ROAD_GATE_MODE).
        road_gate = evaluate_road_gate(outputs)
        gate_decision = road_gate.get("roadGate")
        gate_blocks = (ROAD_GATE_MODE == "enforce"
                       and gate_decision not in ("ROAD_CONFIRMED", "DISABLED"))

        probs = V.softmax(np.asarray(logits, dtype=np.float64))
        max_idx = int(np.argmax(probs))
        top_confidence = float(probs[max_idx])
        predicted_severity = SEVERITY_LEVELS[max_idx]

        # Stage-1 decisiveness gate. Advisory here — /predict never rejects on it —
        # and enforced by /predict-multi, which is where a bad photo could otherwise
        # contaminate a multi-image severity aggregate.
        decisiveness = V.confidence_gate(probs)

        review_required = (top_confidence < 0.70 or image_quality == "LOW_QUALITY"
                           or V.margin_is_too_small(probs)
                           or gate_blocks)

        gate_fields = {
            "pMax": round(decisiveness.p_max, 4),
            "entropy": round(decisiveness.entropy, 4),
            "gatePassed": bool(decisiveness.passed),
            "gateReason": decisiveness.reason,
        }

        if top_confidence < CONFIDENCE_FLOOR:
            res = {
                "severity": "Unassessed",
                "severityScore": 0,
                "confidence": round(top_confidence * 100.0, 1),
                "status": "UNCERTAIN_PREDICTION",
                "message": "Model confidence too low to determine distress severity.",
                "modelVersion": MODEL_VERSION,
                "reviewRequired": True,
                "imageQuality": image_quality,
                **gate_fields,
                **road_gate,
            }
            cache_set(img_hash, res)
            return res

        res = {
            "severity": predicted_severity,
            "severityScore": _severity_score(predicted_severity),
            "confidence": round(top_confidence * 100.0, 1),
            "status": gate_decision if gate_blocks else "CONFIRMED",
            "modelVersion": MODEL_VERSION,
            "reviewRequired": review_required,
            "imageQuality": image_quality,
            **gate_fields,
            **road_gate,
        }
        cache_set(img_hash, res)
        return res

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Inference error: {str(e)}")


@app.post("/predict")
async def predict_severity(file: UploadFile = File(...), _: None = Depends(require_api_key)):
    """Single-image severity prediction (unchanged contract)."""
    contents = await _read_bounded(file)
    return infer_image(contents, file.content_type)


@app.post("/predict-multi")
async def predict_multi_image_severity(
    files: List[UploadFile] = File(...),
    perspectives: Optional[List[str]] = Form(None),
    _: None = Depends(require_api_key),
):
    """Cascaded validation + multi-image severity aggregation for one report.

    Stage 1 — every photo must survive the layered pipeline and the decisiveness
    gate. The submission is rejected as a whole with HTTP 422 when any single photo
    fails, because max-aggregation over a set that contains an unusable image is
    meaningless: a selfie scored as "High" would promote the whole report. The
    response body names the offending indices so the client can highlight exactly
    which frame to retake.

    Stage 2 — ``S_cv = max(SeverityModel(I_1) .. SeverityModel(I_N))`` over the
    validated images, returned as both the label and the 1-3 class number the
    priority engine consumes. Reported confidence is the confidence of the image
    that produced the maximum (the score that actually drove the result), with the
    mean across images alongside it for auditing.
    """
    if session is None:
        raise HTTPException(status_code=503, detail="AI Model session is not initialized.")
    if not files:
        raise HTTPException(status_code=400, detail="At least one image is required.")
    if len(files) > MAX_REPORT_IMAGES:
        raise HTTPException(
            status_code=400,
            detail=f"At most {MAX_REPORT_IMAGES} images may be submitted per report.")

    images: List[Dict] = []
    rejected: List[Dict] = []

    for index, upload in enumerate(files):
        contents = await _read_bounded(upload)
        payload = infer_image(contents, upload.content_type)
        perspective = _perspective_label(
            perspectives[index] if perspectives and index < len(perspectives) else None,
            index)
        passed = bool(payload.get("gatePassed"))
        record = {
            "index": index,
            "perspective": perspective,
            "severity": payload.get("severity"),
            "severityScore": int(payload.get("severityScore") or 0),
            "confidence": payload.get("confidence"),
            "status": payload.get("status"),
            "pMax": payload.get("pMax"),
            "entropy": payload.get("entropy"),
            "gatePassed": passed,
            "reason": payload.get("gateReason") or payload.get("message") or "",
            "roadGate": payload.get("roadGate"),
        }
        images.append(record)
        if not passed:
            rejected.append({
                "index": index,
                "perspective": perspective,
                "severity": payload.get("severity"),
                "status": payload.get("status"),
                "pMax": payload.get("pMax"),
                "entropy": payload.get("entropy"),
                "reason": record["reason"],
            })

    if rejected:
        raise HTTPException(
            status_code=422,
            detail={
                "message": V.GATE_REJECTION_MESSAGE,
                "rejected": rejected,
            })

    scores = [record["severityScore"] for record in images]
    aggregate = max(scores)
    if aggregate < 1:
        # Defensive: imageCount>=1 but nothing scored. Reached only if the
        # decisiveness floors and CONFIDENCE_FLOOR are reconfigured so that a
        # gate-passing image can still be too uncertain to label.
        raise HTTPException(
            status_code=422,
            detail={
                "message": V.GATE_REJECTION_MESSAGE,
                "rejected": [{"index": record["index"],
                              "reason": "No image produced an assessable severity."}
                             for record in images],
            })
    winner = images[scores.index(aggregate)]
    mean_confidence = round(
        sum(float(record["confidence"] or 0.0) for record in images) / len(images), 1)

    return {
        "aggregatedSeverity": SEVERITY_LEVELS[aggregate - 1],
        "aggregatedSeverityScore": aggregate,
        "confidence": winner["confidence"],
        "meanConfidence": mean_confidence,
        "modelVersion": MODEL_VERSION,
        "imageCount": len(images),
        "images": images,
    }

# --- ENGINE 1 ENDPOINT ---
@app.post("/api/v1/forecasting/pavement-decay")
def predict_pavement_decay(req: PavementDecayRequest, _: None = Depends(require_api_key)):
    try:
        return fe.simulate_pavement_lifecycle(
            road_id=req.road_id,
            soil_type=req.soil_type,
            initial_pci=req.initial_pci,
            years_horizon=req.years_horizon,
            fractional_alpha=req.fractional_alpha
        )
    except Exception as e:
        raise _engine_error("Pavement decay engine", e) from e


# --- ENGINE 2 ENDPOINT ---
@app.post("/api/v1/forecasting/hydrological-shock")
def predict_hydrological_shock(req: HydrologicalShockRequest, _: None = Depends(require_api_key)):
    try:
        return fe.simulate_hydrological_shock(
            road_id=req.road_id,
            soil_type=req.soil_type,
            rainfall_mm=req.rainfall_mm,
            slope_degrees=req.slope_degrees,
            drainage_capacity_mm=req.drainage_capacity_mm
        )
    except Exception as e:
        raise _engine_error("Hydrological shock engine", e) from e


# --- ENGINE 3 ENDPOINT ---
@app.post("/api/v1/forecasting/defect-propagation")
def predict_defect_propagation(req: DefectPropagationRequest, _: None = Depends(require_api_key)):
    try:
        return fe.simulate_defect_propagation(
            num_nodes=req.num_nodes,
            timesteps=req.timesteps,
            diffusion_rate=req.diffusion_rate,
            growth_rate=req.growth_rate
        )
    except Exception as e:
        raise _engine_error("Defect propagation engine", e) from e


# --- ENGINE 4 ENDPOINT ---
@app.post("/api/v1/forecasting/financial-var")
def predict_financial_risk(req: FinancialRiskRequest, _: None = Depends(require_api_key)):
    try:
        return fe.simulate_financial_risk_var(
            base_budget_php=req.base_budget_php,
            confidence_level=req.confidence_level,
            alpha_tail=req.alpha_tail,
            beta_skew=req.beta_skew,
            num_simulations=req.num_simulations
        )
    except Exception as e:
        raise _engine_error("Financial risk engine", e) from e


# --- ENGINE 5 ENDPOINT ---
@app.post("/api/v1/forecasting/data-assimilation")
def assimilate_sensor_data(req: DataAssimilationRequest, _: None = Depends(require_api_key)):
    try:
        return fe.assimilate_multi_sensor_data(
            vision_score=req.vision_score,
            vision_confidence=req.vision_confidence,
            sensor_telemetry_score=req.sensor_telemetry_score,
            geomechanical_baseline=req.geomechanical_baseline,
            tradeoff_alpha=req.tradeoff_alpha
        )
    except Exception as e:
        raise _engine_error("Data assimilation engine", e) from e


# --- MULTI-HAZARD COMPOSITE INDEX (roadmap scaffold, spec §2.D) ---
@app.post("/api/v1/forecasting/multi-hazard-index")
def multi_hazard_index(req: MultiHazardRequest, _: None = Depends(require_api_key)):
    """
    CSJDM multi-hazard composite risk index, R_b in [0, 1].

    Roadmap scaffold: evaluates the documented weighted formula over caller-supplied
    normalised hazards. It sources no rasters, does not touch the production ranking
    queue, and stamps ``implementation_status: ROADMAP`` on every reply so a consumer
    cannot mistake it for a production risk score.
    """
    try:
        return mh.composite_risk_index(
            flood=req.flood,
            landslide=req.landslide,
            seismic=req.seismic,
            erosion=req.erosion,
        )
    except Exception as e:
        raise _engine_error("Multi-hazard index", e) from e
