package com.gustler.backend.forecasting.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.forecasting.api.forecast.ForecastOutcome;
import com.gustler.backend.forecasting.api.forecast.ForecastPublished;
import com.gustler.backend.forecasting.api.forecast.PassClosed;
import com.gustler.backend.forecasting.application.forecast.ForecastRecordStore;
import com.gustler.backend.forecasting.application.forecast.PassLabelCalculator;
import com.gustler.backend.forecasting.application.forecast.PassLabelStore;
import com.gustler.backend.forecasting.application.forecast.ScoredForecast;
import com.gustler.backend.forecasting.application.forecast.ScoringReasons;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.ScoringStage;
import com.gustler.backend.forecasting.application.forecast.StopPassStore;
import com.gustler.backend.forecasting.application.forecast.UnresolvedForecast;
import com.gustler.backend.forecasting.domain.forecast.SourceDates;
import com.gustler.backend.forecasting.support.ForecastSamples;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * 예측별 결과 뷰({@code forecast_outcome})가 미해결 기록에 기대지 않고 정산 단계와 같은 짝짓기 조건으로 정답을 붙이는지 본다.
 *
 * <p>정산 단계가 낸 정답 기록만 저장하고 미해결 기록은 저장하지 않아, 정산 결과를 쓰기 전에 프로세스가 끝난 상태를 만든다. 한 통과를
 * 기다린 예측 가운데 하나는 조건에 맞아 정답을 받고 다른 하나는 맞지 않을 때, 뷰가 맞지 않는 예측에 그 정답을 붙이지 않아야 한다.
 * 정답 계산은 고정 값으로 바꿔 둔다.
 */
@ForecastingIntegrationTest
@Transactional
class ForecastOutcomePairConditionTest {

    private static final long ROUTE = 8_300L;
    private static final long VERSION = 8_301L;
    private static final long DEPLOYMENT = 8_302L;
    private static final List<Integer> REAL_STOPS = List.of(
            1,
            2,
            3,
            4,
            5
    );
    private static final int TARGET = 3;
    private static final int CAPACITY = 40;
    private static final double LABEL_VALUE = 12.5;
    private static final String PARAMETER_HASH = "a".repeat(64);
    private static final Instant BASIS = Instant.parse("2026-01-05T00:00:00Z");
    /** 저장되는 시각의 가장 작은 단위다. */
    private static final Duration MICROSECOND = Duration.of(1, ChronoUnit.MICROS);
    private static final Row PENDING =
        Row.withoutLabel("PENDING", null);
    private static final Row CONDITION_UNMET =
        Row.withoutLabel("LOST", ScoringReasons.PASS_CONDITION_UNMET);

    @Autowired
    JdbcClient jdbc;
    @Autowired
    ForecastRecordStore records;
    @Autowired
    StopPassStore passes;
    @Autowired
    PassLabelStore labels;

    @Test
    void 미해결_줄이_저장되지_않아도_조건에_맞지_않는_예측은_같은_통과의_정답_대신_조건_불충족으로_보인다() {
        // given
        final long paired = 500L;
        final long unmet = 501L;
        PassClosed pass = pass(
                "bus-a",
                50,
                BASIS.plusSeconds(55),
                BASIS.plusSeconds(120),
                3
        );
        List<Object> events = List.of(
            forecast(1, BASIS, vehicle(paired, "bus-a", 50)),
            forecast(2, BASIS.plusSeconds(50), vehicle(unmet, "bus-a", 50)),
            pass);
        ScoringResult result = ScoringScenario.feed(stage(), events);
        store(events);
        labels.saveLabels(result.labels());

        // when
        Map<Long, Row> actual = view();

        // then
        assertSoftly(softly -> {
            softly.assertThat(result.unresolved()).singleElement().satisfies(closed -> {
                softly.assertThat(closed.vehicleObservationId()).isEqualTo(unmet);
                softly.assertThat(actual.get(unmet)).isEqualTo(Row.withoutLabel(
                    closed.state().name(),
                    closed.reason()
                ));
            });
            softly.assertThat(actual.get(unmet)).isEqualTo(CONDITION_UNMET);
            softly.assertThat(actual.get(paired)).isEqualTo(new Row(
                    "SETTLED",
                    null,
                    LABEL_VALUE,
                    PARAMETER_HASH,
                    pass.arrivalObservationId(),
                    pass.lastObservationId(),
                    pass.closureObservationId(),
                    null,
                    null
            ));
        });
    }

