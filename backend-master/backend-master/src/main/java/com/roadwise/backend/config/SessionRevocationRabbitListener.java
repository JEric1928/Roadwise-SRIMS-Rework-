package com.roadwise.backend.config;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/** Receives cross-instance revocations and closes sockets owned by this API instance. */
@Component
@ConditionalOnProperty(prefix = "roadwise.bus", name = "enabled", havingValue = "true")
public class SessionRevocationRabbitListener {

    private final LiveWebSocketSessionRegistry sessionRegistry;

    public SessionRevocationRabbitListener(LiveWebSocketSessionRegistry sessionRegistry) {
        this.sessionRegistry = sessionRegistry;
    }

    @RabbitListener(queues = "#{@sessionRevocationQueue.name}")
    public void closeRevokedUserSockets(String userId) {
        try {
            sessionRegistry.closeUserSessions(Long.valueOf(userId));
        } catch (NumberFormatException ignored) {
            // Invalid broker messages must not be interpreted as a user identifier.
        }
    }
}
