package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Where the refresh signal is sent.
 *
 * <p>Worth its own test because the two failure modes are silent: notifying too few destinations
 * means a dashboard quietly stops updating, and notifying too many means one barangay's change
 * refetches the whole city. Neither raises an error, so only an assertion catches them.
 *
 * <p>The fan-out mode is the third silent failure: with the relay and the bus both enabled the
 * signal is delivered twice, and with neither enabled a second instance never hears about the
 * change at all. Both are configuration mistakes that only surface as duplicate or missing
 * refreshes, so each mode is asserted here rather than left to the deployment.
 */
class LiveUpdatePublisherTest {

    private final SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final InstanceIdentity instance = new InstanceIdentity();

    private LiveUpdatePublisher publisher(boolean busEnabled, boolean relayEnabled) {
        return new LiveUpdatePublisher(messagingTemplate, rabbitTemplate, instance, busEnabled, relayEnabled);
    }

    private final ArgumentCaptor<String> destination = ArgumentCaptor.forClass(String.class);

    // ---------------------------------------------------------------- destinations

    @Test
    void publishesToTheRoleChannelsAndTheAffectedBarangay() {
        publisher(false, false).publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
    }

    @Test
    void publishesASeparateFramePerAffectedBarangay() {
        publisher(false, false).publish(List.of(7L, 99L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(99L)), any(Object.class));
        assertEquals(4, countSends());
    }

    /** A batch operation over one barangay's reports must not send that barangay's frame repeatedly. */
    @Test
    void repeatedBarangaysProduceOneFrame() {
        publisher(false, false).publish(List.of(7L, 7L, 7L, 99L, 99L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(99L)), any(Object.class));
        assertEquals(4, countSends(), "two role channels plus two distinct barangays");
    }

    @Test
    void reportsWithNoBarangayStillNotifyTheRoleChannels() {
        LiveUpdatePublisher publisher = publisher(false, false);
        publisher.publish(List.of());
        publisher.publish(Set.of());

        assertEquals(4, countSends(), "two sends per call, and never a null-barangay destination");
    }

    /** A report row with no barangay must not produce a channel named after null. */
    @Test
    void nullBarangayIdsAreIgnored() {
        publisher(false, false).publish(Arrays.asList(7L, null, null));

        assertEquals(3, countSends());
        verify(messagingTemplate, never())
                .convertAndSend(eq(LiveUpdateChannels.BARANGAY_PREFIX + "null"), any(Object.class));
    }

    @Test
    void publishWithoutBarangaysTouchesOnlyTheRoleChannels() {
        publisher(false, false).publish();

        assertEquals(2, countSends());
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
    }

    @Test
    void thePayloadIsTheRefreshSignal() {
        publisher(false, false).publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), eq(LiveUpdatePublisher.REFRESH_DASHBOARDS));
    }

    // ---------------------------------------------------------------- fan-out modes

    /** Single instance: the local broker is the only delivery path. */
    @Test
    void localModeNeverTouchesTheBus() {
        publisher(false, false).publish(List.of(7L));

        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));
    }

    /** The relay already routes the published frame to every instance; a second hop would duplicate it. */
    @Test
    void relayModeNeverTouchesTheBus() {
        publisher(true, true).publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(rabbitTemplate, never()).convertAndSend(any(String.class), any(String.class), any(Object.class),
                any(MessagePostProcessor.class));
    }

    /** LavinMQ: no STOMP, so the signal has to reach the other instances over AMQP. */
    @Test
    void busModePublishesLocallyAndOnTheBus() {
        publisher(true, false).publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(rabbitTemplate).convertAndSend(
                eq(LiveUpdateBusConfig.EXCHANGE_NAME),
                eq(""),
                eq(LiveUpdatePublisher.REFRESH_DASHBOARDS),
                any(MessagePostProcessor.class));
    }

    @Test
    void busMessageCarriesTheAffectedBarangaysAndTheOriginInstance() {
        publisher(true, false).publish(List.of(7L, 99L));

        MessageProperties properties = busMessageProperties();

        assertEquals(instance.id(), properties.getHeader(LiveUpdateBusConfig.HEADER_ORIGIN));
        assertEquals("7,99", properties.getHeader(LiveUpdateBusConfig.HEADER_BARANGAY_IDS));
    }

    @Test
    void busMessageOmitsTheBarangayHeaderWhenNothingIsAffected() {
        publisher(true, false).publish();

        assertNull(busMessageProperties().getHeader(LiveUpdateBusConfig.HEADER_BARANGAY_IDS),
                "an empty header would have to be parsed as 'no barangays' by every receiver");
    }

    /**
     * A dead broker must not also silence this instance's own dashboards: local delivery
     * happens first, and the failed hop is reported rather than thrown.
     */
    @Test
    void aFailingBusDoesNotStopLocalDelivery() {
        doThrow(new RuntimeException("broker down")).when(rabbitTemplate)
                .convertAndSend(any(String.class), any(String.class), any(Object.class),
                        any(MessagePostProcessor.class));

        publisher(true, false).publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        assertTrue(true, "publish returned normally despite the bus failing");
    }

    /**
     * A dead broker must not fail the request that triggered the signal -- the report was still
     * written, and the write is the thing the caller asked for.
     */
    @Test
    void aFailureToPublishIsSwallowed() {
        doThrow(new RuntimeException("broker down")).when(messagingTemplate)
                .convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));

        publisher(false, false).publish(List.of(7L));

        assertTrue(true, "publish returned normally despite the broker failing");
    }

    // ---------------------------------------------------------------- helpers

    private MessageProperties busMessageProperties() {
        ArgumentCaptor<MessagePostProcessor> postProcessor = ArgumentCaptor.forClass(MessagePostProcessor.class);
        verify(rabbitTemplate).convertAndSend(
                eq(LiveUpdateBusConfig.EXCHANGE_NAME),
                eq(""),
                eq(LiveUpdatePublisher.REFRESH_DASHBOARDS),
                postProcessor.capture());

        Message sent = postProcessor.getValue().postProcessMessage(
                new Message(LiveUpdatePublisher.REFRESH_DASHBOARDS.getBytes(), new MessageProperties()));
        return sent.getMessageProperties();
    }

    private int countSends() {
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce()).convertAndSend(destination.capture(), any(Object.class));
        return destination.getAllValues().size();
    }
}
