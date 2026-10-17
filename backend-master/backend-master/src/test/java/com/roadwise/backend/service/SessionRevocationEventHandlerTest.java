package com.roadwise.backend.service;

import com.roadwise.backend.config.LiveWebSocketSessionRegistry;
import com.roadwise.backend.config.SessionRevocationRabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class SessionRevocationEventHandlerTest {

    @Test
    void closesLocalSocketsAfterCommitAndFansOutWhenTheBusIsEnabled() {
        LiveWebSocketSessionRegistry registry = mock(LiveWebSocketSessionRegistry.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        SessionRevocationEventHandler handler =
                new SessionRevocationEventHandler(registry, rabbitTemplate, true);

        handler.onSessionRevoked(new SessionRevokedEvent(24L, "password reset"));

        verify(registry).closeUserSessions(24L);
        verify(rabbitTemplate).convertAndSend(
                SessionRevocationRabbitConfig.EXCHANGE_NAME, "", "24");
    }

    @Test
    void singleInstanceModeClosesLocallyWithoutConnectingToRabbit() {
        LiveWebSocketSessionRegistry registry = mock(LiveWebSocketSessionRegistry.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        SessionRevocationEventHandler handler =
                new SessionRevocationEventHandler(registry, rabbitTemplate, false);

        handler.onSessionRevoked(new SessionRevokedEvent(24L, "logout"));

        verify(registry).closeUserSessions(24L);
        verify(rabbitTemplate, never()).convertAndSend(
                SessionRevocationRabbitConfig.EXCHANGE_NAME, "", "24");
    }

    @Test
    void eventHandlerIsBoundToAfterCommit() throws NoSuchMethodException {
        Method method = SessionRevocationEventHandler.class
                .getMethod("onSessionRevoked", SessionRevokedEvent.class);
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        assertNotNull(annotation);
        assertEquals(TransactionPhase.AFTER_COMMIT, annotation.phase());
    }
}
