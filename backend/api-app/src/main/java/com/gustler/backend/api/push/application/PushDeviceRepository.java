package com.gustler.backend.api.push.application;

import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Instant;
import java.util.UUID;

public interface PushDeviceRepository {
    boolean register(UUID memberPublicId, DeviceToken token, PushEnvironment environment, Instant registeredAt);

    boolean unregister(UUID memberPublicId, DeviceToken token);

    boolean deleteById(long deviceId);
}
