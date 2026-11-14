package com.gustler.backend.forecasting.infrastructure.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.application.pipeline.PassLabel;
import com.gustler.backend.forecasting.application.pipeline.PassLabelCalculator.PassLabelCalculation;
import com.gustler.backend.forecasting.application.pipeline.ScoringResult;
import com.gustler.backend.forecasting.application.pipeline.ScoringStage;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.pipeline.SourceDates;
import com.gustler.backend.forecasting.infrastructure.jdbc.ConfirmedPassLabelCalculator;
import com.gustler.backend.forecasting.support.ForecastingIntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

/**
 * 모델 배포의 정산 근거를 읽는 JDBC 구현이 옛 확정 판 정산과 같은 근거·같은 계수 해시를 내는지 본다. 옛 정산은 압축 저장한 예측의
 * 근거를 {@code (context->'shared') || 예측 근거 || {"alighting":{"stops": context->'stops'->목표}}}의 jsonb 텍스트로 받아 읽는다.
 */
@ForecastingIntegrationTest
@Transactional
class JdbcScoringSourcesTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final Instant MORNING = Instant.parse("2026-01-04T23:00:00Z");
    private static final Instant MIDDAY = Instant.parse("2026-01-05T04:00:00Z");
    private static final String LEGACY_EVIDENCE = """
        SELECT ((context -> 'shared') || jsonb_build_object('capacity', :capacity)
            || jsonb_build_object('alighting', jsonb_build_object('stops', context -> 'stops' -> CAST(:target AS text))))::text
        FROM (SELECT settlement_context -> CAST(:version AS text) AS context FROM model_deployment WHERE id = :deployment) c
        """;

    @Autowired
    JdbcClient jdbc;
    private final AtomicLong batches = new AtomicLong(100);
    private long version;
    private long deployment;

    @BeforeEach
    void 모델_배포에_정산_근거를_적는다() {
        version = 900_000 + Math.abs(UUID.randomUUID().getMostSignificantBits() % 100_000);
        String parameters = """
            "r": 2.0, "kappa": 1.000, "p": 0.1200, "lam": [1.50, 15e-1, 1.6, 1.7000, 1.8, 1.9, 2.0, 2.10]""";
        StringBuilder cells = new StringBuilder();
        for (String key : AlightingTimeCell.extendedKeys()) {
            cells.append(cells.isEmpty() ? "" : ",").append("\"%s\":{%s}".formatted(key, parameters));
        }
        String context = """
            {"%d": {"stops": {"3": [{"band": "evening", %s, "stop": 3}, {"stop": 3, "band": "morning", %s}]},
              "shared": {"turn": 9, "realStopOrders": [5, 1, 2, 3, 4], "holidays": ["2026-01-01"],
                "inputProfile": "alighting-confirmed-closure-v2", "capacityPolicy": "vehicle-capacity-snapshot-v1",
                "extendedSettlement": {%s}}}}
            """.formatted(version, parameters, parameters, cells);
        deployment = jdbc.sql("""
            INSERT INTO model_deployment(deployment_key,release_id,model_key,model_version,bundle_digest,
                prediction_target_version,calculation_version,supported_scope_digest,data_until,state,settlement_context)
            VALUES(?,'scoring-sources-test','alighting-gate-base-and-pool','v1',?,'after-alighting-and-departure-zero-v1',
                'alighting-online-capacity-snapshot-v2',?,?,'STAGED',CAST(? AS jsonb)) RETURNING id
            """).params(UUID.randomUUID(), "c".repeat(64), "c".repeat(64), offset(MORNING.minusSeconds(86400)), context)
            .query(Long.class).single();
    }

    @Test
    void 출퇴근_정류장_계수로_낸_정답과_계수_해시가_옛_정산이_펼친_근거로_낸_것과_같다() {
        // given
        ScoringStage stage = stage();
        Instant closed = MORNING.plusSeconds(300);
        PassClosed previous = pass("bus-c", 60, closed.minusSeconds(200), closed.minusSeconds(180), 900);
        PassClosed current = pass("bus-a", 50, closed.minusSeconds(30), closed, 901);
        stage.accept(forecast(MORNING, 500, "bus-a", 50, 40));
        stage.accept(previous);

        // when
        ScoringResult result = stage.accept(current);

        // then
        PassLabelCalculation legacy = legacy(current, previous, 40);
        assertThat(legacy.settled()).isTrue();
        assertThat(result.labels()).singleElement().satisfies(label -> assertSame(label, legacy));
    }

    @Test
    void 시간_칸_계수로_낸_정답과_계수_해시가_옛_정산이_펼친_근거로_낸_것과_같다() {
        // given
        ScoringStage stage = stage();
        Instant closed = MIDDAY.plusSeconds(300);
        PassClosed previous = pass("bus-c", 60, closed.minusSeconds(200), closed.minusSeconds(180), 900);
        PassClosed current = pass("bus-a", 50, closed.minusSeconds(30), closed, 901);
        stage.accept(forecast(MIDDAY, 500, "bus-a", 50, 45));
        stage.accept(previous);

        // when
        ScoringResult result = stage.accept(current);

        // then
        PassLabelCalculation legacy = legacy(current, previous, 45);
        assertThat(legacy.settled()).isTrue();
        assertThat(result.labels()).singleElement().satisfies(label -> assertSame(label, legacy));
    }

    @Test
    void 정산_근거가_없는_노선_판본은_빈_값이고_잠시_다시_묻지_않는다() {
        // given
        JdbcSettlementContextProvider provider = new JdbcSettlementContextProvider(jdbc, CLOCK);
        provider.find(deployment, version + 1);
        jdbc.sql("""
            UPDATE model_deployment SET settlement_context = settlement_context
                || jsonb_build_object(CAST(? AS text), settlement_context -> CAST(? AS text))
            WHERE id = ?
            """).params(Long.toString(version + 1), Long.toString(version), deployment).update();

        // when
        boolean foundAgain = provider.find(deployment, version + 1).isPresent();
        boolean foundByFreshProvider = new JdbcSettlementContextProvider(jdbc, CLOCK).find(deployment, version + 1)
            .isPresent();

        // then
        assertThat(foundAgain).isFalse();
        assertThat(foundByFreshProvider).isTrue();
    }

    @Test
    void 실정류장은_그_판본의_정산_근거가_적힌_가장_최근_배포에서_읽고_없으면_승차_가능_정류장을_쓴다() {
        // given
        final long routeId = jdbc.sql("""
            INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name)
            VALUES(:key,'GBIS',:key,:key,'A','B') RETURNING id
            """).param("key", "scoring-" + UUID.randomUUID().toString().substring(0, 8)).query(Long.class).single();
        final long bare = jdbc.sql("""
            INSERT INTO route_version(route_id,content_digest,valid_from) VALUES(?,?,?) RETURNING id
            """).params(routeId, "0".repeat(64), offset(MORNING)).query(Long.class).single();
        for (int order = 1; order <= 4; order++) {
            jdbc.sql("""
                INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed)
                VALUES(?,?,?,?,'UP',?)
                """).params(bare, order, "s" + order, "정류장" + order, order != 3).update();
        }
        JdbcRouteStructureProvider provider = new JdbcRouteStructureProvider(jdbc);

        // when
        List<Integer> recorded = provider.realStopOrders(version);
        List<Integer> fallback = provider.realStopOrders(bare);

        // then
        assertThat(recorded).containsExactly(5, 1, 2, 3, 4);
        assertThat(fallback).containsExactly(1, 2, 4);
    }

    private static void assertSame(PassLabel label, PassLabelCalculation legacy) {
        assertThat(label.state()).isEqualTo(PassLabel.State.SETTLED);
        assertThat(label.parameterHash()).isEqualTo(legacy.parameterHash());
        assertThat(Double.doubleToLongBits(label.labelValue())).isEqualTo(Double.doubleToLongBits(legacy.value()));
        assertThat(label.capacity()).isEqualTo(legacy.capacity());
    }

    private ScoringStage stage() {
        return new ScoringStage(new JdbcSettlementContextProvider(jdbc, CLOCK), new JdbcRouteStructureProvider(jdbc),
            new ConfirmedPassLabelCalculator(), CLOCK);
    }

    private PassLabelCalculation legacy(PassClosed pass, PassClosed previous, final int capacity) {
        String evidence = jdbc.sql(LEGACY_EVIDENCE)
            .param("capacity", capacity)
            .param("target", Integer.toString(pass.stopOrder()))
            .param("version", Long.toString(version))
            .param("deployment", deployment)
            .query(String.class).single();
        return new ConfirmedPassLabelCalculator().calculate(pass, previous, JSON.readTree(evidence));
    }

    private ForecastPublished forecast(Instant confirmedAt, long observation, String vehicle, long chain, int capacity) {
        VehicleForecast forecast = new VehicleForecast(observation, 0, vehicle, 1, chain, chain, false, capacity, false,
            new int[] {3}, new double[1], new double[1]);
        return new ForecastPublished(SourceDates.of(confirmedAt), batches.incrementAndGet(), 7L, version,
            confirmedAt.minusSeconds(2), confirmedAt, deployment, ForecastOutcome.PUBLISHED, List.of(forecast));
    }

    private PassClosed pass(String vehicle, long chain, Instant lastAt, Instant closedAt, long closure) {
        return new PassClosed(SourceDates.of(closedAt), 7L, chain, 3, version, vehicle, chain, SourceDates.of(lastAt),
            closure - 300, 20, lastAt.minusSeconds(40), lastAt.minusSeconds(42), closure - 200, lastAt.minusSeconds(20),
            closure - 100, 18, lastAt, lastAt.minusSeconds(2), lastAt, closure, batches.incrementAndGet(), closedAt);
    }

    private static OffsetDateTime offset(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
