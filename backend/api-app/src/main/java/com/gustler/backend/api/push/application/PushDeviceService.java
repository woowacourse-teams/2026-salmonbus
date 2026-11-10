package com.gustler.backend.api.push.application;

import com.gustler.backend.api.error.UnauthenticatedException;
import com.gustler.backend.api.push.InvalidPushDeviceException;
import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class PushDeviceService {
    private final PushDeviceRepository repository;
    private final Clock clock;

    public PushDeviceService(PushDeviceRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    public void register(UUID memberPublicId, UUID sessionId, String token, String environment) {
        if (sessionId == null) {
            throw new UnauthenticatedException();
        }
        DeviceToken deviceToken;
        PushEnvironment pushEnvironment;
        try {
            deviceToken = new DeviceToken(token);
            pushEnvironment = PushEnvironment.from(environment);
        } catch (IllegalArgumentException exception) {
            throw new InvalidPushDeviceException();
        }
        if (!repository.register(memberPublicId, sessionId, deviceToken, pushEnvironment, clock.instant())) {
            throw new UnauthenticatedException();
        }
    }

    public void unregister(UUID memberPublicId, String token) {
        DeviceToken deviceToken;
        try {
            deviceToken = new DeviceToken(token);
        } catch (IllegalArgumentException exception) {
            throw new InvalidPushDeviceException();
        }
        if (!repository.unregister(memberPublicId, deviceToken)) {
            throw new UnauthenticatedException();
        }
    }
}
