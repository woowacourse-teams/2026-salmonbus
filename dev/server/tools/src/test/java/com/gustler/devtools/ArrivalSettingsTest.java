package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ArrivalSettingsTest {
    private Map<String, String> valid() {
        return Map.of("ARRIVALS_ENABLED", "true", "ARRIVAL_INTERNAL_TOKEN", "a".repeat(64),
                "ARRIVAL_WORKER_BASE_URL", ArrivalSettings.WORKER);
    }

    @Test
    void acceptsSharedDevelopmentTokenAndRejectsDisabledWeakOrExternalSettings() {
        assertDoesNotThrow(() -> ArrivalSettings.validate(valid()));
        for (var change : List.of(Map.of("ARRIVALS_ENABLED", "false"),
                Map.of("ARRIVAL_INTERNAL_TOKEN", "placeholder"),
                Map.of("ARRIVAL_WORKER_BASE_URL", "http://external.invalid:8081"),
                Map.of("ARRIVAL_WORKER_BASE_URL", "http://127.0.0.1:8081/path"))) {
            var values = new HashMap<>(valid());
            values.putAll(change);
            assertThrows(IllegalArgumentException.class, () -> ArrivalSettings.validate(values));
        }
    }

    @Test
    void preflightRejectsTokenMismatchAndUnexpectedArrivalOverrides() {
        var expected = valid();
        assertDoesNotThrow(() -> Environment.effective(expected, expected));
        for (var change : List.of(Map.of("ARRIVAL_INTERNAL_TOKEN", "b".repeat(64)),
                Map.of("ARRIVALS_ENABLED", "false"), Map.of("ARRIVALS_DAILY_LIMIT", "999999"))) {
            assertThrows(IllegalArgumentException.class,
                    () -> Environment.effective(expected, Environment.combined(expected, change)));
        }
        assertThrows(IllegalArgumentException.class,
                () -> Environment.effective(Map.of(), valid()));
    }
}
