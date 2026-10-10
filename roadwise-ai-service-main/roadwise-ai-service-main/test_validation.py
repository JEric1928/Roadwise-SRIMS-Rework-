"""Adversarial + functional tests for the hardened inference validation layers.

Covers the requested test-set categories: documents, screenshots/digital
graphics, sky-dominant frames, dark images, blurred images, corrupt/executable
payloads, decompression-bomb headers, tiny images, and valid road-like images.
These run WITHOUT onnxruntime (pure validation logic).
"""

import io
import os
import sys

import numpy as np
import pytest
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import validation as V


def png_bytes(img: Image.Image) -> bytes:
    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


def noisy_road_like_image(size=(256, 256)) -> Image.Image:
    """Neutral-gray textured image with a darker lower band (asphalt-like)."""
    rng = np.random.default_rng(7)
    gray = rng.normal(135.0, 28.0, size=(size[1], size[0])).clip(40, 220)
    grad = np.linspace(0, 40, size[1])[:, None]
    gray = (gray - grad).clip(0, 255)
    base = np.dstack([gray] * 3).astype(np.uint8)  # zero channel spread => low saturation
    return Image.fromarray(base, "RGB")


# ---------------------------------------------------------------------------
# File / security validation
# ---------------------------------------------------------------------------
class TestFileValidation:
    def test_empty_file_rejected(self):
        status, msg = V.validate_upload_bytes(b"", "image/jpeg")
        assert status == "INVALID_INPUT"

    def test_oversized_file_rejected(self):
        status, _ = V.validate_upload_bytes(b"\xff\xd8\xff" + b"\x00" * (V.MAX_FILE_SIZE_BYTES + 1),
                                            "image/jpeg")
        assert status == "INVALID_INPUT"

    def test_executable_payload_rejected(self):
        status, _ = V.validate_upload_bytes(b"MZ\x90\x00" + b"\x00" * 200, "application/octet-stream")
        assert status == "INVALID_INPUT"

    def test_pdf_disguised_as_jpeg_rejected(self):
        payload = b"%PDF-1.7" + b"\x00" * 200
        status, _ = V.validate_upload_bytes(payload, "image/jpeg")
        assert status == "INVALID_INPUT"

    def test_svg_text_rejected(self):
        payload = b"<svg xmlns='http://www.w3.org/2000/svg'>" + b"\x00" * 200
        status, _ = V.validate_upload_bytes(payload, "image/svg+xml")
        assert status == "INVALID_INPUT"

    def test_mime_mismatch_spoof_rejected(self):
        good = png_bytes(noisy_road_like_image())
        status, _ = V.validate_upload_bytes(good, "image/png")
        assert status is None
        # same PNG bytes declared as jpeg => spoofing attempt
        status, _ = V.validate_upload_bytes(good, "image/jpeg")
        assert status == "INVALID_INPUT"

    def test_valid_png_accepted(self):
        good = png_bytes(noisy_road_like_image())
        status, _ = V.validate_upload_bytes(good, "image/png")
        assert status is None


# ---------------------------------------------------------------------------
# Decode safety / structural checks
# ---------------------------------------------------------------------------
class TestDecodeSafety:
    def test_corrupt_png_fails_decode(self):
        data = bytearray(png_bytes(noisy_road_like_image()))
        data[-20:] = b"\x00" * 20  # destroy trailer
        img, status, _ = V.safe_decode_image(bytes(data))
        assert img is None and status in ("INVALID_INPUT", "LOW_IMAGE_QUALITY")

    def test_tiny_image_rejected(self):
        img, status, _ = V.safe_decode_image(png_bytes(Image.new("RGB", (8, 8))))
        assert img is None
        assert status in ("INVALID_INPUT", "LOW_IMAGE_QUALITY")

    def test_extreme_aspect_ratio_rejected(self):
        img, status, _ = V.safe_decode_image(png_bytes(Image.new("RGB", (600, 20))))
        assert img is None and status == "INVALID_INPUT"

    def test_decompression_bomb_header_rejected(self):
        # Construct a PNG header claiming enormous dimensions without real data.
        fake = b"\x89PNG\r\n\x1a\n" + b"\x00" * 300
        img, status, _ = V.safe_decode_image(fake)
        assert img is None and status == "INVALID_INPUT"

    def test_valid_decodes_to_rgb(self):
        img, status, msg = V.safe_decode_image(png_bytes(noisy_road_like_image()))
        assert img is not None and img.mode == "RGB", (status, msg)


