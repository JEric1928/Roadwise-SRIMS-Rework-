"""
RoadWise AI Service - Secure image validation and road-scene gating.

Implements the layered safety pipeline requested for the inference side:

    1. File / security validation   (size, MIME declaration, magic bytes, verify)
    2. Image quality safeguards     (dimensions, decompression bombs, blur,
                                     exposure, aspect ratio, compression artifacts)
    3. Road / non-road heuristics   (document, screenshot, sky-dominant, dark)
    4. Road-scene gate              (Mahalanobis OOD distance on ResNet-style
                                     penultimate embeddings + optional learned
                                     road/non-road classifier head)
    5. Model output validation      (shape, finiteness, class range, margins)

The severity model's own confidence is NEVER used as proof that an image
contains a road; the road gate below runs before severity classification.
"""

import hashlib
import io
import math
from dataclasses import dataclass, field
from pathlib import Path
from typing import List, Optional, Tuple

import numpy as np
from PIL import Image, ImageFile, UnidentifiedImageError

# Do not let Pillow buffer unbounded amounts of decompressed data.
ImageFile.LOAD_TRUNCATED_IMAGES = False

# =============================================================================
# Policy constants (thresholds must be recalibrated on a validation dataset
# whenever the model, preprocessing, camera source or image domain changes).
# =============================================================================
MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024          # absolute ceiling: 10 MB
MIN_VALID_FILE_SIZE = 128                        # trivially empty payloads
MAX_IMAGE_DIMENSION = 4096                       # per-side pixel limit
MAX_TOTAL_PIXELS = 20_000_000                    # decompression-bomb guard
MIN_TOTAL_PIXELS = 64 * 64                       # unusably tiny images
MAX_ASPECT_RATIO = 5.0                           # extreme panoramas / strips

ALLOWED_MIME_TYPES = {"image/jpeg", "image/jpg", "image/pjpeg", "image/png", "image/webp"}
BLOCKED_MAGIC_PREFIXES = {
    b"%PDF": "application/pdf",
    b"GIF8": "image/gif (animated formats are not accepted)",
    b"BM": "image/bmp",
    b"PS": "postscript",
}

# Heuristic quality gates
BLUR_VAR_THRESHOLD = 45.0        # Laplacian variance below this => too blurry
OVEREXPOSED_RATIO = 0.60         # fraction of >245 luma pixels to reject
OVEREXPOSED_MEAN = 240.0         # mean luma above this => blown out
TOO_DARK_MEAN = 20.0             # mean luma below this => reject
LOW_QUALITY_MEAN = 40.0          # between TOO_DARK and this => LOW_QUALITY
WHITE_DOC_RATIO = 0.45           # bright-paper/document heuristic
GRAPHIC_SATURATION = 42.0        # high-saturation digital graphic heuristic
SKY_RATIO = 0.55                 # top-band bluish pixels dominating the frame
EDGE_DENSITY_MIN = 0.004         # flat synthetic graphics have almost no edges
SATURATED_FLAT_RATIO = 0.5       # fraction of center pixels that must be saturated

# Road-gate policy (calibrate with ROAD_ID / NEAR_OOD / FAR_OOD datasets!)
ROAD_CONF_ACCEPT = 0.80          # >= accept, continue to severity model
ROAD_CONF_REVIEW = 0.50          # [REVIEW, ACCEPT) => manual review band
MAHA_CALIBRATION_PATH = "road_calibration.npz"

SEVERITY_CLASSES = ("Low", "Medium", "High")


# =============================================================================
# Result containers
# =============================================================================
@dataclass
class ValidationOutcome:
    ok: bool
    status: str                     # e.g. INVALID_INPUT / LOW_IMAGE_QUALITY / ...
    message: str = ""
    review_required: bool = True
    image_quality: str = "UNKNOWN"
    details: dict = field(default_factory=dict)


@dataclass
class RoadGateResult:
    decision: str                   # ROAD_CONFIRMED / REVIEW_REQUIRED / NON_ROAD_OR_UNCERTAIN / OUT_OF_DISTRIBUTION
    road_confidence: float
    mahalanobis_distance: Optional[float]
    review_required: bool
    message: str = ""


