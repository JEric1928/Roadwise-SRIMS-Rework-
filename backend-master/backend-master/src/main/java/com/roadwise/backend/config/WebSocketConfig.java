package com.roadwise.backend.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.ArrayList;
import java.util.List;

@Configuration
@EnableWebSocketMessageBroker
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {
    /** Where the broker routes subscriptions. Clients may subscribe, never publish, here. */
    public static final String BROKER_DESTINATION_PREFIX = "/topic";

    /** Where clients may address the application. The only prefix a client SEND may use. */
    public static final String APPLICATION_DESTINATION_PREFIX = "/app";

    private final WebSocketAuthInterceptor authInterceptor;
    private final LiveWebSocketSessionRegistry sessionRegistry;

    public WebSocketConfig(
            WebSocketAuthInterceptor authInterceptor,
            LiveWebSocketSessionRegistry sessionRegistry) {
        this.authInterceptor = authInterceptor;
        this.sessionRegistry = sessionRegistry;
    }

    /**
     * Comma-separated origins allowed to open the live-update socket, e.g.
     * {@code https://roadwise-csjdm.com}. When unset, the configured frontend
     * URL plus localhost development origins are used — never "*".
     */
    @Value("${websocket.allowed-origins:}")
    private String allowedOrigins;

    @Value("${frontend.url:https://roadwise-csjdm.com}")
    private String frontendUrl;

    @Value("${websocket.broker-relay.enabled:false}")
    private boolean brokerRelayEnabled;

    @Value("${websocket.broker-relay.host:}")
    private String brokerRelayHost;

    @Value("${websocket.broker-relay.port:61613}")
    private int brokerRelayPort;

    @Value("${websocket.broker-relay.username:}")
    private String brokerRelayUsername;

    @Value("${websocket.broker-relay.password:}")
    private String brokerRelayPassword;

    @Value("${websocket.broker-relay.virtual-host:/}")
    private String brokerRelayVirtualHost;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        // These prefixes are the contract between the broker and the authorization rules in
        // WebSocketAuthInterceptor. Keep the simple broker for local development, but require
        // an explicitly configured external relay in production so instances share fan-out.
        if (brokerRelayEnabled) {
            if (brokerRelayHost == null || brokerRelayHost.isBlank()
                    || brokerRelayUsername == null || brokerRelayUsername.isBlank()
                    || brokerRelayPassword == null || brokerRelayPassword.isBlank()
                    || brokerRelayVirtualHost == null || brokerRelayVirtualHost.isBlank()) {
                throw new IllegalStateException(
                        "RabbitMQ STOMP relay is enabled but host, username, password, or virtual host is missing.");
            }
            var relay = config.enableStompBrokerRelay(BROKER_DESTINATION_PREFIX);
            relay.setRelayHost(brokerRelayHost);
            relay.setRelayPort(brokerRelayPort);
            relay.setClientLogin(brokerRelayUsername);
            relay.setClientPasscode(brokerRelayPassword);
            relay.setSystemLogin(brokerRelayUsername);
            relay.setSystemPasscode(brokerRelayPassword);
            relay.setVirtualHost(brokerRelayVirtualHost);
        } else {
            config.enableSimpleBroker(BROKER_DESTINATION_PREFIX);
        }
        config.setApplicationDestinationPrefixes(APPLICATION_DESTINATION_PREFIX);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // This is the endpoint the frontend connects to.
        registry.addEndpoint("/ws-live")
                .setAllowedOriginPatterns(resolveAllowedOrigins())
                .withSockJS(); // Provides a fallback if a user has a very old browser
    }

    /**
     * Registers the authorization rules on the inbound channel.
     *
     * <p>Interceptors here run before the broker, so a refused frame never reaches
     * {@code SimpleBrokerMessageHandler}. A throw propagates out of the channel, which
     * {@code StompSubProtocolHandler} turns into an ERROR frame followed by a close -- so a
     * refused CONNECT ends the connection rather than leaving a half-authenticated session.
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(authInterceptor);
    }

    @Override
    public void configureWebSocketTransport(
            org.springframework.web.socket.config.annotation.WebSocketTransportRegistration registration) {
        registration.addDecoratorFactory(sessionRegistry);
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
