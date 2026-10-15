package com.roadwise.backend.config;

import com.roadwise.backend.service.WebAuthnCredentialRepository;
import com.roadwise.backend.service.WebAuthnUserHandleService;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.security.web.webauthn.api.PublicKeyCredentialRpEntity;
import org.springframework.security.web.webauthn.jackson.WebauthnJacksonModule;
import org.springframework.security.web.webauthn.management.WebAuthnRelyingPartyOperations;
import org.springframework.security.web.webauthn.management.Webauthn4JRelyingPartyOperations;

/**
 * Wires the Spring Security WebAuthn module into this application's two extension points.
 *
 * <p>1. The Jackson module: {@link WebauthnJacksonModule} teaches the application's
 * Jackson 3 {@code JsonMapper} — the same instance Spring MVC serialises responses with —
 * how to read and write the WebAuthn types (base64url {@code Bytes}, enums,
 * {@code PublicKeyCredential} and friends). Registering it through Boot's
 * {@link JsonMapperBuilderCustomizer} rather than a private mapper keeps exactly one
 * mapper in the process: options serialised for the ceremony store, options returned to
 * the browser, and credentials parsed from the browser are all handled by the same
 * configuration, so they cannot drift apart.
 *
 * <p>2. The relying-party operations: created only when
 * {@link WebAuthnProperties#isConfigured()} would be true. When the rp-id and origins
 * are unset the bean does not exist, and {@code PasskeyService} answers "unavailable"
 * instead of reaching a half-built relying party.
 */
@Configuration
public class WebAuthnConfig {

    /** Registers the WebAuthn serializers/mixins on the auto-configured mapper. */
    @Bean
    public JsonMapperBuilderCustomizer webauthnJacksonCustomizer() {
        return builder -> builder.addModule(new WebauthnJacksonModule());
    }

    @Bean
    @Conditional(WebAuthnConfig.Configured.class)
    public WebAuthnRelyingPartyOperations webAuthnRelyingPartyOperations(
            WebAuthnUserHandleService userEntities,
            WebAuthnCredentialRepository credentials,
            WebAuthnProperties properties) {
        // The two repositories are this application's stores (user_mfa_factors and
        // webauthn_user_handles) seen through the interfaces the module expects; the
        // Jdbc* implementations the module also ships are deliberately not used.
        return new Webauthn4JRelyingPartyOperations(
                userEntities,
                credentials,
                PublicKeyCredentialRpEntity.builder()
                        .name("RoadWise")
                        .id(properties.getRpId())
                        .build(),
                properties.getAllowedOrigins());
    }

    /** Matches only when webauthn.rp-id is present — see {@link WebAuthnProperties}. */
    static class Configured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String rpId = context.getEnvironment().getProperty("webauthn.rp-id", "");
            return rpId != null && !rpId.trim().isEmpty();
        }
    }
}