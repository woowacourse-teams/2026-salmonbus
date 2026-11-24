package com.gustler.backend.forecasting.support;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.ForecastStageResult;
import com.gustler.backend.forecasting.application.forecast.ObservationBatchRead;
import com.gustler.backend.forecasting.application.forecast.RouteStateReplayer;
import com.gustler.backend.forecasting.application.forecast.RunForecastsService;
import com.gustler.backend.forecasting.application.forecast.ScoringReplay;
import com.gustler.backend.forecasting.application.forecast.ScoringResult;
import com.gustler.backend.forecasting.application.forecast.ScoringService;
import com.gustler.backend.forecasting.configuration.ForecastAssembly;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.capacity.CachedVehicleCapacities;
import com.gustler.backend.forecasting.infrastructure.capacity.JdbcVehicleCapacityStore;
import com.gustler.backend.forecasting.infrastructure.capacity.VehicleCapacityCache;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcPendingBatches;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcReplaySource;
import com.gustler.backend.forecasting.infrastructure.jdbc.JdbcRouteVersionQuery;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 노선 하나의 하루치 배치를 worker의 예보와 채점에 그대로 흘린다.
 *
 * <p>예보({@link RunForecastsService})와 채점({@link ScoringService})을 worker의 구성이 쓰는 조립
 * ({@link ForecastAssembly})과 고정 값으로 묶는다. worker와 다른 것은 세 가지다.
 * <ul>
 *   <li>시계는 {@link WorkerReplayClock}이다. 배치마다 자료에 적힌 착수 시각으로 맞춰, 예보가 찍는 착수 시각이 자료와 같아지게
 *       한다.</li>
 *   <li>노선별 실행 줄과 채점 줄은 넣은 일을 부른 스레드에서 바로 실행한다. 자료의 배치는 미리 모두 DB에 있고, 예보는 재생이
 *       커밋한 것으로 친 배치만 본다({@link CommittedBatches}). 그래서 배치는 재생이 정한 순서로 하나씩 처리된다.</li>
 *   <li>처리할 노선 판본은 유효 기간과 무관하게 자료의 판본 전부다. 지난 날을 재생하므로 그날의 판본이 지금은 닫혀 있을 수
 *       있다.</li>
 * </ul>
 * 노선 상태는 예보가 스스로 만든다. 그날의 첫 배치에서 저장된 관측을 되감는데, 그 앞의 관측이 DB에 없으므로 빈 상태에서 시작한다.
 */
final class WorkerReplayRunner {

    /** worker 설정 파일의 신선도 창과 한 번에 읽는 배치 수다. */
    private static final ForecastPolicy POLICY = new ForecastPolicy(Duration.ofMinutes(10), 20);
    /** 줄 열쇠를 익히는 줍기에서 어느 배치도 신선도 창에 들지 않게 시계를 마지막 배치 뒤로 보내는 간격. */
    private static final Duration BEYOND_STALENESS = Duration.ofDays(1);
    /** 마지막 배치 뒤에 기다리던 예측을 모두 마감하려고 시계를 보내는 간격. 마감(2시간)과 한가한 노선의 마감 주기보다 길다. */
    private static final Duration PAST_EVERY_DEADLINE = Duration.ofHours(3);

    private final WorkerReplayClock clock = new WorkerReplayClock();
    private final ConfirmedAlightingAwareWorkerReplay.Stores stores;
    private final RuntimeSnapshot runtime;
    private final ForecastAssembly assembly;
    private final JdbcReplaySource source;
    private final JdbcRouteVersionQuery routeStops;
    private final ForecastStage stage;
    private final RouteStateReplayer replayer;
    private final ScoringService scoring;
    private final RunForecastsService forecasts;
    private final CommittedBatches committed;

    /**
     * @param runtime 재생에 쓰는 활성 배포와 모델
     * @param routeVersionIds 처리할 노선 판본
     */
    WorkerReplayRunner(
        ConfirmedAlightingAwareWorkerReplay.Stores stores,
        RuntimeSnapshot runtime,
        List<Long> routeVersionIds
    ) {
        JdbcClient jdbc = stores.jdbc();
        List<Long> versions = List.copyOf(routeVersionIds);
        VehicleCapacityCache capacities = new VehicleCapacityCache(new JdbcVehicleCapacityStore(jdbc));
        this.stores = stores;
        this.runtime = runtime;
        this.assembly = new ForecastAssembly(
                jdbc,
                stores.transactions(),
                clock,
                null
        );
        this.source = new JdbcReplaySource(jdbc, JdbcReplaySource.DEFAULT_MAXIMUM_ROWS);
        this.routeStops = new JdbcRouteVersionQuery(jdbc);
        this.stage = new ForecastStage(new CachedVehicleCapacities(capacities));
        this.replayer = new RouteStateReplayer(
                source,
                stage,
                RouteStateReplayer.DEFAULT_WINDOW
        );
        this.committed = new CommittedBatches(new JdbcPendingBatches(jdbc));
        this.scoring = assembly.scoring(
                task -> {
                    task.run();
                    return true;
                },
                stores.records(),
                stores.records(),
                stores.passes(),
                stores.labels()
        );
        ForecastAssembly.Sources sources = new ForecastAssembly.Sources(
                source,
                source,
                committed,
                () -> Optional.of(runtime),
                () -> versions,
                routeStops
        );
        this.forecasts = assembly.forecast(
                (routeKey, task) -> {
                    task.run();
                    return true;
                },
                scoring,
                sources,
                capacities,
                stores.records(),
                stores.passes(),
                ForecastAssembly.Settings.of(POLICY)
        );
    }

