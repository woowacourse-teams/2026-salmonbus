package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingSettlementProjection;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class SettlementProjectionCodecTest {
    @Test
    void 재시작할_미완료_확정_시각_상태를_손실없이_복원한다() {
        var at = Instant.parse("2026-01-05T00:00:00.123456789Z");
        var p = new AlightingSettlementProjection(new AlightingSettlementProjection.Layout(3, List.of(1, 2, 3)), 100,
            AlightingSettlementProjection.Snapshot.empty());
        p.append(at, List.of(new AlightingAwareObservation(7, 3, 0, at, "bus", 1, 1, 2, null, 0, 1, at, false)), false);
        var expected = p.snapshot();
        assertThat(SettlementProjectionCodec.decode(SettlementProjectionCodec.encode(expected),
            AlightingSettlementProjection.Snapshot.class)).isEqualTo(expected);
    }
}
