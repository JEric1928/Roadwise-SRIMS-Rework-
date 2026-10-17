package com.roadwise.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sends the dashboard refresh signal to the channels that should receive it.
 *
 * <p>Extracted because the destination list is now a small policy rather than a single
 * constant, and because it was previously duplicated as a try/catch at fourteen call sites in
 * one controller. A fifteenth site would have been a copy of the same three lines, and the
 * places that got it subtly wrong -- forgetting a barangay, or re-adding the global broadcast
 * -- are the kind of difference nobody notices until dashboards stop updating.
 *
 * <p>Two hops, and which ones are taken depends on how the deployment is wired (see
 * {@link FanOut}). Local delivery always happens: with a STOMP relay the frame then travels
 * to every instance through the broker, and with the AMQP bus every other instance is told
 * separately. The local hop first matters because a broker that is down must not also stop
 * this instance's own dashboards from updating.
 *
 * <p>Best effort by design. A refresh signal that cannot be delivered is not worth failing a
 * user's request over: the client reloads on navigation, and the next signal or a reload
 * reconciles whatever was missed.
 */
@Component
public class LiveUpdatePublisher {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdatePublisher.class);

    /** The whole payload. A signal, not data -- recipients refetch for themselves. */
    public static final String REFRESH_DASHBOARDS = "REFRESH_DASHBOARDS";

    /**
     * How a refresh signal reaches the instances that did not publish it.
     *
     * <p>Only one of these is ever in play, and the choice is a deployment property, not a
     * code path: {@code LOCAL} would double-deliver if combined with either of the others,
     * so the constructor resolves the two flags into exactly one mode.
     */
    enum FanOut {
        /** No shared broker: this instance is the only one holding sockets. */
        LOCAL,
        /** RabbitMQ STOMP relay: the broker routes the published frame to every instance. */
        RELAY,
        /** AMQP bus: every other instance receives the signal and publishes it locally. */
        BUS
    }

    private final SimpMessagingTemplate messagingTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final InstanceIdentity instance;
    private final FanOut fanOut;

    public LiveUpdatePublisher(
            SimpMessagingTemplate messagingTemplate,
            RabbitTemplate rabbitTemplate,
            InstanceIdentity instance,
            @Value("${roadwise.bus.enabled:false}") boolean busEnabled,
            @Value("${websocket.broker-relay.enabled:false}") boolean brokerRelayEnabled) {
        this.messagingTemplate = messagingTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.instance = instance;

        // The relay already fans out everything published to the broker, so adding the bus
        // on top of it would deliver two refresh signals per change. Relay wins when both
        // are on, which is the RabbitMQ-with-STOMP deployment that predates the bus.
        if (brokerRelayEnabled) {
            this.fanOut = FanOut.RELAY;
        } else if (busEnabled) {
            this.fanOut = FanOut.BUS;
        } else {
            this.fanOut = FanOut.LOCAL;
        }
        log.info("live-update fan-out: {}", fanOut);
    }

    /**
     * Notifies the role channels plus one channel per affected barangay.
     *
     * @param barangayIds the barangays whose data changed; {@code null} entries and duplicates
     *                    are tolerated, so a caller can pass what it has without pre-cleaning it
     */
    public void publish(Collection<Long> barangayIds) {
        Set<Long> targets = normalize(barangayIds);
        deliver(targets);
        if (fanOut == FanOut.BUS) {
            publishToBus(targets);
        }
    }

    /** A change that affects no particular barangay, such as an administrative rollover. */
    public void publish() {
        publish(Set.of());
    }

    /**
     * Delivers to this instance's subscribers only.
     *
     * <p>Package-private because {@link LiveUpdateBusListener} is the one other caller: a
     * signal that arrived from another instance has already been fanned out, and must not be
     * put back on the bus.
     */
    void publishLocally(Collection<Long> barangayIds) {
        deliver(normalize(barangayIds));
    }

    private void deliver(Set<Long> targets) {
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

    /**
     * Puts the signal on the bus for the other instances, carrying ids rather than
     * destinations so each receiver resolves channels through {@link LiveUpdateChannels}.
     */
    private void publishToBus(Set<Long> targets) {
        try {
            rabbitTemplate.convertAndSend(
                    LiveUpdateBusConfig.EXCHANGE_NAME,
                    "",
                    REFRESH_DASHBOARDS,
                    message -> {
                        message.getMessageProperties().setHeader(LiveUpdateBusConfig.HEADER_ORIGIN, instance.id());
                        if (!targets.isEmpty()) {
                            message.getMessageProperties().setHeader(
                                    LiveUpdateBusConfig.HEADER_BARANGAY_IDS, join(targets));
                        }
                        return message;
                    });
        } catch (RuntimeException ex) {
            // Same contract as local delivery: the write that triggered this has already
            // committed, and the remote dashboards recover on their next signal or reload.
            log.warn("live-update fan-out failed ({} remote instance(s) may show stale data): {}",
                    fanOut, ex.toString());
        }
    }

    private static Set<Long> normalize(Collection<Long> barangayIds) {
        Set<Long> targets = new LinkedHashSet<>();
        if (barangayIds != null) {
            barangayIds.stream().filter(Objects::nonNull).forEach(targets::add);
        }
        return targets;
    }

    private static String join(Set<Long> targets) {
        return targets.stream().map(String::valueOf).collect(Collectors.joining(","));
    }
}