    @Test
    void 짝짓기_조건의_경계에서_뷰는_채점_단계가_짝지은_예측에만_정답을_붙인다() {
        // given
        final long leadExact = 501L;
        final long leadOver = 511L;
        final long windowExact = 521L;
        final long windowOver = 531L;
        Instant reachedAt = BASIS.plusSeconds(60);
        Instant closedAt = BASIS.plusSeconds(120);
        Instant windowEnd = BASIS.plus(ScoringStage.MATCH_WINDOW);
        List<Object> events = List.of(
                forecast(
                        1,
                        BASIS,
                        vehicle(500, "bus-a", 50),
                        vehicle(510, "bus-b", 51),
                        vehicle(windowExact, "bus-c", 52),
                        vehicle(windowOver, "bus-d", 53)
                ),
                forecast(
                        2,
                        BASIS.plusSeconds(1),
                        vehicle(520, "bus-c", 52),
                        vehicle(530, "bus-d", 53)
                ),
                forecast(
                    3,
                    reachedAt.minus(ScoringStage.MINIMUM_LEAD).minus(MICROSECOND),
                    vehicle(leadOver, "bus-b", 51)
                ),
                forecast(4, reachedAt.minus(ScoringStage.MINIMUM_LEAD), vehicle(leadExact, "bus-a", 50)),
                pass(
                        "bus-a",
                        50,
                        reachedAt,
                        closedAt,
                        5
                ),
                pass(
                        "bus-b",
                        51,
                        reachedAt,
                        closedAt,
                        5
                ),
                pass(
                        "bus-c",
                        52,
                        windowEnd.minusSeconds(30),
                        windowEnd,
                        6
                ),
                pass(
                        "bus-d",
                        53,
                        windowEnd.minusSeconds(30),
                        windowEnd.plus(MICROSECOND),
                        7
                )
        );
        ScoringResult result = ScoringScenario.feed(stage(), events);
        store(events);
        labels.saveLabels(result.labels());

        // when
        Map<Long, Row> actual = view();

        // then
        Set<Long> scored = result.scored().stream()
            .map(ScoredForecast::vehicleObservationId)
            .collect(Collectors.toSet());
        assertSoftly(softly -> {
            softly.assertThat(scored).containsExactlyInAnyOrder(
                    500L,
                    510L,
                    leadOver,
                    520L,
                    windowExact,
                    530L
            );
            softly.assertThat(observationsIn(actual, "SETTLED")).isEqualTo(scored);
            softly.assertThat(actual.get(leadExact)).isEqualTo(CONDITION_UNMET);
            softly.assertThat(actual.get(windowOver)).isEqualTo(CONDITION_UNMET);
        });
    }

    @Test
    void 미해결_줄이_있으면_조건에_맞는_통과의_정답이_있어도_미해결_줄의_상태를_보인다() {
        // given
        final long unsupported = 502L;
        List<Object> events = List.of(
            forecast(1, BASIS, vehicle(500, "bus-a", 50)),
            forecast(2, BASIS.plusSeconds(1), vehicle(unsupported, "bus-a", 50)),
            pass(
                    "bus-a",
                    50,
                    BASIS.plusSeconds(60),
                    BASIS.plusSeconds(120),
                    3
            ));
        ScoringResult result = ScoringScenario.feed(stage(), events);
        store(events);
        labels.saveLabels(result.labels());
        labels.saveUnresolved(List.of(new UnresolvedForecast(
                SourceDates.of(BASIS),
                unsupported,
                TARGET,
                ROUTE,
                UnresolvedForecast.State.UNSUPPORTED,
                ScoringReasons.SOURCE_HISTORY_INCOMPLETE,
                BASIS.plusSeconds(120)
        )));

        // when
        Map<Long, Row> actual = view();

        // then
        assertSoftly(softly -> {
            softly.assertThat(actual.get(unsupported))
                .isEqualTo(Row.withoutLabel(
                    "UNSUPPORTED",
                    ScoringReasons.SOURCE_HISTORY_INCOMPLETE
                ));
            softly.assertThat(actual.get(500L).state()).isEqualTo("SETTLED");
        });
    }

    @Test
    void 통과가_아직_닫히지_않은_예측은_채점_전으로_보인다() {
        // given
        store(List.of(forecast(1, BASIS, vehicle(500, "bus-a", 50))));

        // when
        Map<Long, Row> actual = view();

        // then
        assertThat(actual).containsExactly(Map.entry(500L, PENDING));
    }

    @Test
    void 통과_기록만_있고_그_통과의_정답이_아직_없으면_조건에_맞지_않는_예측도_채점_전으로_보인다() {
        // given
        Instant reachedAt = BASIS.plusSeconds(60);
        store(List.of(
            forecast(1, reachedAt.minus(ScoringStage.MINIMUM_LEAD), vehicle(500, "bus-a", 50)),
            pass(
                    "bus-a",
                    50,
                    reachedAt,
                    BASIS.plusSeconds(120),
                    2
            )));

        // when
        Map<Long, Row> actual = view();

        // then
        assertThat(actual).containsExactly(Map.entry(500L, PENDING));
    }

