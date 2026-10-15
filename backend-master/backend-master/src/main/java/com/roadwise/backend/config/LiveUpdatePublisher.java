package com.roadwise.backend.config;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Sends the dashboard refresh signal to the channels that should receive it.
 *
 * <p>Extracted because the destination list is now a small policy rather than a single
 * constant, and because it was previously duplicated as a try/catch at fourteen call sites in
 * one controller. A fifteenth site would have been a copy of the same three lines, and the
 * places that got it subtly wrong -- forgetting a barangay, or re-adding the global broadcast
 * -- are the kind of difference nobody notices until dashboards stop updating.
 *
 * <p>Best effort by design. A refresh signal that cannot be delivered is not worth failing a
 * user's request over: the client reloads on navigation, and the next signal or a reload
 * reconciles whatever was missed.
 */
@Component
public class LiveUpdatePublisher {

    /** The whole payload. A signal, not data -- recipients refetch for themselves. */
    public static final String REFRESH_DASHBOARDS = "REFRESH_DASHBOARDS";

    private final SimpMessagingTemplate messagingTemplate;

    public LiveUpdatePublisher(SimpMessagingTemplate messagingTemplate) {
        this.messagingTemplate = messagingTemplate;
    }

    /**
     * Notifies the role channels plus one channel per affected barangay.
     *
     * @param barangayIds the barangays whose data changed; {@code null} entries and duplicates
     *                    are tolerated, so a caller can pass what it has without pre-cleaning it
     */
    public void publish(Collection<Long> barangayIds) {
        Set<Long> targets = new LinkedHashSet<>();
        if (barangayIds != null) {
            barangayIds.stream().filter(Objects::nonNull).forEach(targets::add);
        }

        try {
            messagingTemplate.convertAndSend(LiveUpdateChannels.ADMIN, REFRESH_DASHBOARDS);
            messagingTemplate.convertAndSend(LiveUpdateChannels.ENGINEERING, REFRESH_DASHBOARDS);
            for (Long barangayId : targets) {
                messagingTemplate.convertAndSend(LiveUpdateChannels.forBarangay(barangayId), REFRESH_DASHBOARDS);
            }
        } catch (Exception ignored) {
            // See the class comment: a missed refresh signal is recovered by the next one.
        }
    }

    /** A change that affects no particular barangay, such as an administrative rollover. */
    public void publish() {
        publish(Set.of());
    }
}
