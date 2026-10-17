package com.roadwise.backend.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Receives another instance's refresh signal and delivers it to this instance's clients.
 *
 * <p>This is the half of the fan-out the STOMP relay used to perform inside the broker.
 * With the relay, application code published once and the broker routed the frame to every
 * subscription; here the publishing instance publishes locally and puts the same signal on
 * {@link LiveUpdateBusConfig#EXCHANGE_NAME}, and every other instance receives it and
 * publishes it to its own subscribers. The visible behaviour -- one refresh frame per
 * connected client per change -- is the same.
 *
 * <p>Deliberately tolerant: a malformed header is dropped rather than thrown, and a missing
 * header means "the role channels only". Nothing here is allowed to kill the listener
 * container over a message that is, after all, a hint to refetch.
 */
@Component
@ConditionalOnProperty(prefix = "roadwise.bus", name = "enabled", havingValue = "true")
public class LiveUpdateBusListener {

    private static final Logger log = LoggerFactory.getLogger(LiveUpdateBusListener.class);

    private final LiveUpdatePublisher publisher;
    private final InstanceIdentity instance;

    public LiveUpdateBusListener(LiveUpdatePublisher publisher, InstanceIdentity instance) {
        this.publisher = publisher;
        this.instance = instance;
    }

    @RabbitListener(queues = "#{@liveUpdateQueue.name}")
    public void onRefreshSignal(Message message) {
        var properties = message.getMessageProperties();

        // The publishing instance already delivered to its own subscribers before it sent
        // this, so delivering again would only refetch the same dashboards twice. Dropping
        // it is an optimisation, not a correctness rule: if the ids ever disagreed the
        // result is one redundant refresh, which is harmless.
        if (instance.id().equals(properties.getHeader(LiveUpdateBusConfig.HEADER_ORIGIN))) {
            return;
        }

        publisher.publishLocally(parseBarangayIds(properties.getHeader(LiveUpdateBusConfig.HEADER_BARANGAY_IDS)));
    }

    /**
     * Parses the comma-separated id header, discarding anything that is not a number.
     *
     * <p>Only ids this class can read reach {@link LiveUpdateChannels#forBarangay}: an id
     * that cannot be parsed is skipped, so a corrupted header narrows the fan-out instead of
     * inventing a destination.
     */
    private static Set<Long> parseBarangayIds(Object header) {
        Set<Long> ids = new LinkedHashSet<>();
        if (header == null) {
            return ids;
        }
        for (String candidate : header.toString().split(",")) {
            String trimmed = candidate.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                ids.add(Long.valueOf(trimmed));
            } catch (NumberFormatException malformed) {
                log.warn("live-update bus carried a malformed barangay id '{}' (ignored)", trimmed);
            }
        }
        return ids;
    }
}
