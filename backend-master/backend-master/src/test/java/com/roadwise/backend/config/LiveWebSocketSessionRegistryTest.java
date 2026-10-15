package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveWebSocketSessionRegistryTest {

    @Test
    void revocationClosesEverySocketForOnlyThatUser() throws Exception {
        LiveWebSocketSessionRegistry registry = new LiveWebSocketSessionRegistry();
        WebSocketSession revokedUserSocket = socket("socket-1");
        WebSocketSession otherUserSocket = socket("socket-2");

        var handler = registry.decorate(new TextWebSocketHandler());
        handler.afterConnectionEstablished(revokedUserSocket);
        handler.afterConnectionEstablished(otherUserSocket);
        registry.associate("socket-1", 41L);
        registry.associate("socket-2", 42L);

        registry.closeUserSessions(41L);

        verify(revokedUserSocket).close(any(CloseStatus.class));
        verify(otherUserSocket, never()).close(any(CloseStatus.class));
    }

    @Test
    void disconnectedSocketsAreRemovedFromTheUserIndex() throws Exception {
        LiveWebSocketSessionRegistry registry = new LiveWebSocketSessionRegistry();
        WebSocketSession socket = socket("socket-1");
        var handler = registry.decorate(new TextWebSocketHandler());
        handler.afterConnectionEstablished(socket);
        registry.associate("socket-1", 41L);

        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        registry.closeUserSessions(41L);

        verify(socket, never()).close(any(CloseStatus.class));
    }

    private WebSocketSession socket(String id) {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        return socket;
    }
}