    /**
     * 배치를 순서대로 예보에 넣고, 마지막 배치 뒤에 기다리던 예측을 모두 마감한다.
     *
     * <p>{@code resignaled}의 배치는 처리한 직후 한 번 더 넣는다. 이미 처리한 배치를 다시 받아도 예보 실행의 착수 시각이 그대로여야
     * 한다.
     *
     * @param batches 재생할 배치. (착수 시각, 배치 번호) 순서다
     * @param resignaled 처리한 직후 한 번 더 넣을 배치
     * @throws IllegalStateException 다시 넣은 배치의 착수 시각이 바뀌었으면 던진다
     */
    void run(List<WorkerReplayFixture.Batch> batches, Set<Long> resignaled) {
        if (batches.isEmpty()) {
            return;
        }
        WorkerReplayFixture.Batch last = batches.getLast();
        clock.moveTo(last.responseAt().plus(BEYOND_STALENESS));
        forecasts.pickUp();
        for (WorkerReplayFixture.Batch batch : batches) {
            clock.moveTo(batch.confirmedAt());
            committed.commit(batch.id());
            forecasts.batchStored(batch.id(), batch.routeVersionId());
            if (resignaled.contains(batch.id())) {
                signalAgain(batch);
            }
            scoring.flush();
        }
        clock.moveTo(last.confirmedAt().plus(PAST_EVERY_DEADLINE));
        scoring.tick();
        scoring.flush();
    }

    private void signalAgain(WorkerReplayFixture.Batch batch) {
        clock.moveTo(batch.confirmedAt().plusSeconds(1));
        forecasts.batchStored(batch.id(), batch.routeVersionId());
        Optional<Instant> confirmedAt = runConfirmedAt(batch.id());
        if (confirmedAt.isPresent() && !confirmedAt.get().equals(batch.confirmedAt().truncatedTo(ChronoUnit.MICROS))) {
            throw new IllegalStateException("다시 넣은 배치의 착수 시각이 바뀌었다: " + batch.id());
        }
    }

    private Optional<Instant> runConfirmedAt(final long batchId) {
        return stores.jdbc().sql("SELECT confirmed_at FROM forecast_run WHERE observation_batch_id=?")
            .param(batchId).query(OffsetDateTime.class).optional().map(OffsetDateTime::toInstant);
    }

    /**
     * worker가 그 배치를 처리하기 직전에 다시 뜬 것처럼, 저장된 관측을 되감아 만든 노선 상태로 그 배치의 예보 입력을 다시 만든다.
     * DB에 쓰지 않는다.
     *
     * @param batchId 예보 실행이 저장된 배치
     * @return 배치에 예보 실행이 없어 착수 시각을 모르면 빈 값
     * @throws IllegalStateException 되감기가 실패하면 던진다
     */
    Optional<Restarted> restartedAt(final long batchId) {
        Optional<ObservationBatchRead> found = source.readBatch(batchId);
        if (found.isEmpty() || found.get().batch().confirmedAt() == null) {
            return Optional.empty();
        }
        ObservationBatchRead read = found.get();
        RouteStops stops = routeStops.readStops(read.batch().routeVersionId());
        AlightingAwareRouteStatistics statistics = runtime.model().statistics(stops);
        RouteStateReplayer.Result replayed = replayer.replay(statistics, read.batch().confirmedAt(), batchId - 1);
        if (!replayed.succeeded()) {
            throw new IllegalStateException("저장된 관측을 되감지 못했다: " + batchId + " " + replayed.failureReason());
        }
        return Optional.of(new Restarted(statistics, stops,
            stage.advance(
                    replayed.state(),
                    statistics,
                    read.capacitySourceId(),
                    read.batch()
            )));
    }

    /**
     * 채점이 {@code through}에 닫힌 통과까지 처리한 뒤 다시 뜬 것처럼, 저장된 예보·통과 기록만으로 빈 채점 상태를 다시 채우며
     * 나온 결과를 돌려준다. DB에 쓰지 않는다.
     *
     * @param through 이 시각에 닫힌 통과까지 다시 흘린다
     */
    ScoringResult rescoredThrough(final long routeId, Instant through) {
        return new ScoringReplay(stores.records(), stores.passes())
            .replay(assembly.scoringStage(), routeId, through.plus(1, ChronoUnit.MICROS));
    }

    /**
     * 되감아 만든 상태로 다시 만든 배치 하나의 예보 입력이다.
     *
     * @param statistics 배치의 노선 판본에 쓰는 모델 통계
     * @param stops 배치의 노선 판본 정류장
     * @param result 예보 단계의 결과
     */
    record Restarted(AlightingAwareRouteStatistics statistics, RouteStops stops, ForecastStageResult result) {

        Optional<AlightingAwareFeatureSnapshot> snapshot(final long observationId) {
            return result.snapshots().stream()
                .filter(snapshot -> snapshot.vehicleObservationId() == observationId)
                .findFirst();
        }

        /** 그 관측이 예보 입력에서 빠진 사유다. 빠진 차량 목록에 없으면 예보 단계의 결과 사유를 돌려준다. */
        String withheldReason(final long observationId) {
            return result.withheld().stream()
                .filter(vehicle -> vehicle.observationId() == observationId)
                .map(ForecastStageResult.WithheldVehicle::reason)
                .findFirst()
                .orElse(String.valueOf(result.reason()));
        }
    }
}
