package com.roadwise.backend.config;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The AMQP side of the live-update fan-out, for deployments without a STOMP-capable broker.
 *
 * <p>Spring's {@code enableStompBrokerRelay} is a RabbitMQ feature: the relay speaks STOMP
 * 1.2 on port 61613, which is a RabbitMQ plugin. LavinMQ speaks AMQP 0-9-1 and MQTT and
 * nothing else, so a LavinMQ deployment cannot use the relay at all. This bus replaces the
 * relay with the primitive LavinMQ does provide: a fanout exchange with one ephemeral queue
 * per instance. {@link LiveUpdateBusListener} reads from that queue and re-publishes to the
 * instance's own (now local) simple broker, which is what the relay used to do on the
 * broker's side.
 *
 * <p>Same exchange shape as {@link SessionRevocationRabbitConfig}: durable exchange, one
 * anonymous per-instance queue, because a refresh signal is only useful to instances that
 * are alive and holding sockets. Nothing here is RabbitMQ-specific -- fanout exchanges,
 * exclusive auto-delete queues and bindings are core AMQP 0-9-1.
 */
@Configuration
@ConditionalOnProperty(prefix = "roadwise.bus", name = "enabled", havingValue = "true")
public class LiveUpdateBusConfig {

    /** Durable so a restarted publisher does not have to win a race to declare it. */
    public static final String EXCHANGE_NAME = "roadwise.live-updates";

    /**
     * Comma-separated canonical barangay ids carried in the message headers.
     *
     * <p>The ids, not the destinations, cross the wire: a destination is a policy decision
     * ({@link LiveUpdateChannels} owns it), and letting the bus carry destinations would let
     * an older or newer instance -- or anything else on the broker -- name a channel this
     * instance would then broadcast to.
     */
    public static final String HEADER_BARANGAY_IDS = "roadwise.barangay-ids";

    /** The {@link InstanceIdentity} of the instance that published, so it can drop the echo. */
    public static final String HEADER_ORIGIN = "roadwise.origin";

    @Bean
    FanoutExchange liveUpdateExchange() {
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    Queue liveUpdateQueue() {
        return new AnonymousQueue();
    }

    @Bean
    Binding liveUpdateBinding(Queue liveUpdateQueue, FanoutExchange liveUpdateExchange) {
        return BindingBuilder.bind(liveUpdateQueue).to(liveUpdateExchange);
    }
}
