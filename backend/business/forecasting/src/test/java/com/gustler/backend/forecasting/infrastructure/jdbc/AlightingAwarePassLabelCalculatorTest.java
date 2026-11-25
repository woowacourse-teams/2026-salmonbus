package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AlightingAwarePassLabelCalculatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Instant MORNING_START = Instant.parse("2026-01-04T21:00:01Z");
    private static final String PARAMETERS = """
        {"stop":3,"band":"morning","lam":[1.5,1.5,1.6,1.7,1.8,1.9,2.0,2.1],"p":0.12,"kappa":1.0,"r":2.0}
        """;
    private static final String REORDERED_PARAMETERS = """
        {"band":"morning","stop":3,"lam":[1.5,1.5,1.6,1.7,1.8,1.9,2.0,2.1],"p":0.12,"kappa":1.0,"r":2.0}
        """;

    @Test
    void 수신_시각이_달라도_확정_시각으로_시간대와_앞차_간격을_계산한다() {
        // given
        AlightingAwarePassLabelCalculator calculator = new AlightingAwarePassLabelCalculator();
        JsonNode evidence = evidence(PARAMETERS);
        Instant previousConfirmedAt = MORNING_START.minusSeconds(20);
        PassLabelCalculator.PassLabelCalculation confirmedOnly = calculator.calculate(
            pass(MORNING_START, MORNING_START),
            pass(previousConfirmedAt, previousConfirmedAt),
            evidence
        );
        assertThat(confirmedOnly.settled()).isTrue();

        // when
        PassLabelCalculator.PassLabelCalculation actual = calculator.calculate(
            pass(MORNING_START, MORNING_START.minusSeconds(2)),
            pass(previousConfirmedAt, previousConfirmedAt.minusSeconds(1)),
            evidence
        );

        // then
        assertThat(actual.settled()).isTrue();
        assertSoftly(checks -> {
            checks.assertThat(Double.doubleToLongBits(actual.value()))
                .isEqualTo(Double.doubleToLongBits(confirmedOnly.value()));
            checks.assertThat(actual.reason()).isNull();
            checks.assertThat(actual.parameterHash()).isEqualTo(confirmedOnly.parameterHash());
        });
    }

    @Test
    void 계수_필드_순서가_달라도_정답은_같고_해시는_직렬화_순서를_따른다() {
        // given
        AlightingAwarePassLabelCalculator calculator = new AlightingAwarePassLabelCalculator();
        PassClosed current = pass(MORNING_START, MORNING_START);
        PassClosed previous = pass(MORNING_START.minusSeconds(20), MORNING_START.minusSeconds(20));

        // when
        PassLabelCalculator.PassLabelCalculation original =
            calculator.calculate(current, previous, evidence(PARAMETERS));
        PassLabelCalculator.PassLabelCalculation reordered =
            calculator.calculate(current, previous, evidence(REORDERED_PARAMETERS));

        // then
        assertThat(original.settled()).isTrue();
        assertThat(reordered.settled()).isTrue();
        assertSoftly(checks -> {
            checks.assertThat(Double.doubleToLongBits(reordered.value()))
                .isEqualTo(Double.doubleToLongBits(original.value()));
            checks.assertThat(original.parameterHash())
                .isEqualTo("f03b3e84e70ad886c1bf1ccfdf620a099b7773edd50e3c87ea259d772a21bee8");
            checks.assertThat(reordered.parameterHash())
                .isEqualTo("2b7d58ab16ee24a86ec8004c6d5585b2fd6a25720a02eb9ef45f90670b1dd985");
        });
    }

    private JsonNode evidence(String parameters) {
        return JSON.readTree("""
            {"capacity":40,"holidays":[],"alighting":{"stops":[%s]}}
            """.formatted(parameters));
    }

    private PassClosed pass(Instant confirmedAt, Instant receivedAt) {
        return new PassClosed(
                SourceDates.of(confirmedAt),
                7,
                100,
                3,
                70,
                "bus-a",
                100,
                SourceDates.of(confirmedAt),
                101,
                25,
                confirmedAt.minusSeconds(10),
                receivedAt.minusSeconds(10),
                102,
                confirmedAt.minusSeconds(5),
                103,
                20,
                confirmedAt,
                receivedAt,
                confirmedAt,
                104,
                200,
                confirmedAt.plusSeconds(20)
        );
    }
}
