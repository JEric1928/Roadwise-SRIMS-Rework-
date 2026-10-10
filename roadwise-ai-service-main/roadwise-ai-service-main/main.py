import hashlib
import io
import logging
import numpy as np
import onnxruntime as ort
from PIL import Image
from fastapi import FastAPI, File, UploadFile, HTTPException, Header, Security
from fastapi.security.api_key import APIKeyHeader
from collections import OrderedDict
import threading
import os
from pydantic import BaseModel, Field
from typing import Optional
from scipy.ndimage import laplace

import forecasting_engines as fe
from csjdm_parameters import CSJDM_GEOMORPHIC_PROFILE

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
logger = logging.getLogger("roadwise_ai")

app = FastAPI(
    title="RoadWise AI & Advanced Mathematical Forecasting Service",
    version="2.0.0",
    description="ISO/IEC 25010 compliant analytical microservice hosting 5 continuous-time stochastic and differential forecasting engines."
)

# Configuration Constants
SERVICE_API_KEY = os.getenv("AI_SERVICE_API_KEY", "roadwise-internal-secret-key")
MAX_FILE_SIZE = 10 * 1024 * 1024  # 10 MB
MAX_IMAGE_WIDTH = 4096
MAX_IMAGE_HEIGHT = 4096
MAX_TOTAL_PIXELS = 16 * 1024 * 1024  # Decompression bomb limit

MODEL_PATH = "rdd2022_d4_resnet38.onnx"
try:
    opts = ort.SessionOptions()
    opts.intra_op_num_threads = 1
    opts.inter_op_num_threads = 1
    session = ort.InferenceSession(MODEL_PATH, sess_options=opts, providers=['CPUExecutionProvider'])
    input_name = session.get_inputs()[0].name
    logger.info(f"[AI READY] Model '{MODEL_PATH}' loaded successfully.")
except Exception as e:
    logger.error(f"[AI ERROR] Failed to load model: {e}")
    session = None

SEVERITY_LEVELS = ["Low", "Medium", "High"]
CONFIDENCE_FLOOR = 0.40
ROAD_CONFIDENCE_THRESHOLD = 0.70
MODEL_VERSION = "rdd2022-d4-resnet38-v2"
MAX_CACHE_SIZE = 1000

CACHE_LOCK = threading.Lock()
PREDICTION_CACHE: OrderedDict[str, dict] = OrderedDict()

api_key_header = APIKeyHeader(name="X-API-Key", auto_error=False)


def verify_service_api_key(api_key: Optional[str] = Security(api_key_header)):
    if SERVICE_API_KEY and api_key != SERVICE_API_KEY:
        raise HTTPException(status_code=401, detail="Unauthorized service request.")


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


def validate_file_bytes(contents: bytes, filename: Optional[str] = None):
    if not contents or len(contents) == 0:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")

    if len(contents) > MAX_FILE_SIZE:
        raise HTTPException(status_code=400, detail="File size exceeds maximum allowed limit (10MB).")

    # Magic byte signatures verification
    is_jpeg = contents.startswith(b"\xff\xd8\xff")
    is_png = contents.startswith(b"\x89PNG\r\n\x1a\n")
    is_webp = contents.startswith(b"RIFF") and len(contents) >= 12 and contents[8:12] == b"WEBP"

    if not (is_jpeg or is_png or is_webp):
        raise HTTPException(status_code=400, detail="Invalid file type or format signature.")


def verify_and_load_image(contents: bytes) -> Image.Image:
    try:
        verify_img = Image.open(io.BytesIO(contents))
        verify_img.verify()

        img = Image.open(io.BytesIO(contents))
        w, h = img.size

        if w <= 0 or h <= 0:
            raise HTTPException(status_code=400, detail="Invalid image dimensions.")

        if w > MAX_IMAGE_WIDTH or h > MAX_IMAGE_HEIGHT or (w * h) > MAX_TOTAL_PIXELS:
            raise HTTPException(status_code=400, detail="Image dimensions exceed maximum allowed limits.")

        rgb_img = Image.new("RGB", (w, h))
        rgb_img.paste(img)
        return rgb_img
    except HTTPException:
        raise
    except Exception as e:
        logger.warning(f"Image verification/loading failed: {e}")
        raise HTTPException(status_code=400, detail="Failed to parse image file or image is corrupted.")


