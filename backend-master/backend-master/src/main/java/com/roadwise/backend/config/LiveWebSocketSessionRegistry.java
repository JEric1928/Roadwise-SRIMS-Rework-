package com.roadwise.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;
import org.springframework.web.socket.handler.WebSocketHandlerDecoratorFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Tracks the transport sessions authenticated by STOMP so revocation can close them server-side. */
@Component
public class LiveWebSocketSessionRegistry implements WebSocketHandlerDecoratorFactory {

    private static final Logger log = LoggerFactory.getLogger(LiveWebSocketSessionRegistry.class);
    private static final CloseStatus REVOKED = new CloseStatus(1008, "Session revoked");

    private final ConcurrentMap<String, WebSocketSession> socketsById = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Long> userBySocketId = new ConcurrentHashMap<>();
    private final ConcurrentMap<Long, Set<String>> socketIdsByUser = new ConcurrentHashMap<>();

    @Override
    public WebSocketHandler decorate(WebSocketHandler delegate) {
        return new WebSocketHandlerDecorator(delegate) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                register(session);
                try {
                    super.afterConnectionEstablished(session);
                } catch (Exception ex) {
                    unregister(session.getId());
                    throw ex;
                }
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
                try {
                    super.afterConnectionClosed(session, status);
                } finally {
                    unregister(session.getId());
                }
            }
        };
    }

    /** Associates an authenticated STOMP CONNECT with its underlying WebSocket session. */
    public void associate(String socketId, Long userId) {
        if (socketId == null || socketId.isBlank() || userId == null) {
            return;
        }

        Long previousUser = userBySocketId.put(socketId, userId);
        if (previousUser != null && !previousUser.equals(userId)) {
            removeFromUser(previousUser, socketId);
        }
        socketIdsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet()).add(socketId);
    }

    /** Closes every currently connected socket for a user whose sessions were revoked. */
    public void closeUserSessions(Long userId) {
        if (userId == null) {
            return;
        }
        Set<String> socketIds = socketIdsByUser.get(userId);
        if (socketIds == null) {
            return;
        }

        for (String socketId : new ArrayList<>(socketIds)) {
            WebSocketSession socket = socketsById.get(socketId);
            if (socket == null) {
                unregister(socketId);
                continue;
            }
            if (!socket.isOpen()) {
                unregister(socketId);
                continue;
            }
            try {
                socket.close(REVOKED);
            } catch (IOException ex) {
                log.warn("Could not close revoked live-update socket {} for user {}", socketId, userId, ex);
            }
        }
    }

    private void register(WebSocketSession session) {
        socketsById.put(session.getId(), session);
        Long userId = userBySocketId.get(session.getId());
        if (userId != null) {
            socketIdsByUser.computeIfAbsent(userId, ignored -> ConcurrentHashMap.newKeySet())
                    .add(session.getId());
        }
    }

    private void unregister(String socketId) {
        if (socketId == null) {
            return;
        }
        socketsById.remove(socketId);
        Long userId = userBySocketId.remove(socketId);
        if (userId != null) {
            removeFromUser(userId, socketId);
        }
    }

    private void removeFromUser(Long userId, String socketId) {
        Set<String> socketIds = socketIdsByUser.get(userId);
        if (socketIds != null) {
            socketIds.remove(socketId);
            if (socketIds.isEmpty()) {
                socketIdsByUser.remove(userId, socketIds);
            }
        }
    }
}