    private static ScoringStage stage() {
        JsonNode context = ScoringScenario.context(
                REAL_STOPS,
                9,
                List.of(2, 3, 4),
                true,
                List.of(),
                List.of()
        );
        return new ScoringStage(
                (deployment, version) -> Optional.of(context),
                version -> REAL_STOPS,
                ForecastOutcomePairConditionTest::settled,
                ScoringScenario.FIXED,
                ScoringStage.DEFAULT_OBSERVATION_LIMIT
        );
    }

    private static PassLabelCalculator.PassLabelCalculation settled(
        PassClosed pass,
        PassClosed previous,
        JsonNode evidence
    ) {
        return new PassLabelCalculator.PassLabelCalculation(
                true,
                LABEL_VALUE,
                null,
                PARAMETER_HASH
        );
    }

    private static ForecastPublished forecast(
        final long batchId,
        Instant confirmedAt,
        ForecastPublished.VehicleForecast... vehicles
    ) {
        return new ForecastPublished(
                SourceDates.of(confirmedAt),
                batchId,
                ROUTE,
                VERSION,
                confirmedAt.minusSeconds(2),
                confirmedAt,
                DEPLOYMENT,
                ForecastOutcome.PUBLISHED,
                List.of(vehicles)
        );
    }

    /** 목표 정류장 하나를 겨눈 예측이다. 운행 시작 관측은 연결 시작 관측과 같게 둔다. */
    private static ForecastPublished.VehicleForecast vehicle(
        final long observationId,
        String vehicleId,
        final long chain
    ) {
        return new ForecastPublished.VehicleForecast(
                observationId,
                0,
                vehicleId,
                1,
                chain,
                chain,
                false,
                CAPACITY,
                false,
                new int[] {TARGET},
                new double[] {20.0},
                new double[] {0.1}
        );
    }

    /** 목표 정류장의 통과다. 근거 관측 번호는 연결 시작 관측 번호에서 만든다. */
    private static PassClosed pass(
        String vehicleId,
        final long chain,
        Instant reachedAt,
        Instant closedAt,
        final long closureBatchId
    ) {
        Instant lastAt = closedAt.minusSeconds(20);
        return new PassClosed(
                SourceDates.of(closedAt),
                ROUTE,
                chain,
                TARGET,
                VERSION,
                vehicleId,
                chain,
                SourceDates.of(lastAt),
                chain * 10 + 1,
                20,
                lastAt.minusSeconds(40),
                lastAt.minusSeconds(42),
                chain * 10 + 2,
                lastAt.minusSeconds(20),
                chain * 10 + 3,
                18,
                lastAt,
                lastAt.minusSeconds(2),
                reachedAt,
                chain * 10 + 4,
                closureBatchId,
                closedAt
        );
    }

    private void store(List<Object> events) {
        for (Object event : events) {
            if (event instanceof ForecastPublished published) {
                records.save(published, ForecastSamples.seenWithoutRows(published));
                continue;
            }
            if (event instanceof PassClosed pass) {
                passes.save(List.of(pass));
            }
        }
    }

    private Map<Long, Row> view() {
        Map<Long, Row> rows = new HashMap<>();
        jdbc.sql("""
            SELECT vehicle_observation_id, state, reason, label_value, parameter_hash, arrival_observation_id,
                last_observation_id, closure_observation_id, previous_last_observation_id,
                previous_closure_observation_id
            FROM forecast_outcome WHERE route_id = ?
            """).param(ROUTE).query(row -> {
                Row before = rows.putIfAbsent(row.getLong(
                    1
                ), new Row(
                        row.getString(2),
                        row.getString(3),
                        (Double) row.getObject(4),
                        row.getString(5),
                        (Long) row.getObject(6),
                        (Long) row.getObject(7),
                        (Long) row.getObject(8),
                        (Long) row.getObject(9),
                        (Long) row.getObject(10)
                ));
                if (before != null) {
                    throw new AssertionError("한 예측에 줄이 둘이다: " + row.getLong(1));
                }
            });
        return rows;
    }

    private static Set<Long> observationsIn(Map<Long, Row> rows, String state) {
        return rows.entrySet().stream()
            .filter(entry -> entry.getValue().state().equals(state))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
    }

    /** 뷰의 한 행에서 견주는 열이다. 예측마다 목표 정류장이 하나라 기준 관측 번호로 찾는다. */
    private record Row(
        String state,
        String reason,
        Double labelValue,
        String parameterHash,
        Long arrival,
        Long last,
        Long closure,
        Long previousLast,
        Long previousClosure
    ) {

        static Row withoutLabel(String state, String reason) {
            return new Row(
                    state,
                    reason,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );
        }
    }
}
