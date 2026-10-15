/**
 * WebAuthn ceremony helpers.
 *
 * The server speaks the spec's JSON dialect: binary fields (challenges,
 * credential ids, signatures) travel as base64url strings without padding. The
 * browser API speaks ArrayBuffer. These functions are the single translation
 * point between the two.
 *
 * They are deliberately pure -- no navigator, no window, no atob/btoa -- so the
 * exact code that ships in the bundle can be exercised by the Node test harness
 * (test.js) without a browser.
 */

const BASE64URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

/**
 * Fields the browser accepts for PublicKeyCredentialCreationOptions /
 * PublicKeyCredentialRequestOptions. Server payloads are whitelisted through
 * these rather than spread wholesale, so a future server-side field that the
 * browser does not understand cannot break the ceremony.
 */
const CREATION_FIELDS = [
  "rp", "user", "challenge", "pubKeyCredParams", "timeout",
  "excludeCredentials", "authenticatorSelection", "attestation", "extensions"
];
const REQUEST_FIELDS = [
  "challenge", "timeout", "rpId", "allowCredentials", "userVerification", "extensions"
];

/** @return {Uint8Array} from a base64url string (padding tolerated). */
export function base64UrlToBytes(value) {
  if (typeof value !== "string") {
    throw new TypeError("Expected a base64url string.");
  }
  let bits = 0;
  let bitCount = 0;
  const out = [];
  for (const ch of value) {
    if (ch === "=" || ch === "\n" || ch === "\r") continue;
    const index = BASE64URL_ALPHABET.indexOf(ch);
    if (index === -1) {
      throw new Error(`Invalid base64url character: ${ch}`);
    }
    bits = (bits << 6) | index;
    bitCount += 6;
    if (bitCount >= 8) {
      bitCount -= 8;
      out.push((bits >> bitCount) & 0xff);
    }
  }
  return Uint8Array.from(out);
}

/** @return {string} base64url without padding, from bytes / an ArrayBuffer. */
export function bytesToBase64Url(bytes) {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  let out = "";
  let bits = 0;
  let bitCount = 0;
  for (const byte of view) {
    bits = (bits << 8) | byte;
    bitCount += 8;
    while (bitCount >= 6) {
      bitCount -= 6;
      out += BASE64URL_ALPHABET[(bits >> bitCount) & 0x3f];
    }
  }
  if (bitCount > 0) {
    out += BASE64URL_ALPHABET[(bits << (6 - bitCount)) & 0x3f];
  }
  return out;
}

/**
 * @return {ArrayBuffer} A standalone buffer (offset zero). Some browsers
 * reject views into a larger buffer for WebAuthn fields, so this copies.
 */
export function toArrayBuffer(bytes) {
  const view = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
  return view.slice().buffer;
}

/** @return {ArrayBuffer} decoded from a base64url string. */
export function base64UrlToArrayBuffer(value) {
  return toArrayBuffer(base64UrlToBytes(value));
}

/** True when this browser can run WebAuthn ceremonies at all. */
export function isWebAuthnSupported() {
  return typeof navigator !== "undefined"
    && !!navigator.credentials
    && typeof navigator.credentials.get === "function"
    && typeof navigator.credentials.create === "function"
    && typeof globalThis.PublicKeyCredential !== "undefined";
}

function requireString(value, name) {
  if (typeof value !== "string" || value.length === 0) {
    throw new Error(`${name} is required to start a passkey ceremony.`);
  }
  return value;
}

function pick(source, fields) {
  const out = {};
  for (const field of fields) {
    if (source[field] !== undefined && source[field] !== null) {
      out[field] = source[field];
    }
  }
  return out;
}

/**
 * Server creation options (spec JSON) -> the `publicKey` argument for
 * navigator.credentials.create(). Challenge and user id are decoded; the
 * declarative fields (rp, pubKeyCredParams, authenticatorSelection, ...) pass
 * through untouched, which is what the spec expects.
 */
