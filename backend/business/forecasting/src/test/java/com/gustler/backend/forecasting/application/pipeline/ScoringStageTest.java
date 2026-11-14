package com.gustler.backend.forecasting.application.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.api.pipeline.CollectionResult;
import com.gustler.backend.forecasting.api.pipeline.ForecastOutcome;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished;
import com.gustler.backend.forecasting.api.pipeline.ForecastPublished.VehicleForecast;
import com.gustler.backend.forecasting.api.pipeline.ObservationBatchSeen;
import com.gustler.backend.forecasting.api.pipeline.PassClosed;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.infrastructure.jdbc.ConfirmedPassLabelCalculator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class ScoringStageTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
    private static final long ROUTE = 7L;
    private static final long VERSION = 70L;
    private static final long DEPLOYMENT = 3L;
    private static final long OTHER_DEPLOYMENT = 4L;
    private static final Instant MORNING = Instant.parse("2026-01-04T23:00:00Z");

    private final Map<Long, JsonNode> contexts = new HashMap<>();
    private final AtomicLong batches = new AtomicLong(100);
    private ScoringStage stage;

    @BeforeEach
    void 채점_단계를_준비한다() {
        contexts.put(DEPLOYMENT, context(0.12, "alighting-confirmed-closure-v2"));
        stage = new ScoringStage((deployment, version) -> Optional.ofNullable(contexts.get(deployment)),
            version -> List.of(5, 1, 2, 3, 4, 2), new ConfirmedPassLabelCalculator(), CLOCK);
    }

    @Test
    void 같은_시각에_닫힌_통과는_서로_직전이_되지_않고_그보다_앞선_통과를_직전으로_쓴다() {
        // given
        Instant first = MORNING.plusSeconds(60);
        Instant together = MORNING.plusSeconds(240);
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3), vehicle(501, "bus-b", 51, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, first.minusSeconds(20), first, 900));

        // when
        ScoringResult result = stage.accept(pass("bus-b", 51, 3, together.minusSeconds(30), together, 902))
            .plus(stage.accept(pass("bus-a", 50, 3, together.minusSeconds(40), together, 901)));

        // then
        assertThat(result.labels()).hasSize(2).allSatisfy(label -> {
            assertThat(label.previousLastObservationId()).isEqualTo(lastObservation(900));
            assertThat(label.previousClosureObservationId()).isEqualTo(900);
        });
        assertThat(result.scored()).extracting(ScoredForecast::vehicleObservationId).containsExactlyInAnyOrder(500L, 501L);
    }

    @Test
    void 같은_시각의_앞선_통과가_여럿이면_마지막_관측_번호가_작은_통과를_직전으로_쓴다() {
        // given
        Instant earlier = MORNING.plusSeconds(60);
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(pass("bus-d", 70, 3, earlier.minusSeconds(20), earlier, 920));
        stage.accept(pass("bus-c", 60, 3, earlier.minusSeconds(25), earlier, 910));

        // when
        ScoringResult result = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // then
        assertThat(result.labels()).singleElement()
            .extracting(PassLabel::previousLastObservationId).isEqualTo(lastObservation(910));
    }

    @Test
    void 직전_통과의_마지막_관측이_이번_통과보다_늦으면_앞차_간격_사유로_닫는다() {
        // given
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, closed.minusSeconds(10), closed.minusSeconds(5), 900));

        // when
        ScoringResult result = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(60), closed, 901));

        // then
        assertThat(result.labels()).singleElement().satisfies(label -> {
            assertThat(label.state()).isEqualTo(PassLabel.State.UNSUPPORTED);
            assertThat(label.reason()).isEqualTo("UNSUPPORTED_HEADWAY");
            assertThat(label.previousLastObservationId()).isEqualTo(lastObservation(900));
        });
    }

    @Test
    void 자정을_넘겨_닫힌_통과는_출발_날짜가_같은_통과만_직전으로_본다() {
        // given
        Instant midnight = LocalDate.of(2026, 1, 6).atStartOfDay(SEOUL).toInstant();
        stage.accept(forecast(midnight.minusSeconds(600), vehicle(500, "bus-a", 50, 40, 3),
            vehicle(501, "bus-b", 51, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, midnight.minusSeconds(300), midnight.minusSeconds(280), 900));

        // when
        ScoringResult yesterday = stage.accept(pass("bus-a", 50, 3, midnight.minusSeconds(30), midnight.plusSeconds(40),
            901));
        ScoringResult today = stage.accept(pass("bus-b", 51, 3, midnight.plusSeconds(30), midnight.plusSeconds(60),
            902));

        // then
        assertThat(yesterday.labels()).singleElement().satisfies(label -> {
            assertThat(label.state()).isEqualTo(PassLabel.State.SETTLED);
            assertThat(label.previousLastObservationId()).isEqualTo(lastObservation(900));
            assertThat(label.sourceDate()).isEqualTo(LocalDate.of(2026, 1, 6));
        });
        assertThat(today.labels()).singleElement().satisfies(label -> {
            assertThat(label.reason()).isEqualTo("NO_PREVIOUS_PASS");
            assertThat(label.previousLastObservationId()).isNull();
        });
    }

    @Test
    void 정원이_다른_예측이_한_통과를_기다리면_정원마다_정답을_낸다() {
        // given
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(forecast(MORNING.plusSeconds(15), vehicle(501, "bus-a", 50, 45, 3)));
        stage.accept(pass("bus-c", 60, 3, closed.minusSeconds(200), closed.minusSeconds(180), 900));

        // when
        ScoringResult result = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // then
        assertThat(result.labels()).extracting(PassLabel::capacity).containsExactly(40, 45);
        assertThat(result.labels()).allSatisfy(label -> assertThat(label.state()).isEqualTo(PassLabel.State.SETTLED));
        assertThat(result.labels().get(0).labelValue()).isNotEqualTo(result.labels().get(1).labelValue());
        assertThat(result.scored()).extracting(ScoredForecast::capacity).containsExactly(40, 45);
        assertThat(stage.waitingCount()).isZero();
    }

    @Test
    void 도달_관측이_기준_시각_10초_뒤를_넘어야_짝이_되고_아니면_통과가_닫힐_때_조건_불충족으로_닫는다() {
        // given
        Instant closed = MORNING.plusSeconds(120);
        stage.accept(forecast(MORNING.minusMillis(1), vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(forecast(MORNING, vehicle(501, "bus-a", 50, 40, 3)));

        // when
        ScoringResult closing = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), MORNING.plusSeconds(10),
            closed, 901));
        ScoringResult expired = stage.accept(seen(MORNING.plus(Duration.ofHours(3))));

        // then
        assertThat(closing.scored()).extracting(ScoredForecast::vehicleObservationId).containsExactly(500L);
        assertThat(closing.unresolved()).singleElement().satisfies(closedForecast -> {
            assertThat(closedForecast.vehicleObservationId()).isEqualTo(501L);
            assertThat(closedForecast.state()).isEqualTo(UnresolvedForecast.State.LOST);
            assertThat(closedForecast.reason()).isEqualTo(ScoringReasons.PASS_CONDITION_UNMET);
        });
        assertThat(expired.unresolved()).isEmpty();
        assertThat(stage.waitingCount()).isZero();
    }

    @Test
    void 통과가_이미_닫힌_연결과_정류장의_예측은_받는_순간_조건_불충족으로_닫는다() {
        // given
        Instant closed = MORNING.plusSeconds(120);
        stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // when
        ScoringResult result = stage.accept(forecast(closed.plusSeconds(20), vehicle(502, "bus-a", 50, 40, 3)));

        // then
        assertThat(result.unresolved()).singleElement().satisfies(closedForecast -> {
            assertThat(closedForecast.state()).isEqualTo(UnresolvedForecast.State.LOST);
            assertThat(closedForecast.reason()).isEqualTo(ScoringReasons.PASS_CONDITION_UNMET);
        });
        assertThat(stage.waitingCount()).isZero();
    }

    @Test
    void 기준_시각_2시간_뒤에_닫힌_통과까지_짝이_되고_그보다_늦으면_먼저_마감한다() {
        // given
        Instant closed = MORNING.plus(Duration.ofHours(2));
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(forecast(MORNING.minusMillis(1), vehicle(499, "bus-b", 51, 40, 3)));

        // when
        ScoringResult onTime = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));
        ScoringResult late = stage.accept(pass("bus-b", 51, 3, closed.minusSeconds(30), closed.plusMillis(1), 902));

        // then
        assertThat(onTime.scored()).extracting(ScoredForecast::vehicleObservationId).containsExactly(500L);
        assertThat(onTime.unresolved()).singleElement().satisfies(closedForecast -> {
            assertThat(closedForecast.vehicleObservationId()).isEqualTo(499L);
            assertThat(closedForecast.reason()).isEqualTo(ScoringReasons.PASS_NOT_CLOSED);
        });
        assertThat(late.scored()).isEmpty();
        assertThat(late.labels()).isEmpty();
    }

    @Test
    void 첫_마지막_실정류장과_실정류장이_아닌_목표는_기다리지_않고_바로_놓침으로_닫는다() {
        // when
        ScoringResult result = stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 1, 3, 5, 6)));

        // then
        assertThat(result.unresolved()).extracting(UnresolvedForecast::targetStopOrder).containsExactly(1, 5, 6);
        assertThat(result.unresolved()).allSatisfy(closed -> {
            assertThat(closed.state()).isEqualTo(UnresolvedForecast.State.LOST);
            assertThat(closed.reason()).isEqualTo(ScoringReasons.TARGET_WITHOUT_PASS);
            assertThat(ScoringReasons.legacyReason(closed.reason())).contains("NO_CLOSED_IDENTITY_SAFE_PASS");
        });
        assertThat(stage.waitingCount()).isOne();
    }

    @Test
    void 마감_때_연결이_바뀐_차량과_통과가_없는_차량의_사유를_나눈다() {
        // given
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3), vehicle(501, "bus-b", 51, 40, 3)));
        stage.accept(forecast(MORNING.plusSeconds(200), vehicle(600, "bus-a", 77, 40, 4)));

        // when
        ScoringResult expired = stage.accept(seen(MORNING.plus(Duration.ofHours(2)).plusSeconds(1)));

        // then
        assertThat(expired.unresolved()).extracting(UnresolvedForecast::vehicleObservationId, UnresolvedForecast::reason)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(500L, ScoringReasons.CHAIN_BROKEN),
                org.assertj.core.groups.Tuple.tuple(501L, ScoringReasons.PASS_NOT_CLOSED));
        assertThat(stage.waitingCount()).isOne();
    }

    @Test
    void 벽시계_마감은_노선에_2시간_10분_넘게_배치가_없을_때만_한다() {
        // given
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));

        // when
        ScoringResult tooEarly = stage.expire(MORNING.plus(ScoringStage.IDLE_EXPIRY));
        ScoringResult idle = stage.expire(MORNING.plus(ScoringStage.IDLE_EXPIRY).plusSeconds(1));

        // then
        assertThat(tooEarly.unresolved()).isEmpty();
        assertThat(idle.unresolved()).singleElement()
            .extracting(UnresolvedForecast::reason).isEqualTo(ScoringReasons.PASS_NOT_CLOSED);
    }

    @Test
    void 같은_통과_같은_정원이라도_모델_배포가_다르면_배포마다_자기_계수로_정답을_낸다() {
        // given
        contexts.put(OTHER_DEPLOYMENT, context(0.3, "alighting-confirmed-closure-v2"));
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(forecast(MORNING, DEPLOYMENT, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(forecast(MORNING.plusSeconds(15), OTHER_DEPLOYMENT, vehicle(501, "bus-a", 50, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, closed.minusSeconds(200), closed.minusSeconds(180), 900));

        // when
        ScoringResult result = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // then
        assertThat(result.labels()).extracting(PassLabel::modelDeploymentId).containsExactly(DEPLOYMENT, OTHER_DEPLOYMENT);
        assertThat(result.labels()).allSatisfy(label -> {
            assertThat(label.state()).isEqualTo(PassLabel.State.SETTLED);
            assertThat(label.capacity()).isEqualTo(40);
        });
        assertThat(result.labels().get(0).labelValue()).isNotEqualTo(result.labels().get(1).labelValue());
        assertThat(result.labels().get(0).parameterHash()).isNotEqualTo(result.labels().get(1).parameterHash());
        assertThat(result.scored()).extracting(ScoredForecast::vehicleObservationId, ScoredForecast::modelDeploymentId)
            .containsExactly(org.assertj.core.groups.Tuple.tuple(500L, DEPLOYMENT),
                org.assertj.core.groups.Tuple.tuple(501L, OTHER_DEPLOYMENT));
        assertThat(result.unresolved()).isEmpty();
    }

    @Test
    void 계산식의_전제를_어긴_계수는_그_통과만_계산_실패로_닫는다() {
        // given
        contexts.put(DEPLOYMENT, context(1.5, "alighting-confirmed-closure-v2"));
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, closed.minusSeconds(200), closed.minusSeconds(180), 900));

        // when
        ScoringResult result = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // then
        assertThat(result.labels()).singleElement().satisfies(label -> {
            assertThat(label.state()).isEqualTo(PassLabel.State.UNSUPPORTED);
            assertThat(label.reason()).isEqualTo(ScoringReasons.SETTLEMENT_CALCULATION_FAILED);
        });
    }

    @Test
    void 정산_근거가_없거나_입력_판이_다르면_예보를_받을_때_지원_안_함으로_닫는다() {
        // given
        contexts.put(OTHER_DEPLOYMENT, context(0.12, "alighting-closure-v1"));

        // when
        ScoringResult missing = stage.accept(forecast(MORNING, 9L, vehicle(500, "bus-a", 50, 40, 3)));
        ScoringResult mismatch = stage.accept(forecast(MORNING.plusSeconds(15), OTHER_DEPLOYMENT,
            vehicle(501, "bus-a", 50, 40, 3)));

        // then
        assertThat(missing.unresolved()).singleElement()
            .extracting(UnresolvedForecast::reason).isEqualTo(ScoringReasons.SETTLEMENT_EVIDENCE_MISSING);
        assertThat(mismatch.unresolved()).singleElement()
            .extracting(UnresolvedForecast::reason).isEqualTo(ScoringReasons.INPUT_PROFILE_MISMATCH);
        assertThat(stage.waitingCount()).isZero();
    }

    @Test
    void 수집_구멍은_그날_00시부터_통과가_닫힌_시각까지만_본다() {
        // given
        Instant closed = MORNING.plusSeconds(300);
        stage.accept(holeBatch(MORNING.minus(Duration.ofHours(10))));
        stage.accept(seen(MORNING.minus(Duration.ofHours(9))));
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3), vehicle(501, "bus-b", 51, 40, 3)));
        stage.accept(pass("bus-c", 60, 3, closed.minusSeconds(200), closed.minusSeconds(180), 900));
        ScoringResult settled = stage.accept(pass("bus-a", 50, 3, closed.minusSeconds(30), closed, 901));

        // when
        stage.accept(holeBatch(closed.plusSeconds(10)));
        stage.accept(seen(closed.plusSeconds(15)));
        ScoringResult tainted = stage.accept(pass("bus-b", 51, 3, closed.plusSeconds(20), closed.plusSeconds(40),
            902));
        ScoringResult later = stage.accept(forecast(closed.plusSeconds(60), vehicle(502, "bus-d", 52, 40, 3)));

        // then
        assertThat(settled.labels()).singleElement().extracting(PassLabel::state).isEqualTo(PassLabel.State.SETTLED);
        assertThat(tainted.unresolved()).singleElement().satisfies(closedForecast -> {
            assertThat(closedForecast.vehicleObservationId()).isEqualTo(501L);
            assertThat(closedForecast.state()).isEqualTo(UnresolvedForecast.State.UNSUPPORTED);
            assertThat(closedForecast.reason()).isEqualTo(ScoringReasons.SOURCE_HISTORY_INCOMPLETE);
        });
        assertThat(tainted.labels()).isEmpty();
        assertThat(later.unresolved()).singleElement()
            .extracting(UnresolvedForecast::reason).isEqualTo(ScoringReasons.SOURCE_HISTORY_INCOMPLETE);
    }

    @Test
    void 늦게_착수한_배치의_줄_수_차이는_수집_구멍으로_보지_않는다() {
        // given
        long batch = batches.incrementAndGet();
        stage.accept(new ObservationBatchSeen(batch, ROUTE, VERSION, MORNING.minusSeconds(200),
            MORNING.minusSeconds(190), CollectionResult.SUCCESS, 3, 2));
        stage.accept(new ForecastPublished(day(MORNING), batch, ROUTE, VERSION, MORNING.minusSeconds(190),
            MORNING.minusSeconds(90), null, ForecastOutcome.LATE, List.of()));

        // when
        ScoringResult result = stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));

        // then
        assertThat(result.unresolved()).isEmpty();
        assertThat(stage.waitingCount()).isOne();
    }

    @Test
    void 상태를_버린_노선은_기다리던_예측이_없다() {
        // given
        stage.accept(forecast(MORNING, vehicle(500, "bus-a", 50, 40, 3)));

        // when
        stage.discard(ROUTE);

        // then
        assertThat(stage.waitingCount()).isZero();
        assertThat(stage.waitingCount(ROUTE)).isZero();
    }

    private ForecastPublished forecast(Instant confirmedAt, VehicleForecast... vehicles) {
        return forecast(confirmedAt, DEPLOYMENT, vehicles);
    }

    private ForecastPublished forecast(Instant confirmedAt, long deployment, VehicleForecast... vehicles) {
        return new ForecastPublished(day(confirmedAt), batches.incrementAndGet(), ROUTE, VERSION,
            confirmedAt.minusSeconds(2), confirmedAt, deployment, ForecastOutcome.PUBLISHED, List.of(vehicles));
    }

    private static VehicleForecast vehicle(long observation, String vehicle, long chain, int capacity, int... targets) {
        return new VehicleForecast(observation, 0, vehicle, 1, chain, chain, false, capacity, false, targets,
            new double[targets.length], new double[targets.length]);
    }

    private PassClosed pass(String vehicle, long chain, int stop, Instant lastAt, Instant closedAt, long closure) {
        return pass(vehicle, chain, stop, lastAt, lastAt, closedAt, closure);
    }

    private PassClosed pass(String vehicle, long chain, int stop, Instant lastAt, Instant reachedAt, Instant closedAt,
        long closure) {
        return new PassClosed(day(closedAt), ROUTE, chain, stop, VERSION, vehicle, chain, day(lastAt), closure - 300, 20,
            lastAt.minusSeconds(40), lastAt.minusSeconds(42), closure - 200, lastAt.minusSeconds(20),
            lastObservation(closure), 18, lastAt, lastAt.minusSeconds(2), reachedAt, closure, batches.incrementAndGet(),
            closedAt);
    }

    private ObservationBatchSeen seen(Instant at) {
        return new ObservationBatchSeen(batches.incrementAndGet(), ROUTE, VERSION, at, at, CollectionResult.EMPTY);
    }

    private ObservationBatchSeen holeBatch(Instant at) {
        return new ObservationBatchSeen(batches.incrementAndGet(), ROUTE, VERSION, at, at, CollectionResult.SUCCESS, 3,
            2);
    }

    private static long lastObservation(long closure) {
        return closure - 100;
    }

    private static LocalDate day(Instant at) {
        return at.atZone(SEOUL).toLocalDate();
    }

    private static JsonNode context(double probability, String inputProfile) {
        String parameters = "\"p\":%s,\"lam\":[1.5,1.5,1.5,1.5,1.5,1.5,1.5,1.5],\"kappa\":1,\"r\":2".formatted(probability);
        StringBuilder stops = new StringBuilder();
        for (int stop = 1; stop <= 6; stop++) {
            if (stop > 1) {
                stops.append(',');
            }
            stops.append("\"%d\":[{\"stop\":%d,\"band\":\"morning\",%s},{\"stop\":%d,\"band\":\"evening\",%s}]"
                .formatted(stop, stop, parameters, stop, parameters));
        }
        String extended = String.join(",", AlightingTimeCell.extendedKeys().stream()
            .map(key -> "\"%s\":{%s}".formatted(key, parameters)).toList());
        return JSON.readTree("""
            {"shared":{"capacityPolicy":"vehicle-capacity-snapshot-v1","inputProfile":"%s","turn":9,
              "realStopOrders":[1,2,3,4,5],"extendedSettlement":{%s}},"stops":{%s}}
            """.formatted(inputProfile, extended, stops));
    }
}
