package com.roadwise.backend.config;

import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;

/** A per-instance ephemeral queue fans revocations out to every live API instance. */
@Configuration
@EnableRabbit
@ConditionalOnProperty(prefix = "websocket.broker-relay", name = "enabled", havingValue = "true")
public class SessionRevocationRabbitConfig {

    public static final String EXCHANGE_NAME = "roadwise.session-revocations";

    @Bean
    FanoutExchange sessionRevocationExchange() {
        return new FanoutExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    Queue sessionRevocationQueue() {
        // A node only needs revocations while it is alive and holding sockets. On restart,
        // clients reconnect and authenticate against the persisted session version.
        return new AnonymousQueue();
    }

    @Bean
    Binding sessionRevocationBinding(Queue sessionRevocationQueue, FanoutExchange sessionRevocationExchange) {
        return BindingBuilder.bind(sessionRevocationQueue).to(sessionRevocationExchange);
    }
}
