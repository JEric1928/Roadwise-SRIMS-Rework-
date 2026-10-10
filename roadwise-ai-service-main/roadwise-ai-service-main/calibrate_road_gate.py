"""
Calibration utility for the RoadWise road-scene gate (Mahalanobis OOD).

Procedure:
    1. Extract ResNet-style penultimate embeddings from a directory of images.
    2. Fit mu_road and a shrunk covariance Sigma_road using ONLY ROAD_ID images.
    3. Compute Mahalanobis distances for ROAD_ID / NEAR_OOD / FAR_OOD groups.
    4. Select the threshold on a safety-oriented operating point measured on
       real data (e.g. accept >=95% of true road images) - NEVER an arbitrary
       value like 2.0 or 3.0.
    5. Persist everything to road_calibration.npz for the inference service.

The production model is exported as ONNX. Export it with TWO outputs
(severity logits + pre-classifier pooling embedding), or point
--embedding-output-index at whichever output already carries the penultimate
feature vector (typically 512-d for ResNet-34; confirm against the actual
architecture behind 'rdd2022_d4_resnet38.onnx').

Usage:
    python calibrate_road_gate.py \
        --road-dir data/ROAD_ID --near-ood-dir data/NEAR_OOD \
        --far-ood-dir data/FAR_OOD --model rdd2022_d4_resnet38.onnx \
        --embedding-output-index 1 --target-road-acceptance 0.95 \
        --out road_calibration.npz

Re-run whenever the model, preprocessing, camera source, or image domain
changes, and validate the chosen threshold on an untouched hold-out set.
"""

import argparse
import sys
from pathlib import Path

import numpy as np

ALLOWED_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}


def load_image_tensor(path: Path) -> np.ndarray:
    """Decode with the same safe pipeline used in production."""
    from validation import safe_decode_image

    contents = path.read_bytes()
    img, status, message = safe_decode_image(contents)
    if img is None:
        raise ValueError(f"{path.name}: rejected during decode ({status}: {message})")
    resized = img.resize((224, 224))
    arr = np.asarray(resized, dtype=np.float32) / 255.0
    return np.transpose(arr, (2, 0, 1))[None, :, :, :]  # NCHW batch of 1


def extract_embeddings(image_dir: Path, session, input_name: str, emb_index: int) -> np.ndarray:
    vectors = []
    for p in sorted(image_dir.iterdir()):
        if p.suffix.lower() not in ALLOWED_SUFFIXES:
            continue
        try:
            tensor = load_image_tensor(p)
            outputs = session.run(None, {input_name: tensor})
            vec = np.asarray(outputs[emb_index]).reshape(-1).astype(np.float64)
            norm = np.linalg.norm(vec)
            if norm <= 1e-12 or not np.all(np.isfinite(vec)):
                print(f"  ! skipping {p.name}: invalid embedding", file=sys.stderr)
                continue
            vectors.append(vec / norm)
        except Exception as exc:  # noqa: BLE001
            print(f"  ! skipping {p.name}: {exc}", file=sys.stderr)
    if not vectors:
        return np.zeros((0, 0))
    return np.stack(vectors)


def fit_shrunk_gaussian(emb: np.ndarray):
    """Mean + Ledoit-Wolf style shrunk covariance (never singular by construction)."""
    mean = emb.mean(axis=0)
    cov = np.atleast_2d(np.cov(emb, rowvar=False))
    d = cov.shape[0]
    n = emb.shape[0]
    target = np.eye(d) * np.trace(cov) / d
    mu_shrink = min(1.0, max(0.01, d / (d + float(n))))
    cov = (1.0 - mu_shrink) * cov + mu_shrink * target
    cov += 1e-6 * np.eye(d)
    return mean, cov


