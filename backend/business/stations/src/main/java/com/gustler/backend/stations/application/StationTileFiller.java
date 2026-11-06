package com.gustler.backend.stations.application;

import com.gustler.backend.quota.api.StationCallQuota;
import com.gustler.backend.station.contract.StationTileProbes;
import com.gustler.backend.stations.api.FillStationTiles;
import com.gustler.backend.stations.api.StationFillPolicy;
import com.gustler.backend.stations.domain.BusStation;
import com.gustler.backend.stations.domain.StationAroundSource;
import com.gustler.backend.stations.domain.StationFillThrottle;
import com.gustler.backend.stations.domain.StationTileStore;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 열람 요청이 들어온 정류장 칸만 갱신한다. 한 번 부를 때 탐색점 하나만 처리한다.
 *
 * <p>원천 호출은 트랜잭션 밖에서 하고, 결과만 짧은 트랜잭션으로 저장한다. 같은 칸의 다음 점은 다음 실행에서 부른다.
 * 요청 없는 지역으로 확장하지 않는다. 오래된 칸은 다음 열람 요청이 접수했을 때만 갱신한다.
 *
 * <p>한 회차에서 연속 실패가 정책 상한에 닿거나, 다시 불러도 같은 결과가 나올 실패(원천 결과 코드 오류·잘못된 정류장 행)는
 * 그 칸의 접수를 거둔다. 이전 정류장 자료는 남기고 다음 열람 요청이 새로 접수한다.
 */
public final class StationTileFiller implements FillStationTiles {

    private static final Logger LOGGER = LoggerFactory.getLogger(StationTileFiller.class);
    private final StationTileStore store;
    private final StationAroundSource source;
    private final StationFillPolicy policy;
    private final StationFillThrottle throttle;
    private final Clock clock;
    private final StationCallQuota quota;

    private int consecutiveFailures;

    public StationTileFiller(
        StationTileStore store,
        StationAroundSource source,
        StationFillPolicy policy,
        Clock clock,
        StationCallQuota quota
    ) {
        this.store = store;
        this.source = source;
        this.policy = policy;
        this.clock = clock;
        this.quota = quota;
        this.throttle = new StationFillThrottle(
            policy.blockWait(),
            policy.blockWaitMaximum(),
            policy.dailyLimit(),
            policy.remainingFloor()
        );
    }

    @Override
    public FillStationTiles.Step fillNext() {
        Instant now = clock.instant();
        if (!throttle.mayCall(now)) {
            return FillStationTiles.Step.PAUSED;
        }
        Optional<StationTileStore.TilePass> next = store.next(now, now.minus(policy.refreshInterval()));
        if (next.isEmpty()) {
            return FillStationTiles.Step.NOTHING_TO_DO;
        }
        StationTileStore.TilePass pass = next.get();
        List<StationTileProbes.Probe> probes = StationTileProbes.of(pass.tile());
        StationTileProbes.Probe probe = probes.get(pass.probesDone());
        StationAroundSource.Result result = call(probe);
        if (result instanceof StationAroundSource.Ambiguous) {
            // 정류장 없는 좌표를 동시에 부를 때만 생겼다. 순차로 한 번 더 부르면 결과 코드 4로 바뀐다.
            result = call(probe);
        }
        return handle(pass, probes.size(), result, clock.instant());
    }

    private StationAroundSource.Result call(StationTileProbes.Probe probe) {
        Instant dispatchAt = clock.instant();
        if (!quota.reserve(policy.keyAlias(), dispatchAt, policy.dailyLimit())) {
            return new StationAroundSource.Exhausted("DAILY_LIMIT");
        }
        throttle.called(dispatchAt);
        return source.around(probe.x(), probe.y(), policy.keyAlias());
    }

    private FillStationTiles.Step handle(
        StationTileStore.TilePass pass,
        final int probeCount,
        StationAroundSource.Result result,
        Instant now
    ) {
        return switch (result) {
            case StationAroundSource.Found found -> {
                observeRemaining(found.remaining(), now);
                yield record(pass, probeCount, found.stations(), now);
            }
            case StationAroundSource.NoStations empty -> {
                observeRemaining(empty.remaining(), now);
                yield record(pass, probeCount, List.of(), now);
            }
            case StationAroundSource.Throttled ignored -> {
                throttle.blocked(now);
                LOGGER.warn("event=station_fill_paused reason=THROTTLED until={}", throttle.pausedUntil());
                yield FillStationTiles.Step.PAUSED;
            }
            case StationAroundSource.Exhausted exhausted -> {
                quota.blockForDay(policy.keyAlias(), now);
                throttle.exhausted(now, exhausted.reason());
                LOGGER.warn(
                    "event=station_fill_paused reason={} until={}",
                    exhausted.reason(),
                    throttle.pausedUntil()
                );
                yield FillStationTiles.Step.PAUSED;
            }
            case StationAroundSource.Ambiguous ignored ->
                fail(pass, "AMBIGUOUS_EMPTY_RESPONSE", null, now);
            case StationAroundSource.Failed failed ->
                fail(pass, failed.reason(), failed.retryAfter(), now);
        };
    }

    private void observeRemaining(Integer remaining, Instant now) {
        throttle.observedRemaining(remaining, now);
        if (remaining != null && remaining < policy.remainingFloor()) {
            quota.blockForDay(policy.keyAlias(), now);
        }
    }

    private FillStationTiles.Step record(
        StationTileStore.TilePass pass,
        final int probeCount,
        List<BusStation> stations,
        Instant now
    ) {
        consecutiveFailures = 0;
        store.recordProbe(pass, stations, now);
        if (pass.probesDone() + 1 < probeCount) {
            return FillStationTiles.Step.PROBED;
        }
        LOGGER.info(
            "event=station_tile_filled tile={}/{} callsToday={}",
            pass.tile().x(),
            pass.tile().y(),
            throttle.callsToday()
        );
        return FillStationTiles.Step.TILE_COMPLETED;
    }

    private FillStationTiles.Step fail(
        StationTileStore.TilePass pass,
        String reason,
        Duration retryAfter,
        Instant now
    ) {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 10);
        Duration wait = policy.retryBase().multipliedBy(1L << (consecutiveFailures - 1));
        if (wait.compareTo(policy.retryMaximum()) > 0) {
            wait = policy.retryMaximum();
        }
        if (retryAfter != null && retryAfter.compareTo(wait) > 0) {
            wait = retryAfter;
        }
        Instant retryAt = now.plus(wait);
        if (isPermanent(reason)) {
            return giveUp(pass, reason, retryAt, 1);
        }
        final int failures = store.fail(pass, reason, retryAt);
        if (failures >= policy.maxFailures()) {
            return giveUp(pass, reason, retryAt, failures);
        }
        LOGGER.warn(
            "event=station_tile_failed tile={}/{} reason={} retryIn={}",
            pass.tile().x(),
            pass.tile().y(),
            reason,
            wait
        );
        return FillStationTiles.Step.FAILED;
    }

    private FillStationTiles.Step giveUp(
        StationTileStore.TilePass pass,
        String reason,
        Instant retryAt,
        final int failures
    ) {
        store.giveUp(pass, reason, retryAt);
        LOGGER.warn(
            "event=station_tile_given_up tile={}/{} reason={} failures={}",
            pass.tile().x(),
            pass.tile().y(),
            reason,
            failures
        );
        return FillStationTiles.Step.FAILED;
    }

    private static boolean isPermanent(String reason) {
        return reason.equals("INVALID_STATION_ROW");
    }
}