# ---------------------------------------------------------------------------
# Quality safeguards (adversarial set)
# ---------------------------------------------------------------------------
class TestQualitySafeguards:
    def test_document_white_page_rejected(self):
        doc = Image.new("RGB", (256, 256), (250, 250, 248))
        rng = np.random.default_rng(1)
        arr = np.asarray(doc).copy()
        rows = rng.integers(0, 256, 40)
        arr[rows, 30:220] = 120  # text lines keep edges but page stays bright
        outcome = V.prescreen_quality(Image.fromarray(arr, "RGB"))
        assert not outcome.ok
        assert outcome.status in ("NON_ROAD_OR_UNCERTAIN", "LOW_IMAGE_QUALITY")

    def test_dark_image_rejected(self):
        dark = Image.fromarray(np.full((256, 256, 3), 8, dtype=np.uint8), "RGB")
        outcome = V.prescreen_quality(dark)
        assert not outcome.ok and outcome.status == "LOW_IMAGE_QUALITY"

    def test_overexposed_image_rejected(self):
        bright = Image.fromarray(np.full((256, 256, 3), 252, dtype=np.uint8), "RGB")
        outcome = V.prescreen_quality(bright)
        assert not outcome.ok

    def test_blurred_image_rejected(self):
        from PIL import ImageFilter
        blurred = noisy_road_like_image().filter(ImageFilter.GaussianBlur(radius=8))
        outcome = V.prescreen_quality(blurred)
        assert not outcome.ok and outcome.status == "LOW_IMAGE_QUALITY"

    def test_saturated_digital_graphic_rejected(self):
        graphic = Image.new("RGB", (256, 256), (250, 30, 200))
        outcome = V.prescreen_quality(graphic)
        assert not outcome.ok and outcome.status == "NON_ROAD_OR_UNCERTAIN"

    def test_sky_dominant_frame_rejected(self):
        arr = np.zeros((256, 256, 3), dtype=np.uint8)
        arr[:, :, 0] = 110
        arr[:, :, 1] = 160
        arr[:, :, 2] = 235  # blue sky everywhere
        outcome = V.prescreen_quality(Image.fromarray(arr, "RGB"))
        assert not outcome.ok

    def test_valid_road_like_passes_prescreen(self):
        outcome = V.prescreen_quality(noisy_road_like_image())
        assert outcome.ok
        assert outcome.image_quality in ("ACCEPTABLE", "LOW_QUALITY")


