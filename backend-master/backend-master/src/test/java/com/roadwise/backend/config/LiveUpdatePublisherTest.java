package com.roadwise.backend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Where the refresh signal is sent.
 *
 * <p>Worth its own test because the two failure modes are silent: notifying too few destinations
 * means a dashboard quietly stops updating, and notifying too many means one barangay's change
 * refetches the whole city. Neither raises an error, so only an assertion catches them.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class LiveUpdatePublisherTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @InjectMocks
    private LiveUpdatePublisher publisher;

    @Captor
    private ArgumentCaptor<String> destination;

    @Test
    void publishesToTheRoleChannelsAndTheAffectedBarangay() {
        publisher.publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
    }

    @Test
    void publishesASeparateFramePerAffectedBarangay() {
        publisher.publish(List.of(7L, 99L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(99L)), any(Object.class));
    }

    /** A batch operation over one barangay's reports must not send that barangay's frame repeatedly. */
    @Test
    void repeatedBarangaysProduceOneFrame() {
        publisher.publish(List.of(7L, 7L, 7L, 99L, 99L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(7L)), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.forBarangay(99L)), any(Object.class));
        assertEquals(4, countSends(), "two role channels plus two distinct barangays");
    }

    @Test
    void reportsWithNoBarangayStillNotifyTheRoleChannels() {
        publisher.publish(List.of());
        publisher.publish(Set.of());

        assertEquals(4, countSends(), "two sends per call, and never a null-barangay destination");
    }

    /** A report row with no barangay must not produce a channel named after null. */
    @Test
    void nullBarangayIdsAreIgnored() {
        publisher.publish(java.util.Arrays.asList(7L, null, null));

        assertEquals(3, countSends());
        verify(messagingTemplate, never()).convertAndSend(eq(LiveUpdateChannels.BARANGAY_PREFIX + "null"), any(Object.class));
    }

    @Test
    void publishWithoutBarangaysTouchesOnlyTheRoleChannels() {
        publisher.publish();

        assertEquals(2, countSends());
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));
        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ENGINEERING), any(Object.class));
    }

    /**
     * A dead broker must not fail the request that triggered the signal -- the report was still
     * written, and the write is the thing the caller asked for.
     */
    @Test
    void aFailureToPublishIsSwallowed() {
        doThrow(new RuntimeException("broker down")).when(messagingTemplate)
                .convertAndSend(eq(LiveUpdateChannels.ADMIN), any(Object.class));

        publisher.publish(List.of(7L));

        assertTrue(true, "publish returned normally despite the broker failing");
    }

    @Test
    void thePayloadIsTheRefreshSignal() {
        publisher.publish(List.of(7L));

        verify(messagingTemplate).convertAndSend(eq(LiveUpdateChannels.ADMIN), eq(LiveUpdatePublisher.REFRESH_DASHBOARDS));
    }

    private int countSends() {
        verify(messagingTemplate, org.mockito.Mockito.atLeastOnce()).convertAndSend(destination.capture(), any(Object.class));
        return destination.getAllValues().size();
    }
}
