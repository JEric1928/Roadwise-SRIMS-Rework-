package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * The receiving half of the AMQP live-update bus.
 *
 * <p>Two properties matter and neither is visible from the publisher's side: a signal that
 * comes back to the instance that sent it must not be delivered twice, and a signal that
 * arrives must never be put back on the bus -- a rebound message would ping-pong between
 * instances forever.
 */
class LiveUpdateBusListenerTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final InstanceIdentity instance = new InstanceIdentity();

    private final LiveUpdatePublisher publisher =
            new LiveUpdatePublisher(messagingTemplate, rabbitTemplate, instance, true, false);
    private final LiveUpdateBusListener listener = new LiveUpdateBusListener(publisher, instance);

    @Test
    void deliversAnotherInstancesSignalToTheLocalChannels() {
        listener.onRefreshSignal(signal("some-other-instance", "7"));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
    }

    /** The publishing instance already delivered locally; delivering again would refetch twice. */
    @Test
    void ignoresItsOwnEcho() {
        listener.onRefreshSignal(signal(instance.id(), "7"));

        verifyNoInteractions(messagingTemplate);
    }

    @Test
    void aSignalWithNoBarangaysNotifiesOnlyTheRoleChannels() {
        listener.onRefreshSignal(signal("some-other-instance", null));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
        assertEquals(2, countSends(), "no barangay header must mean 'role channels only'");
    }

    /** A corrupted header narrows the fan-out instead of inventing a destination. */
    @Test
    void unreadableBarangayIdsAreDropped() {
        listener.onRefreshSignal(signal("some-other-instance", "7, not-a-number, ,99"));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(99L)), any(Object.class));
        assertEquals(4, countSends(), "two role channels plus the two ids that could be read");
        verify(messagingTemplate, never())
                .convertAndSend(eq(LiveUpdateChannels.BARANGAY_PREFIX + "not-a-number"), any(Object.class));
    }

    /** A received signal has already been fanned out; putting it back would loop forever. */
    @Test
    void neverPublishesAReceivedSignalBackOntoTheBus() {
        listener.onRefreshSignal(signal("some-other-instance", "7"));

        verify(rabbitTemplate, never()).convertAndSend(
                any(String.class), any(String.class), any(Object.class), any(MessagePostProcessor.class));
    }

    private int countSends() {
        ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce())
                .convertAndSend(destination.capture(), any(Object.class));
        return destination.getAllValues().size();
    }

    private static Message signal(String origin, String barangayIds) {
        MessageProperties properties = new MessageProperties();
        if (origin != null) {
            properties.setHeader(LiveUpdateBusConfig.HEADER_ORIGIN, origin);
        }
        if (barangayIds != null) {
            properties.setHeader(LiveUpdateBusConfig.HEADER_BARANGAY_IDS, barangayIds);
        }
        return new Message(LiveUpdatePublisher.REFRESH_DASHBOARDS.getBytes(), properties);
    }
}
