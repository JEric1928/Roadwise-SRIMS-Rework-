package com.roadwise.backend.service;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** The single write path for invalidating a user's credentials and live sockets. */
@Service
public class SessionRevocationService {

    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SessionRevocationService(UserRepository userRepository, ApplicationEventPublisher eventPublisher) {
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    /**
     * Bumps the persisted session version and queues socket closure after the caller's
     * transaction commits. Requiring an existing transaction prevents a disconnect event
     * from escaping when the revocation itself later rolls back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void revoke(User user, String reason) {
        if (user == null || user.getId() == null) {
            throw new IllegalArgumentException("A persisted user is required for session revocation.");
        }
        user.bumpSessionVersion();
        userRepository.save(user);
        eventPublisher.publishEvent(new SessionRevokedEvent(user.getId(), reason));
    }
}
