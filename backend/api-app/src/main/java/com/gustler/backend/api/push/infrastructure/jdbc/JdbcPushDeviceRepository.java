package com.gustler.backend.api.push.infrastructure.jdbc;

import com.gustler.backend.api.push.application.PushDeviceRepository;
import com.gustler.backend.api.push.domain.DeviceToken;
import com.gustler.backend.api.push.domain.PushEnvironment;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcPushDeviceRepository implements PushDeviceRepository {
    private static final String REGISTER = """
        INSERT INTO push_device (member_id, token, environment, created_at, last_registered_at)
        SELECT id, ?, ?, ?, ? FROM member WHERE public_id = ?
        ON CONFLICT (token) DO UPDATE SET member_id = EXCLUDED.member_id,
            environment = EXCLUDED.environment, last_registered_at = EXCLUDED.last_registered_at
        """;

    private final JdbcClient jdbc;

    public JdbcPushDeviceRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public boolean register(UUID memberPublicId, DeviceToken token, PushEnvironment environment, Instant registeredAt) {
        OffsetDateTime timestamp = OffsetDateTime.ofInstant(registeredAt, ZoneOffset.UTC);
        return jdbc.sql(REGISTER)
            .params(token.value(), environment.name(), timestamp, timestamp, memberPublicId)
            .update() > 0;
    }

    @Override
    @Transactional
    public boolean unregister(UUID memberPublicId, DeviceToken token) {
        Optional<Long> memberId = jdbc.sql("SELECT id FROM member WHERE public_id = ?")
            .param(memberPublicId).query(Long.class).optional();
        if (memberId.isEmpty()) {
            return false;
        }
        jdbc.sql("DELETE FROM push_device WHERE member_id = ? AND token = ?")
            .params(memberId.get(), token.value()).update();
        return true;
    }

    @Override
    @Transactional
    public boolean deleteById(long deviceId) {
        return jdbc.sql("DELETE FROM push_device WHERE id = ?")
            .param(deviceId)
            .update() > 0;
    }
}