# =============================================================================
# 1. File / security validation (performed BEFORE any decoding)
# =============================================================================
def validate_upload_bytes(contents: bytes, declared_mime: Optional[str]) -> Tuple[Optional[str], Optional[str]]:
    """Return (error_status, error_message); both None when the payload passes."""
    if contents is None or len(contents) == 0:
        return "INVALID_INPUT", "Uploaded file is empty."
    if len(contents) < MIN_VALID_FILE_SIZE:
        return "INVALID_INPUT", "Uploaded file is too small to be a valid image."
    if len(contents) > MAX_FILE_SIZE_BYTES:
        return "INVALID_INPUT", "Uploaded file exceeds the maximum allowed size."

    # Declared MIME (never trusted alone, but rejected early when obviously wrong)
    norm_mime = (declared_mime or "").lower().split(";")[0].strip()
    if norm_mime and norm_mime not in ALLOWED_MIME_TYPES:
        return "INVALID_INPUT", f"Declared content type '{norm_mime}' is not an accepted image format."

    # Actual signature / magic bytes (this is what we trust)
    detected = detect_magic_mime(contents)
    if detected is None:
        return "INVALID_INPUT", "File signature does not match an accepted image format (JPEG/PNG/WebP only)."
    for prefix, label in BLOCKED_MAGIC_PREFIXES.items():
        if contents.startswith(prefix) and detected != "image/png":
            # PDF/GIF/BM/PS headers never appear inside valid JPEG/PNG/WebP magic
            if not detected.startswith("image/"):
                return "INVALID_INPUT", f"Detected unsupported file type: {label}."
    if detected not in ALLOWED_MIME_TYPES:
        return "INVALID_INPUT", f"Detected unsupported file type: {detected}."
    if norm_mime and norm_mime not in (detected, "image/jpg") and not (
        norm_mime == "image/jpeg" and detected == "image/jpeg"
    ):
        # Mismatch between declared and actual type is suspicious (possible spoofing)
        if not (norm_mime.replace("image/", "") in ("jpeg", "jpg", "pjpeg") and detected == "image/jpeg"):
            return "INVALID_INPUT", "Declared content type does not match the actual file signature."
    return None, None


