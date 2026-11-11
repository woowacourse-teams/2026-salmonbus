package com.gustler.backend.api.salmon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class SalmonActionSettingsTest {
    private static final Duration WALK = Duration.ofMinutes(15);
    private static final Duration HORIZON = Duration.ofMinutes(20);
    private static final Duration IN_VEHICLE = Duration.ofMinutes(20);
    private static final BigDecimal ALPHA = new BigDecimal("1.2");
    private static final Duration CONNECTION = Duration.ofSeconds(15);

    private final SalmonActionSettings adopted = SalmonSettingsFixture.adopted();

    @Test
    void 도보_합이_15분이면_허용하고_넘으면_거절한다() {
        assertThat(adopted.walkExceeded(WALK.toMillis())).isFalse();
        assertThat(adopted.walkExceeded(WALK.toMillis() + 1)).isTrue();
        assertThat(adopted.maxWalkMinutes()).isEqualTo(15);
        assertThat(SalmonSettingsFixture.with(WALK.plusMillis(1), IN_VEHICLE, HORIZON).maxWalkMinutes()).isEqualTo(16);
    }

    @Test
    void 도보_10분은_12분으로_올려_마감과_비교한다() {
        SalmonActionSettings twelveMinutes = SalmonSettingsFixture.with(WALK, IN_VEHICLE, Duration.ofMinutes(12));

        assertThat(twelveMinutes.walkOutlastsHorizon(Duration.ofMinutes(10).toMillis())).isFalse();
        assertThat(twelveMinutes.walkOutlastsHorizon(Duration.ofMinutes(10).toMillis() + 1)).isTrue();
    }

    @Test
    void 환승은_한_번까지_차내_시간은_20분까지_허용한다() {
        assertThat(adopted.transfersExceeded(1)).isFalse();
        assertThat(adopted.transfersExceeded(2)).isTrue();
        assertThat(adopted.inVehicleExceeded(IN_VEHICLE.toMillis())).isFalse();
        assertThat(adopted.inVehicleExceeded(IN_VEHICLE.toMillis() + 1)).isTrue();
    }

    @Test
    void 버스보다_15초_먼저_서면_연결이다() {
        long bus = Duration.ofMinutes(10).toMillis();

        assertThat(adopted.connected(bus, bus - Duration.ofSeconds(15).toMillis())).isTrue();
        assertThat(adopted.connected(bus, bus - Duration.ofSeconds(14).toMillis())).isFalse();
    }

    @Test
    void 도보_조회가_답하는_30분보다_긴_도보_상한은_만들지_않는다() {
        assertThat(settings(Duration.ofMinutes(30), 0.5, HORIZON, IN_VEHICLE, 0, ALPHA, 1, CONNECTION).maxWalk())
            .isEqualTo(Duration.ofMinutes(30));
        assertRejected(Duration.ofMinutes(30).plusMillis(1), 0.5, HORIZON, IN_VEHICLE, 0, ALPHA, 1, CONNECTION);
    }

    @Test
    void 범위를_벗어난_값은_만들지_않는다() {
        assertRejected(Duration.ZERO, 0.5, HORIZON, IN_VEHICLE, 0, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0, HORIZON, IN_VEHICLE, 0, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 1.1, HORIZON, IN_VEHICLE, 0, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0.5, Duration.ZERO, IN_VEHICLE, 0, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, Duration.ZERO, 0, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, IN_VEHICLE, -0.01, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, IN_VEHICLE, Double.NaN, ALPHA, 1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, IN_VEHICLE, 0, new BigDecimal("0.9"), 1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, IN_VEHICLE, 0, ALPHA, -1, CONNECTION);
        assertRejected(WALK, 0.5, HORIZON, IN_VEHICLE, 0, ALPHA, 1, Duration.ofSeconds(-1));
    }

    @Test
    void 이득_역치_1과_접속_여유_0은_허용한다() {
        SalmonActionSettings edge = settings(WALK, 1, HORIZON, IN_VEHICLE, 0, BigDecimal.ONE, 0, Duration.ZERO);

        assertThat(edge.gainThreshold()).isEqualTo(1);
        assertThat(edge.connected(1_000, 1_000)).isTrue();
    }

    private static void assertRejected(Duration maxWalk, double gainThreshold, Duration horizon, Duration maxInVehicle,
                                       double eta, BigDecimal alpha, int maxTransfers, Duration minConnection) {
        assertThatThrownBy(() -> settings(maxWalk, gainThreshold, horizon, maxInVehicle, eta, alpha, maxTransfers,
            minConnection)).isInstanceOf(IllegalArgumentException.class);
    }

    private static SalmonActionSettings settings(Duration maxWalk, double gainThreshold, Duration horizon,
                                                 Duration maxInVehicle, double eta, BigDecimal alpha,
                                                 int maxTransfers, Duration minConnection) {
        return new SalmonActionSettings(true, maxWalk, gainThreshold, horizon, maxInVehicle, eta, alpha, maxTransfers,
            minConnection);
    }
}
