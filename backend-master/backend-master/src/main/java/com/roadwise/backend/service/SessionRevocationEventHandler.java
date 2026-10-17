package com.roadwise.backend.service;

import com.roadwise.backend.config.LiveWebSocketSessionRegistry;
import com.roadwise.backend.config.SessionRevocationRabbitConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Applies committed revocations locally and broadcasts them to other API instances. */
@Component
public class SessionRevocationEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SessionRevocationEventHandler.class);

    private final LiveWebSocketSessionRegistry sessionRegistry;
    private final RabbitTemplate rabbitTemplate;
    private final boolean busEnabled;

    public SessionRevocationEventHandler(
            LiveWebSocketSessionRegistry sessionRegistry,
            RabbitTemplate rabbitTemplate,
            @Value("${roadwise.bus.enabled:false}") boolean busEnabled) {
        this.sessionRegistry = sessionRegistry;
        this.rabbitTemplate = rabbitTemplate;
        this.busEnabled = busEnabled;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onSessionRevoked(SessionRevokedEvent event) {
        sessionRegistry.closeUserSessions(event.userId());
        if (busEnabled) {
            try {
                rabbitTemplate.convertAndSend(
                        SessionRevocationRabbitConfig.EXCHANGE_NAME, "", event.userId().toString());
            } catch (RuntimeException ex) {
                // The database revocation is already committed. Make failed cluster fan-out
                // visible for operations instead of hiding it behind a best-effort refresh.
                log.error("Could not publish session revocation for user {} to RabbitMQ", event.userId(), ex);
            }
        }
    }
}