def main():
    ap = argparse.ArgumentParser(description="Calibrate the RoadWise road-scene Mahalanobis gate")
    ap.add_argument("--road-dir", required=True, type=Path, help="ROAD_ID calibration images")
    ap.add_argument("--near-ood-dir", type=Path, default=None)
    ap.add_argument("--far-ood-dir", type=Path, default=None)
    ap.add_argument("--model", default="rdd2022_d4_resnet38.onnx")
    ap.add_argument("--embedding-output-index", type=int, default=1,
                    help="Index of the ONNX output carrying the penultimate embedding")
    ap.add_argument("--target-road-acceptance", type=float, default=0.95,
                    help="Fraction of true road images the threshold must accept")
    ap.add_argument("--out", default="road_calibration.npz", type=Path)
    args = ap.parse_args()

    try:
        import onnxruntime as ort
    except ImportError:
        print("onnxruntime is required for calibration.", file=sys.stderr)
        sys.exit(1)

    session = ort.InferenceSession(args.model, providers=["CPUExecutionProvider"])
    input_name = session.get_inputs()[0].name

    print(f">>> Extracting ROAD_ID embeddings from {args.road_dir} ...")
    road = extract_embeddings(args.road_dir, session, input_name, args.embedding_output_index)
    if road.shape[0] < 10:
        print("Need at least 10 valid road calibration images.", file=sys.stderr)
        sys.exit(1)
    print(f"    {road.shape[0]} embeddings, dim={road.shape[1]}")

    mean, cov = fit_shrunk_gaussian(road)
    cov_inv = np.linalg.pinv(cov)

    def maha(emb):
        if emb.shape[0] == 0:
            return np.zeros((0,))
        dvec = emb - mean
        return np.sqrt(np.maximum(np.einsum("ij,jk,ik->i", dvec, cov_inv, dvec), 0.0))

    road_dist = maha(road)
    near_dist = np.zeros((0,))
    far_dist = np.zeros((0,))
    if args.near_ood_dir and args.near_ood_dir.is_dir():
        print(f">>> Extracting NEAR_OOD embeddings from {args.near_ood_dir} ...")
        near_dist = maha(extract_embeddings(args.near_ood_dir, session, input_name,
                                            args.embedding_output_index))
    if args.far_ood_dir and args.far_ood_dir.is_dir():
        print(f">>> Extracting FAR_OOD embeddings from {args.far_ood_dir} ...")
        far_dist = maha(extract_embeddings(args.far_ood_dir, session, input_name,
                                           args.embedding_output_index))

    # Safety-oriented threshold selection on measured data (not guessed).
    candidates = [float(np.quantile(road_dist, args.target_road_acceptance))]
    for other, label in ((near_dist, "NEAR_OOD"), (far_dist, "FAR_OOD")):
        if other.size:
            overlap_edge = float(np.quantile(other, 0.05))
            if overlap_edge > candidates[0]:
                candidates.append(overlap_edge)
                print(f"    WARNING: {label} distances overlap the road distribution; "
                      f"consider training a dedicated road/non-road classifier.")
    threshold = max(candidates)

    fa_near = float((near_dist <= threshold).mean()) if near_dist.size else float("nan")
    fa_far = float((far_dist <= threshold).mean()) if far_dist.size else float("nan")
    accepted = float((road_dist <= threshold).mean())
    print("\n>>> Calibration summary")
    print(f"    road acceptance @ threshold {threshold:.4f}: {accepted:.3f}")
    print(f"    false-accept NEAR_OOD: {fa_near:.3f}")
    print(f"    false-accept FAR_OOD : {fa_far:.3f}")

    np.savez_compressed(
        args.out,
        embeddings=road,
        mean=mean,
        covariance=cov,
        maha_threshold=np.float64(threshold),
        target_acceptance=np.float64(args.target_road_acceptance),
        model=np.array(args.model),
    )
    print(f">>> Wrote {args.out}. Validate on a held-out set before deploying, "
          f"and recalibrate whenever the model or image domain changes.")


if __name__ == "__main__":
    main()
