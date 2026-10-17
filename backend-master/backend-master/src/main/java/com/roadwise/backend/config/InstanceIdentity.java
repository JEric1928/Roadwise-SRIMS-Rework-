package com.roadwise.backend.config;

import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Identifies this application instance for the life of its process.
 *
 * <p>The live-update bus needs a way to tell "some other instance published this" from
 * "I published this". The publisher delivers to its own subscribers before it puts
 * anything on the bus, so a signal that comes back with this instance's own id is a
 * duplicate and is dropped instead of being delivered twice.
 *
 * <p>A random value per process is enough: the id only has to be unique among the
 * instances running right now, and an instance that restarts is a new instance from
 * the broker's point of view -- its old queue died with the old process. A
 * configuration property would not work here for the same reason a {@code random.uuid}
 * injection would not: both would need to be evaluated once and shared, which is what
 * this bean is for.
 */
@Component
public class InstanceIdentity {

    private final String id = UUID.randomUUID().toString();

    public String id() {
        return id;
    }
}