def detect_magic_mime(data: bytes) -> Optional[str]:
    if data[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if data[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if len(data) >= 12 and data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


# =============================================================================
# 2. Safe decode + structural image checks
# =============================================================================
def safe_decode_image(contents: bytes) -> Tuple[Optional[Image.Image], Optional[str], Optional[str]]:
    """Verify then reopen the image (anti-decompression-bomb pattern).

    Returns (rgb_image, error_status, error_message).
    """
    try:
        with Image.open(io.BytesIO(contents)) as probe:
            fmt = (probe.format or "").upper()
            if fmt not in ("JPEG", "PNG", "WEBP"):
                return None, "INVALID_INPUT", f"Decoder identified unsupported format '{fmt or 'unknown'}'."
            w, h = probe.size
            # Structural checks happen from the header, before full pixel decode.
            if w <= 0 or h <= 0:
                return None, "INVALID_INPUT", "Image has zero width or height."
            if w > MAX_IMAGE_DIMENSION or h > MAX_IMAGE_DIMENSION:
                return None, "INVALID_INPUT", f"Image dimensions exceed the {MAX_IMAGE_DIMENSION}px limit."
            if w * h > MAX_TOTAL_PIXELS:
                return None, "INVALID_INPUT", "Image pixel count exceeds the decompression-bomb limit."
            if w * h < MIN_TOTAL_PIXELS:
                return None, "LOW_IMAGE_QUALITY", "Image resolution is too low to assess road damage."
            ratio = max(w, h) / float(min(w, h))
            if ratio > MAX_ASPECT_RATIO:
                return None, "INVALID_INPUT", "Extreme aspect ratio; not a plausible road photo."
            probe.verify()  # validates integrity; the file object is unusable after this...

        # ...so we must REOPEN it for the actual (bounded) pixel decode.
        img = Image.open(io.BytesIO(contents))
        img.load()
        rgb = img.convert("RGB")  # normalizes palette/grayscale/CMYK, ignores EXIF orientation
        img.close()
        return rgb, None, None
    except UnidentifiedImageError:
        return None, "INVALID_INPUT", "File could not be decoded as an image."
    except (OSError, ValueError, SyntaxError, Image.DecompressionBombError):
        return None, "INVALID_INPUT", "Corrupt or oversized image data; decoding refused."


# =============================================================================
# 3. Image-quality safeguards (heuristic pre-screen)
# =============================================================================
def _laplacian_variance(gray: np.ndarray) -> float:
    kernel = np.array([[0.0, 1.0, 0.0], [1.0, -4.0, 1.0], [0.0, 1.0, 0.0]], dtype=np.float32)
    h, w = gray.shape
    padded = np.pad(gray, 1, mode="edge")
    resp = np.zeros((h, w), dtype=np.float32)
    for dy in (-1, 0, 1):
        for dx in (-1, 0, 1):
            k = kernel[dy + 1, dx + 1]
            if k != 0.0:
                resp += k * padded[1 + dy:1 + dy + h, 1 + dx:1 + dx + w]
    return float(resp.var())


def _edge_density(gray: np.ndarray) -> float:
    gx = np.abs(np.diff(gray, axis=1)).mean()
    gy = np.abs(np.diff(gray, axis=0)).mean()
    grad = np.maximum(gx, 1e-6) + np.maximum(gy, 1e-6)
    # Fraction of pixels whose local gradient exceeds ~15% of dynamic range
    strong = ((np.abs(np.diff(gray, axis=1)) > 38.0).sum() +
              (np.abs(np.diff(gray, axis=0)) > 38.0).sum()) / float(gray.size)
    del grad
    return float(strong)


def prescreen_quality(img: Image.Image) -> ValidationOutcome:
    """Brightness / saturation / document / sky heuristics + blur detection."""
    work = img.copy()
    work.thumbnail((512, 512))
    rgb = np.asarray(work, dtype=np.float32)
    gray = np.array(work.convert("L"), dtype=np.float32)
    total = float(gray.size)

    mean_brightness = float(gray.mean())
    white_ratio = float((gray > 200.0).sum() / total)
    over_ratio = float((gray > 245.0).sum() / total)

    h, w = gray.shape
    center = rgb[int(h * 0.2):int(h * 0.8), int(w * 0.2):int(w * 0.8)]
    r, g, b = center[:, :, 0], center[:, :, 1], center[:, :, 2]
    sat_map = np.maximum.reduce([r, g, b]) - np.minimum.reduce([r, g, b])
    center_saturation = float(np.mean(sat_map))
    saturated_fraction = float((sat_map > 60.0).mean())

    if mean_brightness < TOO_DARK_MEAN:
        return ValidationOutcome(False, "LOW_IMAGE_QUALITY",
                                 "Image is too dark to inspect.",
                                 image_quality="REJECTED")
    if white_ratio > WHITE_DOC_RATIO:
        return ValidationOutcome(False, "NON_ROAD_OR_UNCERTAIN",
                                 "Surface rejected: document or paper surface detected.",
                                 image_quality="REJECTED")
    if over_ratio > OVEREXPOSED_RATIO or mean_brightness > OVEREXPOSED_MEAN:
        return ValidationOutcome(False, "LOW_IMAGE_QUALITY",
                                 "Image is severely overexposed.",
                                 image_quality="REJECTED")

    edge_density = _edge_density(gray)
    lap_var = _laplacian_variance(gray)
    # Uniformly saturated flat frames are synthetic graphics/screenshots; check
    # this BEFORE the blur gate so they receive the semantically correct status.
    if center_saturation > GRAPHIC_SATURATION and saturated_fraction > SATURATED_FLAT_RATIO:
        return ValidationOutcome(False, "NON_ROAD_OR_UNCERTAIN",
                                 "Surface rejected: uniformly saturated digital graphic "
                                 "(screenshot/UI render) detected.",
                                 image_quality="REJECTED")
    if lap_var < BLUR_VAR_THRESHOLD:
        return ValidationOutcome(False, "LOW_IMAGE_QUALITY",
                                 "Image is too blurry (likely motion blur or obstruction) to assess.",
                                 image_quality="REJECTED")

    # Sky-dominant frames: bluish upper band covering most of the image
    top_band = rgb[:max(1, h // 3), :, :]
    tr, tg, tb = top_band[:, :, 0], top_band[:, :, 1], top_band[:, :, 2]
    sky_mask = (tb > tr + 12.0) & (tb > 120.0) & (tg > tr)
    sky_fraction = float(sky_mask.sum() / sky_mask.size)
    if sky_fraction > SKY_RATIO and mean_brightness > 140.0:
        return ValidationOutcome(False, "NON_ROAD_OR_UNCERTAIN",
                                 "Frame is dominated by sky; no road surface visible.",
                                 image_quality="REJECTED")

    # High-saturation FLAT regions indicate digital graphics/screenshots rather
    # than photographed asphalt (which is low-saturation but textured). Real
    # road markings stay local, so whole-frame flatness is required here.
    # Photographed asphalt stays low-saturation even with bright markings;
    # flat low-texture frames with any color cast are treated as screenshots.
    if edge_density < EDGE_DENSITY_MIN and center_saturation > 20.0:
        return ValidationOutcome(False, "NON_ROAD_OR_UNCERTAIN",
                                 "Flat digital graphic detected (possible screenshot).",
                                 image_quality="REJECTED")

    quality = "ACCEPTABLE" if mean_brightness >= LOW_QUALITY_MEAN else "LOW_QUALITY"
    return ValidationOutcome(True, "PASSED", "Passed quality pre-screen",
                             review_required=(quality == "LOW_QUALITY"),
                             image_quality=quality,
                             details={"meanBrightness": round(mean_brightness, 1),
                                      "blurScore": round(lap_var, 1)})


# =============================================================================
# 4. Road-scene gate: Mahalanobis OOD on embeddings (+ optional classifier head)
# =============================================================================
class RoadSceneGate:
    """One-class Mahalanobis gate fitted ONLY on valid road-image embeddings.

    Calibration data (road_calibration.npz) must contain:
        embeddings : (N, D) float array of L2-normalized road-image features
    Optional keys:
        covariance : (D, D) precomputed regularized covariance
        road_head_weights / road_head_bias : logistic head P(road | embedding)

    The covariance uses Ledoit-Wolf-style shrinkage toward a scaled identity so
    that small calibration sets cannot produce a singular matrix.
    """

    def __init__(self, calibration_path: str = MAHA_CALIBRATION_PATH,
                 dimension: int = 512, maha_threshold: Optional[float] = None):
        self.dimension = dimension
        self.mean: Optional[np.ndarray] = None
        self.cov_inv: Optional[np.ndarray] = None
        self.threshold = maha_threshold          # calibrated externally, NOT guessed
        self.head_w: Optional[np.ndarray] = None
        self.head_b: float = 0.0
        self.loaded = False
        try:
            data = np.load(calibration_path)
            emb = np.asarray(data["embeddings"], dtype=np.float64)
            if emb.ndim != 2 or emb.shape[0] < 10:
                raise ValueError("Calibration embeddings must be a (N>=10, D) matrix.")
            self.dimension = int(emb.shape[1])
            if "covariance" in data.files:
                cov = np.asarray(data["covariance"], dtype=np.float64)
            else:
                cov = np.cov(emb, rowvar=False)
            # Regularization: covariance += lambda * I  (prevents instability)
            lam = 0.1 * float(np.trace(cov)) / max(cov.shape[0], 1) + 1e-6
            cov = cov + lam * np.eye(cov.shape[0], dtype=np.float64)
            self.mean = emb.mean(axis=0)
            self.cov_inv = np.linalg.pinv(cov)
            if maha_threshold is not None:
                self.threshold = float(maha_threshold)
            elif "maha_threshold" in data.files:
                self.threshold = float(data["maha_threshold"])
            if "road_head_weights" in data.files and "road_head_bias" in data.files:
                self.head_w = np.asarray(data["road_head_weights"], dtype=np.float64).reshape(-1)
                self.head_b = float(np.asarray(data["road_head_bias"]).reshape(()))
            self.loaded = True
            print(f">>> [ROAD GATE] Mahalanobis calibration loaded "
                  f"(dim={self.dimension}, samples={emb.shape[0]}, threshold={self.threshold}).")
        except FileNotFoundError:
            print(">>> [ROAD GATE] WARNING: no calibration file found; feature-space gate disabled "
                  "(heuristic screen remains active). Severity-only predictions are NOT road proof.")
        except Exception as exc:  # pragma: no cover - defensive
            print(f">>> [ROAD GATE] WARNING: failed to load calibration ({exc}); gate disabled.")

    def embed_to_vector(self, outputs, embedding_index: int) -> Optional[np.ndarray]:
        """Pull the penultimate/global-average-pooling embedding out of ONNX outputs."""
        if embedding_index is None or embedding_index >= len(outputs):
            return None
        vec = np.asarray(outputs[embedding_index]).reshape(-1).astype(np.float64)
        if vec.size == 0 or not np.all(np.isfinite(vec)):
            return None
        norm = np.linalg.norm(vec)
        if norm <= 1e-12:
            return None
        return vec / norm  # cosine-friendly unit embedding

    def road_probability(self, z: np.ndarray) -> Optional[float]:
        if self.head_w is None or self.head_w.shape[0] != z.shape[0]:
            return None
        logit = float(np.dot(self.head_w, z) + self.head_b)
        return 1.0 / (1.0 + math.exp(-max(min(logit, 30.0), -30.0)))

    def mahalanobis(self, z: np.ndarray) -> Optional[float]:
        if self.cov_inv is None or z.shape[0] != self.mean.shape[0]:
            return None
        d = z - self.mean
        dist_sq = float(d @ self.cov_inv @ d)
        if not np.isfinite(dist_sq) or dist_sq < 0.0:
            return None
        return math.sqrt(max(dist_sq, 0.0))

    def evaluate(self, embedding: Optional[np.ndarray]) -> RoadGateResult:
        if embedding is None:
            # No embedding available (model exports logits only). Fail closed to review.
            return RoadGateResult("REVIEW_REQUIRED", 0.0, None, True,
                                  "Road-scene embedding unavailable; manual review required.")
        if not self.loaded:
            return RoadGateResult("REVIEW_REQUIRED", 0.0, None, True,
                                  "Road-scene gate is not calibrated; manual review required.")

        p_road = self.road_probability(embedding)
        dist = self.mahalanobis(embedding)

        # Combined policy: classifier AND distance must agree (see design notes).
        if p_road is not None:
            if p_road < ROAD_CONF_REVIEW:
                return RoadGateResult("NON_ROAD_OR_UNCERTAIN", p_road, dist, True,
                                      "Road-scene classifier confidence below rejection threshold.")
            if p_road < ROAD_CONF_ACCEPT:
                return RoadGateResult("REVIEW_REQUIRED", p_road, dist, True,
                                      "Road-scene classifier confidence in manual-review band.")
        if self.threshold is not None and dist is not None and dist > self.threshold:
            return RoadGateResult("OUT_OF_DISTRIBUTION", p_road if p_road is not None else 0.0,
                                  dist, True,
                                  "Image embedding is out of distribution relative to known road imagery.")
        conf = p_road if p_road is not None else 1.0
        return RoadGateResult("ROAD_CONFIRMED", conf, dist, False, "Road scene confirmed by gate.")


# =============================================================================
# 5. Severity model output validation
# =============================================================================
def validate_model_outputs(outputs: List[np.ndarray],
                           expected_num_classes: int = 3,
                           embedding_index: Optional[int] = 1) -> Tuple[Optional[np.ndarray], Optional[str]]:
    """Validate raw ONNX outputs before they are ever interpreted.

    Checks: at least one output, exactly `expected_num_classes` logits, all
    values finite, predicted index within the allowed class range.
    Returns (logits, error_message).
    """
    if outputs is None or len(outputs) == 0:
        return None, "MODEL_ERROR: model returned no outputs."
    logits = np.asarray(outputs[0], dtype=np.float64).reshape(-1)
    if logits.size != expected_num_classes:
        return None, f"MODEL_ERROR: expected {expected_num_classes} logits, got {logits.size}."
    if not np.all(np.isfinite(logits)):
        return None, "MODEL_ERROR: model output contains NaN or infinite values."
    probs = softmax(logits)
    idx = int(np.argmax(probs))
    if idx < 0 or idx >= expected_num_classes:
        return None, "MODEL_ERROR: predicted class index outside allowed range."
    if abs(float(probs.sum()) - 1.0) > 1e-6:
        return None, "MODEL_ERROR: probabilities do not sum to 1."
    return logits, None


def softmax(logits: np.ndarray) -> np.ndarray:
    shifted = logits - float(np.max(logits))
    exp = np.exp(shifted)
    total = float(exp.sum())
    if not np.isfinite(total) or total <= 0.0:
        raise ValueError("Invalid softmax denominator")
    return exp / total


def margin_is_too_small(probs: np.ndarray, min_margin: float = 0.10) -> bool:
    """True when the top two classes are indistinguishably close."""
    ordered = np.sort(probs)[::-1]
    return bool(ordered[0] - ordered[1] < min_margin)


# =============================================================================
# 5b. Stage-1 single-pass input gate (normalized entropy + max probability)
# =============================================================================
# IMPORTANT — what this gate does and does not prove.
#
# p_max and H(p) are computed from the *severity* classifier's output distribution.
# They measure how DECISIVE the model is about which severity class an image
# belongs to. They are not evidence about whether the image contains a road at all:
# a photograph of a document or an indoor scene can sit far outside the training
# distribution and still produce a confidently peaked 3-way distribution, because
# nothing in a softmax over severity classes penalizes "none of these".
#
# The genuine road/non-road signals in this module are the heuristic pre-screen
# (section 3: documents, skies, screenshots, saturation) and the embedding-space
# RoadSceneGate (section 4). This gate is an additional cheap filter that catches
# the *ambiguous* upload — the one the classifier itself cannot place — and it is
# applied per image on the multi-image path so a single unusable photo cannot
# contribute to severity aggregation.
#
# Thresholds come from the platform specification; recalibrate them against a
# labelled validation set alongside the other policy constants above.
CONFIDENCE_PMAX_FLOOR = 0.65        # reject when max class probability is below this
CONFIDENCE_ENTROPY_CEILING = 0.75   # reject when normalized entropy is above this

GATE_REJECTION_MESSAGE = "Invalid Upload: Image does not contain recognizable road distress."


@dataclass
class ConfidenceGate:
    """Outcome of the Stage-1 decisiveness gate for one image."""
    passed: bool
    p_max: float
    entropy: float                  # normalized to [0, 1]: 0 certain, 1 uniform
    reason: str = ""


def normalized_entropy(probs: np.ndarray) -> float:
    """Shannon entropy of a categorical distribution, normalized by log(C).

    Normalizing keeps the ceiling meaningful if the severity head ever gains or
    loses a class. Returns 0.0 for a degenerate distribution (a single non-zero
    class) and 1.0 for a uniform one.
    """
    p = np.asarray(probs, dtype=np.float64).reshape(-1)
    p = p[p > 0.0]
    if p.size <= 1:
        return 0.0
    entropy = float(-(p * np.log(p)).sum())
    ceiling = float(np.log(p.size))
    if not np.isfinite(entropy) or ceiling <= 0.0:
        return 0.0
    return max(0.0, min(1.0, entropy / ceiling))


def confidence_gate(probs: np.ndarray,
                    p_max_floor: float = CONFIDENCE_PMAX_FLOOR,
                    entropy_ceiling: float = CONFIDENCE_ENTROPY_CEILING) -> ConfidenceGate:
    """Apply the Stage-1 rule to one image's class distribution.

    Rejects when the distribution is not decisive: ``p_max < p_max_floor`` OR
    ``H(p) / log(C) > entropy_ceiling``. Both conditions are checked because they
    fail differently — p_max catches a distribution with no clear winner, entropy
    catches one that is smeared across several classes even when the top class is
    nominally above the floor.
    """
    p = np.asarray(probs, dtype=np.float64).reshape(-1)
    if p.size == 0 or not np.all(np.isfinite(p)) or float(p.sum()) <= 0.0:
        return ConfidenceGate(False, 0.0, 1.0, "Model produced no usable class distribution.")

    total = float(p.sum())
    if abs(total - 1.0) > 1e-6:
        p = p / total     # tolerate float drift without trusting a bad distribution

    p_max = float(np.max(p))
    entropy = normalized_entropy(p)

    if p_max < p_max_floor:
        return ConfidenceGate(
            False, p_max, entropy,
            f"Classification confidence {p_max:.2f} is below the {p_max_floor:.2f} floor.")
    if entropy > entropy_ceiling:
        return ConfidenceGate(
            False, p_max, entropy,
            f"Classification entropy {entropy:.2f} exceeds the {entropy_ceiling:.2f} ceiling.")

    return ConfidenceGate(True, p_max, entropy)

# =============================================================================
# Utility
# =============================================================================
def sha256_hex(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def verify_model_file(model_path: str, expected_sha256_env: Optional[str] = None) -> bool:
    """Checksum verification hook for supply-chain protection of the ONNX model.

    If the environment variable referenced by `expected_sha256_env` provides an
    expected digest, mismatch is fatal. Otherwise we simply record the digest.
    """
    import os
    try:
        digest = sha256_hex(Path(model_path).read_bytes())
    except OSError:
        return False
    expected = os.environ.get(expected_sha256_env or "MODEL_SHA256", "").strip().lower()
    if expected and expected != digest:
        return False
    print(f">>> [AI READY] Model checksum verified: {digest[:16]}…")
    return True
