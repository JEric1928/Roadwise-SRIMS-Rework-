package com.roadwise.backend.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * The browser's credential object, in the exact shape
 * {@code navigator.credentials.create()/get()} produce.
 *
 * <p>Binary fields are base64url without padding, as the WebAuthn spec defines them.
 * The server does not interpret this DTO directly: it is re-parsed through the
 * WebAuthn-jackson-configured mapper into the module's typed
 * {@code PublicKeyCredential<...>} before verification, so the wire shape and the
 * verified shape can never disagree -- the browser sends what the spec says, and the
 * module decides what that means.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebAuthnCredentialDto {

    @NotBlank(message = "Credential id is required")
    private String id;

    private String rawId;

    private String type;

    private Response response;

    private Map<String, Object> clientExtensionResults;

    private String authenticatorAttachment;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRawId() { return rawId; }
    public void setRawId(String rawId) { this.rawId = rawId; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public Response getResponse() { return response; }
    public void setResponse(Response response) { this.response = response; }

    public Map<String, Object> getClientExtensionResults() { return clientExtensionResults; }
    public void setClientExtensionResults(Map<String, Object> clientExtensionResults) {
        this.clientExtensionResults = clientExtensionResults;
    }

    public String getAuthenticatorAttachment() { return authenticatorAttachment; }
    public void setAuthenticatorAttachment(String authenticatorAttachment) {
        this.authenticatorAttachment = authenticatorAttachment;
    }

    /** Shared response payload: attestation fields, assertion fields, or both. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Response {

        private String clientDataJSON;
        private String attestationObject;
        private String authenticatorData;
        private String signature;
        private String userHandle;
        private List<String> transports;

        public String getClientDataJSON() { return clientDataJSON; }
        public void setClientDataJSON(String clientDataJSON) { this.clientDataJSON = clientDataJSON; }

        public String getAttestationObject() { return attestationObject; }
        public void setAttestationObject(String attestationObject) { this.attestationObject = attestationObject; }

        public String getAuthenticatorData() { return authenticatorData; }
        public void setAuthenticatorData(String authenticatorData) { this.authenticatorData = authenticatorData; }

        public String getSignature() { return signature; }
        public void setSignature(String signature) { this.signature = signature; }

        public String getUserHandle() { return userHandle; }
        public void setUserHandle(String userHandle) { this.userHandle = userHandle; }

        public List<String> getTransports() { return transports; }
        public void setTransports(List<String> transports) { this.transports = transports; }
    }
}