def check_image_quality_and_surface(img: Image.Image) -> tuple[bool, str, str, float]:
    w, h = img.size

    aspect_ratio = max(w, h) / max(1, min(w, h))
    if aspect_ratio > 4.5:
        return True, "Surface rejected: Extreme aspect ratio detected.", "REJECTED", 0.0

    if w > 512 or h > 512:
        img_val = img.copy()
        img_val.thumbnail((512, 512))
    else:
        img_val = img

    rgb = np.array(img_val.convert("RGB"), dtype=np.float32)
    gray = np.array(img_val.convert("L"), dtype=np.float32)
    total_pixels = gray.size

    white_ratio = float(np.sum(gray > 200.0) / total_pixels)
    if white_ratio > 0.45:
        return True, "Surface rejected: Document or paper surface detected.", "REJECTED", 0.10

    gh, gw = gray.shape
    center_rgb = rgb[int(gh * 0.2):int(gh * 0.8), int(gw * 0.2):int(gw * 0.8)]
    r, g, b = center_rgb[:, :, 0], center_rgb[:, :, 1], center_rgb[:, :, 2]
    center_saturation = float(np.mean(np.maximum.reduce([r, g, b]) - np.minimum.reduce([r, g, b])))

    if center_saturation > 42.0:
        return True, "Surface rejected: High-saturation digital graphic detected.", "REJECTED", 0.15

    mean_brightness = float(np.mean(gray))
    if mean_brightness < 20.0:
        return True, "Image is too dark to inspect.", "REJECTED", 0.20

    if mean_brightness > 235.0:
        return True, "Image is overexposed.", "REJECTED", 0.20

    blur_variance = float(laplace(gray).var())
    if blur_variance < 15.0:
        return True, "Image is excessively blurry or lacks edge detail.", "REJECTED", 0.25

    road_confidence = 0.90
    if center_saturation > 30.0:
        road_confidence -= 0.20
    if mean_brightness < 40.0 or mean_brightness > 200.0:
        road_confidence -= 0.15
    if blur_variance < 50.0:
        road_confidence -= 0.15

    quality = "ACCEPTABLE" if mean_brightness >= 40.0 and blur_variance >= 50.0 else "LOW_QUALITY"
    return False, "Passed", quality, max(0.0, min(1.0, road_confidence))


# ==============================================================================
# REQUEST & RESPONSE PYDANTIC MODELS
# ==============================================================================
class PavementDecayRequest(BaseModel):
    road_id: str = Field(default="RD-001", description="Road Identifier")
    soil_type: str = Field(default="Sibul_Clay", description="Subgrade soil type")
    initial_pci: float = Field(default=100.0, ge=0.0, le=100.0)
    years_horizon: int = Field(default=10, ge=1, le=30)
    fractional_alpha: float = Field(default=0.35, ge=0.01, le=0.99)


class HydrologicalShockRequest(BaseModel):
    road_id: str = Field(default="RD-102")
    soil_type: str = Field(default="Sibul_Clay")
    rainfall_mm: float = Field(default=180.0, ge=0.0)
    slope_degrees: float = Field(default=22.0, ge=0.0, le=90.0)
    drainage_capacity_mm: float = Field(default=50.0, ge=0.0)


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


# ==============================================================================
# API ENDPOINTS
# ==============================================================================
@app.get("/")
def health_check():
    return {
        "status": "online",
        "service": "RoadWise AI Inference & Mathematical Forecasting Engine",
        "model_loaded": session is not None,
        "csjdm_profile": CSJDM_GEOMORPHIC_PROFILE
    }