export function prepareCreationOptions(options) {
  if (!options || typeof options !== "object") {
    throw new Error("Creation options are required to register a passkey.");
  }
  const prepared = pick(options, CREATION_FIELDS);
  prepared.challenge = base64UrlToArrayBuffer(requireString(options.challenge, "challenge"));
  if (!options.user || typeof options.user !== "object") {
    throw new Error("Creation options are missing the user entity.");
  }
  prepared.user = { ...options.user, id: base64UrlToArrayBuffer(requireString(options.user.id, "user.id")) };
  if (Array.isArray(options.excludeCredentials)) {
    prepared.excludeCredentials = options.excludeCredentials.map((descriptor) => ({
      ...descriptor,
      id: base64UrlToArrayBuffer(requireString(descriptor.id, "excludeCredentials[].id"))
    }));
  }
  return prepared;
}

/**
 * Server request options (spec JSON) -> the `publicKey` argument for
 * navigator.credentials.get(). Only the challenge and the allowed credential
 * ids carry binary data.
 */
export function prepareRequestOptions(options) {
  if (!options || typeof options !== "object") {
    throw new Error("Request options are required to verify a passkey.");
  }
  const prepared = pick(options, REQUEST_FIELDS);
  prepared.challenge = base64UrlToArrayBuffer(requireString(options.challenge, "challenge"));
  if (Array.isArray(options.allowCredentials)) {
    prepared.allowCredentials = options.allowCredentials.map((descriptor) => ({
      ...descriptor,
      id: base64UrlToArrayBuffer(requireString(descriptor.id, "allowCredentials[].id"))
    }));
  }
  return prepared;
}

function extensionResults(credential) {
  if (typeof credential.getClientExtensionResults === "function") {
    return credential.getClientExtensionResults();
  }
  return credential.clientExtensionResults || {};
}

/**
 * The browser's registration credential -> the JSON body the server's
 * credential DTO expects (see WebAuthnCredentialDto). Undefined fields are
 * omitted by JSON.stringify, which matches the spec's optional members.
 */
export function registrationToJson(credential) {
  if (!credential || !credential.response) {
    throw new Error("The authenticator returned no registration response.");
  }
  const response = credential.response;
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment || undefined,
    clientExtensionResults: extensionResults(credential),
    response: {
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      attestationObject: bytesToBase64Url(response.attestationObject),
      transports: typeof response.getTransports === "function" ? response.getTransports() : undefined
    }
  };
}

/** The assertion twin of {@link registrationToJson}, for /verify-mfa. */
export function assertionToJson(credential) {
  if (!credential || !credential.response) {
    throw new Error("The authenticator returned no assertion response.");
  }
  const response = credential.response;
  return {
    id: credential.id,
    rawId: bytesToBase64Url(credential.rawId),
    type: credential.type,
    authenticatorAttachment: credential.authenticatorAttachment || undefined,
    clientExtensionResults: extensionResults(credential),
    response: {
      clientDataJSON: bytesToBase64Url(response.clientDataJSON),
      authenticatorData: bytesToBase64Url(response.authenticatorData),
      signature: bytesToBase64Url(response.signature),
      userHandle: response.userHandle ? bytesToBase64Url(response.userHandle) : undefined
    }
  };
}

/**
 * Maps a rejected navigator.credentials promise onto something a user can act
 * on. The names come from the spec's DOMException list; anything unrecognised
 * keeps its own message rather than being flattened into "failed".
 */
export function describeCredentialError(error) {
  const name = error && error.name ? error.name : "";
  switch (name) {
    case "NotAllowedError":
      return "The passkey prompt was dismissed or timed out. Try again when you are ready.";
    case "InvalidStateError":
      return "This passkey is already registered on this device. Try a different device or remove the old passkey first.";
    case "NotSupportedError":
    case "SecurityError":
      return "This browser or connection cannot use passkeys here. Passkeys need HTTPS (or localhost) and a compatible browser.";
    case "AbortError":
      return "The passkey prompt was cancelled.";
    default:
      return (error && error.message) || "The passkey ceremony could not be completed.";
  }
}
