package com.roadwise.backend.service;

import com.roadwise.backend.model.User;
import com.roadwise.backend.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SessionRevocationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private SessionRevocationService service;

    @Test
    void revokeBumpsAndPersistsSessionVersionBeforePublishingTheAfterCommitEvent() {
        User user = new User();
        user.setId(24L);
        user.setSessionVersion(7L);
        when(userRepository.save(user)).thenReturn(user);

        service.revoke(user, "password reset");

        assertEquals(8L, user.getSessionVersion());
        verify(userRepository).save(user);
        verify(eventPublisher).publishEvent(new SessionRevokedEvent(24L, "password reset"));
    }
}
