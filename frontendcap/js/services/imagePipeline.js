/**
 * Client-side photo pipeline for field reports.
 *
 * Three jobs, in this order:
 *   1. Decide which of the selected files are admissible (count, type, size).
 *   2. Standardise the aspect ratio by centre-cropping, then downscale.
 *   3. Re-encode as JPEG, stepping quality down until the blob is small enough.
 *
 * Why before upload rather than on the server: a modern handset produces 4-8 MB
 * frames, and three of them will not cross a municipal 3G uplink in any reasonable
 * time — nor should they. Compression here keeps the multipart body in the low
 * hundreds of kilobytes while preserving enough detail for the severity model
 * (which resizes to 224x224 anyway).
 *
 * The pure geometry and validation helpers are exported separately from the canvas
 * work so they can be tested under plain `node`, where no canvas exists.
 */

export const MAX_PHOTOS = 3;

/** Long edge after downscaling. 1600px leaves headroom above the model's 224px input. */
export const TARGET_LONG_EDGE = 1600;

/** 4:3 is the aspect the three capture angles are specified against. */
export const TARGET_ASPECT = 4 / 3;

/** Above this, JPEG quality steps down again. */
export const MAX_OUTPUT_BYTES = 900 * 1024;

/** Quality ladder, tried in order until the blob fits. */
export const QUALITY_STEPS = Object.freeze([0.82, 0.7, 0.6, 0.5]);

/** Reject before decoding: these cannot be re-encoded usefully. */
export const ACCEPTED_MIME = Object.freeze(['image/jpeg', 'image/png', 'image/webp', 'image/heic', 'image/heif']);

/** Anything under this is a thumbnail or a corrupt upload. */
export const MIN_SOURCE_BYTES = 1024;

/** Backstop against a client sending absurd input; the server caps at 8 MB too. */
export const MAX_SOURCE_BYTES = 12 * 1024 * 1024;

/**
 * Partition a FileList/array into admissible photos and reasons for rejection.
 *
 * Extra files beyond {@link MAX_PHOTOS} are rejected by name rather than silently
 * dropped, because "I attached four photos and only three arrived" is precisely the
 * failure an inspector cannot debug in the field.
 */
export function validateSelection(files) {
  const list = Array.from(files || []);
  const accepted = [];
  const rejected = [];

  for (const file of list) {
    if (!file) continue;
    const name = file.name || 'photo';

    if (accepted.length >= MAX_PHOTOS) {
      rejected.push({ name, reason: `Only ${MAX_PHOTOS} photos are allowed; this one was not attached.` });
      continue;
    }
    const type = (file.type || '').toLowerCase();
    const extensionOk = /\.(jpe?g|png|webp|heic|heif)$/i.test(name);
    if (!ACCEPTED_MIME.includes(type) && !(type === '' && extensionOk)) {
      rejected.push({ name, reason: 'Unsupported format. Use a JPEG, PNG or WEBP photo.' });
      continue;
    }
    if (typeof file.size === 'number' && file.size < MIN_SOURCE_BYTES) {
      rejected.push({ name, reason: 'File is too small to be a photo.' });
      continue;
    }
    if (typeof file.size === 'number' && file.size > MAX_SOURCE_BYTES) {
      rejected.push({ name, reason: 'File is too large (over 12 MB).' });
      continue;
    }
    accepted.push(file);
  }

  return { accepted, rejected };
}

/**
 * Largest centred rectangle with the target aspect ratio.
 *
 * Centre-cropping rather than letterboxing: black bars would enter the inference
 * pipeline as constant regions and shift the edge statistics the quality gate
 * relies on.
 */
export function computeCrop(sourceWidth, sourceHeight, aspect = TARGET_ASPECT) {
  const width = Number(sourceWidth);
  const height = Number(sourceHeight);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return { sx: 0, sy: 0, sw: 0, sh: 0 };
  }
  if (!Number.isFinite(aspect) || aspect <= 0) {
    return { sx: 0, sy: 0, sw: Math.round(width), sh: Math.round(height) };
  }

  const sourceAspect = width / height;
  if (sourceAspect > aspect) {
    // Too wide: trim the sides.
    const sw = Math.round(height * aspect);
    return { sx: Math.round((width - sw) / 2), sy: 0, sw, sh: Math.round(height) };
  }
  // Too tall: trim top and bottom.
  const sh = Math.round(width / aspect);
  return { sx: 0, sy: Math.round((height - sh) / 2), sw: Math.round(width), sh };
}

