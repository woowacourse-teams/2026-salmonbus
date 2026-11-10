package com.gustler.backend.api.push.delivery;

import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("push.apns")
public record ApnsProperties(
    String teamId,
    String keyId,
    String privateKeyPath,
    String topic,
    String productionUri,
    String sandboxUri
) {
    ApnsProperties requireComplete() {
        String missing = Stream.of(
                new Setting("APNS_TEAM_ID", teamId),
                new Setting("APNS_KEY_ID", keyId),
                new Setting("APNS_PRIVATE_KEY_PATH", privateKeyPath),
                new Setting("APNS_TOPIC", topic))
            .filter(setting -> setting.value() == null || setting.value().isBlank())
            .map(Setting::environmentVariable)
            .collect(Collectors.joining(", "));
        if (!missing.isEmpty()) {
            throw new IllegalStateException("push.enabled=true 이면 다음 설정이 필요하다: " + missing);
        }
        return this;
    }

    private record Setting(String environmentVariable, String value) {
    }
}
