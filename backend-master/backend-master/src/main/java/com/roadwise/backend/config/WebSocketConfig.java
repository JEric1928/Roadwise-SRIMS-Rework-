package com.roadwise.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    /**
     * Comma-separated origins allowed to open the live-update socket, e.g.
     * {@code https://roadwise-csjdm.com}. When unset, the configured frontend
     * URL plus localhost development origins are used — never "*".
     */
    @Value("${websocket.allowed-origins:}")
    private String allowedOrigins;

    @Value("${frontend.url:https://roadwise-csjdm.com}")
    private String frontendUrl;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // This is the "radio frequency" our frontend will listen to
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // This is the endpoint the frontend connects to.
        registry.addEndpoint("/ws-live")
                .setAllowedOriginPatterns(resolveAllowedOrigins())
                .withSockJS(); // Provides a fallback if a user has a very old browser
    }

    private String[] resolveAllowedOrigins() {
        List<String> patterns = new ArrayList<>();
        if (allowedOrigins != null && !allowedOrigins.isBlank()) {
            for (String origin : allowedOrigins.split(",")) {
                if (!origin.isBlank()) {
                    patterns.add(origin.trim());
                }
            }
        }
        if (!patterns.isEmpty()) {
            return patterns.toArray(new String[0]);
        }
        // Secure default: the configured frontend origin plus local development.
        patterns.add(frontendUrl);
        patterns.add("http://localhost:*");
        patterns.add("http://127.0.0.1:*");
        return patterns.toArray(new String[0]);
    }
}
