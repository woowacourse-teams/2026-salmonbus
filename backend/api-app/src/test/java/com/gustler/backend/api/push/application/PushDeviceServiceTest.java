package com.gustler.backend.api.push.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.push.InvalidPushDeviceException;
import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class PushDeviceServiceTest {
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID SESSION = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-11-06T01:30:00Z");
    private final PushDeviceRepository repository = mock(PushDeviceRepository.class);
    private final PushDeviceService service = new PushDeviceService(repository, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void registersNormalizedTokenWithClockTime() {
        when(repository.register(MEMBER, SESSION, new DeviceToken("ab"), PushEnvironment.SANDBOX, NOW)).thenReturn(true);
        service.register(MEMBER, SESSION, "AB", "SANDBOX");
        verify(repository).register(MEMBER, SESSION, new DeviceToken("ab"), PushEnvironment.SANDBOX, NOW);
    }

    @Test
    void rejectsRegistrationWithoutSession() {
        assertThatThrownBy(() -> service.register(MEMBER, null, "ab", "SANDBOX"))
            .isInstanceOf(UnauthenticatedException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void unregistersNormalizedToken() {
        when(repository.unregister(MEMBER, new DeviceToken("ab"))).thenReturn(true);
        service.unregister(MEMBER, "AB");
        verify(repository).unregister(MEMBER, new DeviceToken("ab"));
    }

    @ParameterizedTest
    @CsvSource(value = {"ab,sandbox", "xyz,SANDBOX", "ab,NULL", "NULL,PRODUCTION"}, nullValues = "NULL")
    void convertsRegistrationFieldErrors(String token, String environment) {
        assertThatThrownBy(() -> service.register(MEMBER, SESSION, token, environment))
            .isInstanceOf(InvalidPushDeviceException.class);
        verifyNoInteractions(repository);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"xyz", " ab"})
    void convertsUnregistrationFieldErrors(String token) {
        assertThatThrownBy(() -> service.unregister(MEMBER, token)).isInstanceOf(InvalidPushDeviceException.class);
        verifyNoInteractions(repository);
    }

    @Test
    void rejectsRegistrationForMissingMember() {
        assertThatThrownBy(() -> service.register(MEMBER, SESSION, "ab", "PRODUCTION"))
            .isInstanceOf(UnauthenticatedException.class);
    }

    @Test
    void rejectsUnregistrationForMissingMember() {
        assertThatThrownBy(() -> service.unregister(MEMBER, "ab")).isInstanceOf(UnauthenticatedException.class);
    }
}
