import hashlib
import io
import numpy as np
import onnxruntime as ort
from PIL import Image
from fastapi import FastAPI, File, UploadFile, HTTPException
from collections import OrderedDict
import threading
from pydantic import BaseModel, Field
from typing import Optional, List, Dict

import forecasting_engines as fe
from csjdm_parameters import CSJDM_GEOMORPHIC_PROFILE

app = FastAPI(
    title="RoadWise AI & Advanced Mathematical Forecasting Service",
    version="2.0.0",
    description="ISO/IEC 25010 compliant analytical microservice hosting 5 continuous-time stochastic and differential forecasting engines."
)

# 1. Load ONNX Model on Startup
MODEL_PATH = "rdd2022_d4_resnet38.onnx"
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

SEVERITY_LEVELS = ["Low", "Medium", "High"]
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


def is_non_road_upload(img: Image.Image) -> tuple[bool, str, str]:
    w_orig, h_orig = img.size
    if w_orig > 512 or h_orig > 512:
        img_val = img.copy()
        img_val.thumbnail((512, 512))
    else:
        img_val = img

    rgb = np.array(img_val.convert("RGB"), dtype=np.float32)
    gray = np.array(img_val.convert("L"), dtype=np.float32)
    total_pixels = gray.size

    white_ratio = float(np.sum(gray > 200.0) / total_pixels)
    if white_ratio > 0.45:
        return True, "Surface rejected: Document or paper surface detected.", "REJECTED"

    h, w = gray.shape
    center_rgb = rgb[int(h * 0.2):int(h * 0.8), int(w * 0.2):int(w * 0.8)]
    r, g, b = center_rgb[:, :, 0], center_rgb[:, :, 1], center_rgb[:, :, 2]
    center_saturation = float(np.mean(np.maximum.reduce([r, g, b]) - np.minimum.reduce([r, g, b])))

    if center_saturation > 42.0:
        return True, "Surface rejected: High-saturation digital graphic detected.", "REJECTED"

    mean_brightness = float(np.mean(gray))
    if mean_brightness < 20.0:
        return True, "Image is too dark to inspect.", "REJECTED"

    quality = "ACCEPTABLE" if mean_brightness >= 40.0 else "LOW_QUALITY"
    return False, "Passed", quality


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
async def predict_severity(file: UploadFile = File(...)):
    if session is None:
        raise HTTPException(status_code=503, detail="AI Model session is not initialized.")

    contents = await file.read()
    if not contents:
        raise HTTPException(status_code=400, detail="Uploaded file is empty.")

    img_hash = hashlib.sha256(contents).hexdigest()
    cached_res = cache_get(img_hash)
    if cached_res is not None:
        return cached_res

    try:
        raw_image = Image.open(io.BytesIO(contents))
        is_invalid, reason, image_quality = is_non_road_upload(raw_image)
        if is_invalid:
            res = {
                "severity": "Unassessed",
                "confidence": 0.0,
                "status": "INVALID_SURFACE",
                "message": reason,
                "modelVersion": MODEL_VERSION,
                "reviewRequired": True,
                "imageQuality": image_quality
            }
            cache_set(img_hash, res)
            return res

        image_resized = raw_image.convert("RGB").resize((224, 224))
        img_np = np.array(image_resized, dtype=np.float32) / 255.0
        img_np = np.transpose(img_np, (2, 0, 1))
        img_np = np.expand_dims(img_np, axis=0)

        outputs = session.run(None, {input_name: img_np})
        logits = outputs[0][0]

        exp_logits = np.exp(logits - np.max(logits))
        probs = exp_logits / np.sum(exp_logits)
        max_idx = int(np.argmax(probs))
        top_confidence = float(probs[max_idx])
        predicted_severity = SEVERITY_LEVELS[max_idx]

        review_required = top_confidence < 0.70 or image_quality == "LOW_QUALITY"

        if top_confidence < CONFIDENCE_FLOOR:
            res = {
                "severity": "Unassessed",
                "confidence": round(top_confidence * 100.0, 1),
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
            "status": "CONFIRMED",
            "modelVersion": MODEL_VERSION,
            "reviewRequired": review_required,
            "imageQuality": image_quality
        }
        cache_set(img_hash, res)
        return res

    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Inference error: {str(e)}")


# --- ENGINE 1 ENDPOINT ---
@app.post("/api/v1/forecasting/pavement-decay")
def predict_pavement_decay(req: PavementDecayRequest):
    try:
        return fe.simulate_pavement_lifecycle(
            road_id=req.road_id,
            soil_type=req.soil_type,
            initial_pci=req.initial_pci,
            years_horizon=req.years_horizon,
            fractional_alpha=req.fractional_alpha
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Pavement decay engine error: {str(e)}")


# --- ENGINE 2 ENDPOINT ---
@app.post("/api/v1/forecasting/hydrological-shock")
def predict_hydrological_shock(req: HydrologicalShockRequest):
    try:
        return fe.simulate_hydrological_shock(
            road_id=req.road_id,
            soil_type=req.soil_type,
            rainfall_mm=req.rainfall_mm,
            slope_degrees=req.slope_degrees,
            drainage_capacity_mm=req.drainage_capacity_mm
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Hydrological shock engine error: {str(e)}")


# --- ENGINE 3 ENDPOINT ---
@app.post("/api/v1/forecasting/defect-propagation")
def predict_defect_propagation(req: DefectPropagationRequest):
    try:
        return fe.simulate_defect_propagation(
            num_nodes=req.num_nodes,
            timesteps=req.timesteps,
            diffusion_rate=req.diffusion_rate,
            growth_rate=req.growth_rate
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Defect propagation engine error: {str(e)}")


# --- ENGINE 4 ENDPOINT ---
@app.post("/api/v1/forecasting/financial-var")
def predict_financial_risk(req: FinancialRiskRequest):
    try:
        return fe.simulate_financial_risk_var(
            base_budget_php=req.base_budget_php,
            confidence_level=req.confidence_level,
            alpha_tail=req.alpha_tail,
            beta_skew=req.beta_skew,
            num_simulations=req.num_simulations
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Financial risk engine error: {str(e)}")


# --- ENGINE 5 ENDPOINT ---
@app.post("/api/v1/forecasting/data-assimilation")
def assimilate_sensor_data(req: DataAssimilationRequest):
    try:
        return fe.assimilate_multi_sensor_data(
            vision_score=req.vision_score,
            vision_confidence=req.vision_confidence,
            sensor_telemetry_score=req.sensor_telemetry_score,
            geomechanical_baseline=req.geomechanical_baseline,
            tradeoff_alpha=req.tradeoff_alpha
        )
    except Exception as e:
        raise HTTPException(status_code=500, detail=f"Data assimilation engine error: {str(e)}")