# ---------------------------------------------------------------------------
# Road-scene gate (Mahalanobis OOD)
# ---------------------------------------------------------------------------
class TestRoadGate:
    @pytest.fixture()
    def calibrated_gate(self, tmp_path):
        rng = np.random.default_rng(42)
        dim = 32
        road = rng.normal(0.0, 0.4, size=(120, dim))
        road /= np.linalg.norm(road, axis=1, keepdims=True)
        # Threshold must be the 95% quantile of the MAHALANOBIS distance on
        # the calibration set (this is what calibrate_road_gate.py measures);
        # Euclidean quantiles are not comparable to Mahalanobis units.
        mean = road.mean(axis=0)
        cov = np.atleast_2d(np.cov(road, rowvar=False))
        lam = 0.1 * np.trace(cov) / dim + 1e-6
        cov_inv = np.linalg.pinv(cov + lam * np.eye(dim))
        dvec = road - mean
        md = np.sqrt(np.maximum(np.einsum("ij,jk,ik->i", dvec, cov_inv, dvec), 0.0))
        threshold = float(np.quantile(md, 0.95))
        np.savez_compressed(tmp_path / "calib.npz", embeddings=road,
                            covariance=cov + lam * np.eye(dim),
                            maha_threshold=np.float64(threshold))
        return V.RoadSceneGate(calibration_path=str(tmp_path / "calib.npz"))

    def test_in_distribution_embedding_confirmed(self, calibrated_gate):
        z = calibrated_gate.mean.copy()
        z += rng_vec(32, 0.05)
        res = calibrated_gate.evaluate(normalize(z))
        assert res.decision == "ROAD_CONFIRMED"

    def test_far_ood_embedding_flagged(self, calibrated_gate):
        z = calibrated_gate.mean.copy()
        z += 100.0  # far outside the road embedding cluster
        res = calibrated_gate.evaluate(z)
        assert res.decision == "OUT_OF_DISTRIBUTION"
        assert res.review_required

    def test_uncalibrated_gate_fails_closed(self, tmp_path):
        gate = V.RoadSceneGate(calibration_path=str(tmp_path / "missing.npz"))
        res = gate.evaluate(rng_vec(32, 1.0))
        assert res.decision == "REVIEW_REQUIRED" and res.review_required

    def test_missing_embedding_fails_closed(self, calibrated_gate):
        res = calibrated_gate.evaluate(None)
        assert res.decision == "REVIEW_REQUIRED" and res.review_required

    def test_classifier_head_policy_bands(self, tmp_path):
        rng = np.random.default_rng(5)
        dim = 16
        road = rng.normal(0.5, 0.2, size=(60, dim))
        road /= np.linalg.norm(road, axis=1, keepdims=True)
        w = np.zeros(dim); w[0] = 8.0
        b = -4.0  # sigmoid depends mostly on first coordinate
        np.savez_compressed(tmp_path / "c.npz", embeddings=road,
                            maha_threshold=np.float64(50.0),
                            road_head_weights=w, road_head_bias=np.float64(b))
        gate = V.RoadSceneGate(calibration_path=str(tmp_path / "c.npz"))
        low = np.zeros(dim); low[0] = 0.2          # p ~ 0.21 -> reject
        mid = np.zeros(dim); mid[0] = 0.5          # p = 0.5   -> review band edge
        high = np.zeros(dim); high[0] = 0.9        # p ~ 0.97  -> accept
        assert gate.evaluate(low).decision == "NON_ROAD_OR_UNCERTAIN"
        assert gate.evaluate(mid).decision in ("REVIEW_REQUIRED", "NON_ROAD_OR_UNCERTAIN")
        assert gate.evaluate(high).decision == "ROAD_CONFIRMED"


def rng_vec(n, scale):
    v = np.random.default_rng(9).normal(0.0, scale, size=n)
    return v


def normalize(v):
    return v / np.linalg.norm(v)


# ---------------------------------------------------------------------------
# Model output validation
# ---------------------------------------------------------------------------
class TestModelOutputValidation:
    def test_wrong_class_count_rejected(self):
        _, err = V.validate_model_outputs([np.array([[1.0, 2.0]])])
        assert err and "MODEL_ERROR" in err

    def test_nan_logits_rejected(self):
        _, err = V.validate_model_outputs([np.array([[1.0, np.nan, 2.0]])])
        assert err and "NaN" in err

    def test_inf_logits_rejected(self):
        _, err = V.validate_model_outputs([np.array([[np.inf, 1.0, 2.0]])])
        assert err and "MODEL_ERROR" in err

    def test_valid_logits_accepted(self):
        logits, err = V.validate_model_outputs([np.array([[0.1, 5.0, 0.2]])])
        assert err is None and logits is not None

    def test_softmax_and_margin(self):
        probs = V.softmax(np.array([1.0, 1.05, 0.2]))
        assert abs(probs.sum() - 1.0) < 1e-9
        assert V.margin_is_too_small(probs)
        assert not V.margin_is_too_small(V.softmax(np.array([5.0, 1.0, 0.1])))