@app.post("/predict")
async def predict_severity(
    file: UploadFile = File(...),
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)

    if session is None:
        raise HTTPException(status_code=503, detail="AI Model session is not initialized.")

    contents = await file.read()
    validate_file_bytes(contents, file.filename)

    img_hash = hashlib.sha256(contents).hexdigest()
    cached_res = cache_get(img_hash)
    if cached_res is not None:
        return cached_res

    try:
        raw_image = verify_and_load_image(contents)
        is_invalid, reason, image_quality, road_conf = check_image_quality_and_surface(raw_image)

        if is_invalid:
            res = {
                "severity": "Unassessed",
                "confidence": 0.0,
                "roadConfidence": round(road_conf, 2),
                "status": "NON_ROAD_OR_UNCERTAIN",
                "message": reason,
                "modelVersion": MODEL_VERSION,
                "reviewRequired": True,
                "imageQuality": image_quality
            }
            cache_set(img_hash, res)
            return res

        if road_conf < ROAD_CONFIDENCE_THRESHOLD:
            res = {
                "severity": "Unassessed",
                "confidence": 0.0,
                "roadConfidence": round(road_conf, 2),
                "status": "NON_ROAD_OR_UNCERTAIN",
                "message": "Image cannot be confirmed as a valid road surface.",
                "modelVersion": MODEL_VERSION,
                "reviewRequired": True,
                "imageQuality": image_quality
            }
            cache_set(img_hash, res)
            return res

        image_resized = raw_image.resize((224, 224))
        img_np = np.array(image_resized, dtype=np.float32) / 255.0
        img_np = np.transpose(img_np, (2, 0, 1))
        img_np = np.expand_dims(img_np, axis=0)

        outputs = session.run(None, {input_name: img_np})

        if not outputs or len(outputs) == 0:
            raise ValueError("Model produced no outputs.")
        logits = outputs[0][0]
        if logits.shape != (3,) or not np.all(np.isfinite(logits)):
            raise ValueError("Invalid model output logits shape or non-finite numerical values.")

        exp_logits = np.exp(logits - np.max(logits))
        probs = exp_logits / np.sum(exp_logits)

        if not np.all(np.isfinite(probs)) or abs(float(np.sum(probs)) - 1.0) > 1e-3:
            raise ValueError("Normalized output probabilities are numerical invalid.")

        max_idx = int(np.argmax(probs))
        top_confidence = float(probs[max_idx])
        predicted_severity = SEVERITY_LEVELS[max_idx]

        review_required = top_confidence < 0.70 or image_quality == "LOW_QUALITY"

        if top_confidence < CONFIDENCE_FLOOR:
            res = {
                "severity": "Unassessed",
                "confidence": round(top_confidence * 100.0, 1),
                "roadConfidence": round(road_conf, 2),
                "status": "UNCERTAIN_PREDICTION",
                "message": "Model confidence too low to determine distress severity.",
                "modelVersion": MODEL_VERSION,
                "reviewRequired": True,
                "imageQuality": image_quality
            }
            cache_set(img_hash, res)
            return res

        res = {
            "severity": predicted_severity,
            "confidence": round(top_confidence * 100.0, 1),
            "roadConfidence": round(road_conf, 2),
            "status": "ROAD_CONFIRMED",
            "modelVersion": MODEL_VERSION,
            "reviewRequired": review_required,
            "imageQuality": image_quality
        }
        cache_set(img_hash, res)
        return res

    except HTTPException:
        raise
    except Exception as e:
        logger.error(f"Internal error processing request: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="An internal error occurred during image processing.")


# --- ENGINE 1 ENDPOINT ---
@app.post("/api/v1/forecasting/pavement-decay")
def predict_pavement_decay(
    req: PavementDecayRequest,
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)
    try:
        return fe.simulate_pavement_lifecycle(
            road_id=req.road_id,
            soil_type=req.soil_type,
            initial_pci=req.initial_pci,
            years_horizon=req.years_horizon,
            fractional_alpha=req.fractional_alpha
        )
    except Exception as e:
        logger.error(f"Pavement decay engine error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Pavement decay forecasting engine error.")


# --- ENGINE 2 ENDPOINT ---
@app.post("/api/v1/forecasting/hydrological-shock")
def predict_hydrological_shock(
    req: HydrologicalShockRequest,
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)
    try:
        return fe.simulate_hydrological_shock(
            road_id=req.road_id,
            soil_type=req.soil_type,
            rainfall_mm=req.rainfall_mm,
            slope_degrees=req.slope_degrees,
            drainage_capacity_mm=req.drainage_capacity_mm
        )
    except Exception as e:
        logger.error(f"Hydrological shock engine error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Hydrological shock forecasting engine error.")


# --- ENGINE 3 ENDPOINT ---
@app.post("/api/v1/forecasting/defect-propagation")
def predict_defect_propagation(
    req: DefectPropagationRequest,
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)
    try:
        return fe.simulate_defect_propagation(
            num_nodes=req.num_nodes,
            timesteps=req.timesteps,
            diffusion_rate=req.diffusion_rate,
            growth_rate=req.growth_rate
        )
    except Exception as e:
        logger.error(f"Defect propagation engine error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Defect propagation forecasting engine error.")


# --- ENGINE 4 ENDPOINT ---
@app.post("/api/v1/forecasting/financial-var")
def predict_financial_risk(
    req: FinancialRiskRequest,
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)
    try:
        return fe.simulate_financial_risk_var(
            base_budget_php=req.base_budget_php,
            confidence_level=req.confidence_level,
            alpha_tail=req.alpha_tail,
            beta_skew=req.beta_skew,
            num_simulations=req.num_simulations
        )
    except Exception as e:
        logger.error(f"Financial risk engine error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Financial risk forecasting engine error.")


# --- ENGINE 5 ENDPOINT ---
@app.post("/api/v1/forecasting/data-assimilation")
def assimilate_sensor_data(
    req: DataAssimilationRequest,
    api_key: Optional[str] = Header(None, alias="X-API-Key")
):
    verify_service_api_key(api_key)
    try:
        return fe.assimilate_multi_sensor_data(
            vision_score=req.vision_score,
            vision_confidence=req.vision_confidence,
            sensor_telemetry_score=req.sensor_telemetry_score,
            geomechanical_baseline=req.geomechanical_baseline,
            tradeoff_alpha=req.tradeoff_alpha
        )
    except Exception as e:
        logger.error(f"Data assimilation engine error: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail="Data assimilation forecasting engine error.")