/**
 * Output dimensions after the crop, capped by {@link TARGET_LONG_EDGE}.
 * Never upscales: a 640px source stays 640px rather than being interpolated up.
 */
export function computeTargetSize(croppedWidth, croppedHeight, longEdge = TARGET_LONG_EDGE) {
  const width = Number(croppedWidth);
  const height = Number(croppedHeight);
  if (!Number.isFinite(width) || !Number.isFinite(height) || width <= 0 || height <= 0) {
    return { width: 0, height: 0 };
  }
  const longest = Math.max(width, height);
  if (longest <= longEdge) {
    return { width: Math.round(width), height: Math.round(height) };
  }
  const scale = longEdge / longest;
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/** The perspective for position `index`, matching the server's positional fallback. */
export function perspectiveForIndex(index) {
  const order = ['CLOSE_UP', 'WIDE_ANGLE', 'PERPENDICULAR'];
  return order[index] || order[order.length - 1];
}

function loadImageElement(file) {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined' || typeof URL === 'undefined' || !URL.createObjectURL) {
      reject(new Error('Image decoding is unavailable in this environment.'));
      return;
    }
    const url = URL.createObjectURL(file);
    const image = new Image();
    image.onload = () => {
      URL.revokeObjectURL(url);
      resolve(image);
    };
    image.onerror = () => {
      URL.revokeObjectURL(url);
      reject(new Error('The selected file could not be decoded as an image.'));
    };
    image.src = url;
  });
}

function canvasToBlob(canvas, quality) {
  return new Promise((resolve, reject) => {
    if (typeof canvas.toBlob !== 'function') {
      reject(new Error('Canvas encoding is unavailable in this environment.'));
      return;
    }
    canvas.toBlob((blob) => {
      if (blob) resolve(blob);
      else reject(new Error('The photo could not be compressed.'));
    }, 'image/jpeg', quality);
  });
}

/**
 * Crop, downscale and re-encode one photo.
 *
 * @returns {Promise<{blob: Blob, width: number, height: number, quality: number, bytes: number}>}
 */
export async function preparePhoto(file, options = {}) {
  const {
    longEdge = TARGET_LONG_EDGE,
    aspect = TARGET_ASPECT,
    maxBytes = MAX_OUTPUT_BYTES,
    qualitySteps = QUALITY_STEPS
  } = options;

  const image = await loadImageElement(file);
  const sourceWidth = image.naturalWidth || image.width;
  const sourceHeight = image.naturalHeight || image.height;
  const crop = computeCrop(sourceWidth, sourceHeight, aspect);
  const target = computeTargetSize(crop.sw, crop.sh, longEdge);

  if (!target.width || !target.height) {
    throw new Error('The selected photo has no usable dimensions.');
  }

  const canvas = document.createElement('canvas');
  canvas.width = target.width;
  canvas.height = target.height;
  const context = canvas.getContext('2d');
  if (!context) {
    throw new Error('This browser cannot process images.');
  }
  context.imageSmoothingEnabled = true;
  context.imageSmoothingQuality = 'high';
  context.drawImage(image, crop.sx, crop.sy, crop.sw, crop.sh, 0, 0, target.width, target.height);

  let blob = null;
  let usedQuality = qualitySteps[qualitySteps.length - 1];
  for (const quality of qualitySteps) {
    blob = await canvasToBlob(canvas, quality);
    usedQuality = quality;
    if (blob.size <= maxBytes) break;
  }

  return {
    blob,
    width: target.width,
    height: target.height,
    quality: usedQuality,
    bytes: blob.size
  };
}

/**
 * Run the whole selection through {@link preparePhoto}.
 *
 * A single unprocessable file does not abort the batch: the others are returned and
 * the failure is reported alongside them, so the officer keeps their work.
 */
export async function preparePhotos(files, options = {}) {
  const { accepted, rejected } = validateSelection(files);
  const prepared = [];
  const failures = [...rejected];

  for (let index = 0; index < accepted.length; index += 1) {
    const file = accepted[index];
    try {
      const result = await preparePhoto(file, options);
      prepared.push({
        blob: result.blob,
        perspective: perspectiveForIndex(index),
        name: file.name || `photo-${index + 1}.jpg`,
        width: result.width,
        height: result.height,
        bytes: result.bytes
      });
    } catch (error) {
      failures.push({ name: file.name || `photo-${index + 1}`, reason: error.message });
    }
  }

  return { prepared, failures };
}
