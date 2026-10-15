package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Proves the rules are actually installed on the channel the broker listens to.
 *
 * <p>Kept separate from {@link WebSocketAuthInterceptorTest} because the two failures are
 * different: an interceptor with wrong rules and an interceptor nobody registered both leave
 * an open relay, and a unit test cannot tell them apart. This one asks the real application
 * context for the real inbound channel and checks two things -- that the interceptor is in
 * its chain, and that a frame sent to that channel is refused by it.
 *
 * <p>Frames are pushed onto the channel directly rather than over a socket. That is the same
 * path a real frame takes after the sub-protocol handler decodes it, and it is the only part
 * of the path that can be asserted here: server-to-client WebSocket delivery does not work in
 * this environment (see the note on {@code PRODUCTION_READINESS.md} item 6), so a test that
 * waited for a reply would be asserting nothing.
 */
@SpringBootTest
@ActiveProfiles("test")
class WebSocketInboundChannelWiringTest {

    @Autowired
    private MessageChannel clientInboundChannel;

    @Autowired
    private WebSocketAuthInterceptor interceptor;

    @Test
    void interceptorIsRegisteredOnTheBrokerInboundChannel() {
        assertTrue(clientInboundChannel instanceof org.springframework.messaging.support.ExecutorSubscribableChannel,
                "expected the broker's inbound channel; the wiring check below would be meaningless otherwise");

        List<org.springframework.messaging.support.ChannelInterceptor> interceptors =
                ((org.springframework.messaging.support.ExecutorSubscribableChannel) clientInboundChannel).getInterceptors();

        assertTrue(interceptors.stream().anyMatch(WebSocketAuthInterceptor.class::isInstance),
                "WebSocketAuthInterceptor is not registered on clientInboundChannel -- "
                        + "the broker is accepting client frames unauthenticated");
    }

    /**
     * A client SEND addressed at the live-update topic, pushed through the real channel.
     * Without the interceptor this frame reaches the broker and is broadcast.
     *
     * <p>This is the release gate for the inbound rules. It asserts where the decision is
     * made, not over a socket: it is the same channel, the same chain and the same broker
     * entry point a decoded frame takes, minus the transport that cannot be trusted to
     * deliver anything in this environment.
     */
    @Test
    void sendToBrokerDestinationIsRefusedThroughTheRealChannel() {
        Message<?> frame = stompFrame(StompCommand.SEND, LiveUpdateChannels.ADMIN);

        assertThrows(RuntimeException.class, () -> clientInboundChannel.send(frame),
                "an unauthenticated SEND to a broker destination was accepted by the real inbound channel");
    }

    @Test
    void subscribeIsRefusedThroughTheRealChannel() {
        Message<?> frame = stompFrame(StompCommand.SUBSCRIBE, LiveUpdateChannels.ADMIN);

        assertThrows(RuntimeException.class, () -> clientInboundChannel.send(frame),
                "an unauthenticated SUBSCRIBE was accepted by the real inbound channel");
    }

    @Test
    void connectWithoutCredentialsIsRefusedThroughTheRealChannel() {
        Message<?> frame = stompFrame(StompCommand.CONNECT, null);

        assertThrows(RuntimeException.class, () -> clientInboundChannel.send(frame),
                "an unauthenticated CONNECT was accepted by the real inbound channel");
    }

    @Test
    void sendToAnUnlistedBrokerDestinationIsRefusedThroughTheRealChannel() {
        Message<?> frame = stompFrame(StompCommand.SEND, "/topic/not-the-live-update-topic");

        assertThrows(RuntimeException.class, () -> clientInboundChannel.send(frame),
                "a SEND to an arbitrary broker destination was accepted by the real inbound channel");
    }

    private Message<?> stompFrame(StompCommand command, String destination) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
        accessor.setSessionId("wiring-test");
        accessor.setLeaveMutable(true);
        if (destination != null) {
            accessor.setDestination(destination);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /**
     * The failing half of the assertion above: a frame that is not STOMP traffic must not be
     * refused, otherwise the interceptor is blocking the broker's own internal messages.
     */
    @Test
    void nonStompMessagePassesThrough() {
        Message<String> internal = MessageBuilder.withPayload("broker-internal").build();

        assertTrue(clientInboundChannel.send(internal), "broker-internal traffic must still flow");
    }

    @Test
    void interceptorBeanExists() {
        assertNotNull(interceptor);
    }
}
