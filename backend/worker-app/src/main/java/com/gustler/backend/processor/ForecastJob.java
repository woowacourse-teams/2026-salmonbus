package com.gustler.backend.processor;

import com.gustler.backend.processor.seatdistribution.RuntimeSnapshot;

import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 예보가 안 붙은 판을 집어 그 판의 차량마다 앞 1~12정류장 예보를 내고 저장한다.
 *
 * <p>예보 쓰기는 관측을 저장하고 커밋한 뒤 별도 transaction 이다. 수집 사다리에 추론 시간이 섞이면
 * 예보가 실패할 때 관측까지 되돌아가고, 놓친 순간은 되살릴 수 없다.
 *
 * <p>도는 배포가 없거나 모델 구현이 없으면 판을 하나도 안 연다. 계수 번들이 없는 동안의 정상
 * 상태이고, 응답이 그 노선을 아직 준비 중이라고 답하는 자리와 같다. 반쯤 채운 판을 남기는 것보다
 * 아무 판도 안 여는 편이 낫다. 예보가 안 붙은 판은 조회가 건너뛴다.
 */
@Component
@ConditionalOnProperty(prefix = "forecast", name = "enabled", havingValue = "true")
public class ForecastJob {

    private static final Logger log = LoggerFactory.getLogger(ForecastJob.class);

    /**
     * 창 밖 판을 두고 경고를 남기는 사이. 예보는 10초마다 도는데 밀린 상태는 한 회차에 안 풀려서,
     * 회차마다 남기면 같은 사실이 로그를 덮는다.
     */
    private static final Duration WARNING_INTERVAL = Duration.ofMinutes(1);

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final ForecastRuntime forecastRuntime;
    private final ForecastBatchWriter forecastBatchWriter;
    private final ForecastProperties properties;
    private final Clock clock;
    private Instant lastStaleWarningAt;

    /**
     * 모델이 없으면 켜져 있어도 아무것도 안 한다는 것을 한 번 남긴다.
     *
     * <p>안 남기면 예보가 조용히 0건인 상태와 batch 가 없어서 0건인 상태가 밖에서 같아 보인다.
     */
    @PostConstruct
    void 예보를_낼_수_있는지_남긴다() {
        if (forecastRuntime.resolveActive().isEmpty()) {
            log.warn("예보 배치가 켜져 있는데 쓸 계수가 없다. 도는 배포와 올라온 계수의 신원이 맞을 때까지 "
                + "batch 를 하나도 안 연다");
        }
    }

    public ForecastJob(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        RouteVersionRepository routeVersionRepository,
        ForecastRuntime forecastRuntime,
        ForecastBatchWriter forecastBatchWriter,
        ForecastProperties properties,
        Clock clock
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.forecastRuntime = forecastRuntime;
        this.forecastBatchWriter = forecastBatchWriter;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * 신선도 한계는 회차마다 한 번만 읽는다. 노선마다 다시 읽으면 같은 회차의 노선들이 서로 다른
     * 한계로 잘려서, 어느 판이 빠졌는지가 노선 순서에 따라 달라진다.
     */
    @Scheduled(fixedDelayString = "${forecast.interval}")
    public void writeForecasts() {
        Optional<RuntimeSnapshot> runtime = forecastRuntime.resolveActive();
        if (runtime.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Instant notBefore = now.minus(properties.staleness());
        Instant leftBehindFrom = now.minus(ForecastProperties.MAX_STALENESS);
        Instant oldestLeftBehind = null;
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds()) {
            writeForecastsOf(routeVersionId, notBefore, runtime.get());
            oldestLeftBehind = olderOf(
                oldestLeftBehind, leftBehindAt(routeVersionId, leftBehindFrom, notBefore));
        }
        warnIfLeftBehind(oldestLeftBehind, now);
    }

    /**
     * 이 노선에 창 밖으로 밀려난 판이 있으면 그중 가장 오래된 것의 관측 시각.
     *
     * <p>예보를 쓴 뒤에 묻는다. 먼저 물으면 이번 회차에 처리할 판까지 남은 것으로 센다.
     *
     * <p>거슬러 보는 폭이 창의 상한이다. 그보다 오래된 것은 밀린 것이 아니라 예보를 받을 일이
     * 없는 판이고, 옮겨 넣은 관측이 그 자리에 영영 남는다.
     */
    private Optional<Instant> leftBehindAt(
        final long routeVersionId,
        Instant from,
        Instant until
    ) {
        return vehicleTrajectoryRepository.findOldestLeftBehindAt(routeVersionId, from, until);
    }

    /**
     * 창 밖 판이 남았다는 것을 남긴다. 밀리기 시작하면 멀쩡한 관측을 버리는데 그 사이에 예보 수는
     * 그냥 0으로 보여서, 안 남기면 밀린 것과 판이 없는 것이 밖에서 같아 보인다.
     *
     * <p>사라지면 기억을 지운다. 다음에 다시 밀릴 때 첫 회차에 바로 남기려는 것이다.
     */
    private void warnIfLeftBehind(
        Instant oldestLeftBehind,
        Instant now
    ) {
        if (oldestLeftBehind == null) {
            lastStaleWarningAt = null;
            return;
        }
        if (lastStaleWarningAt != null && now.isBefore(lastStaleWarningAt.plus(WARNING_INTERVAL))) {
            return;
        }
        lastStaleWarningAt = now;
        log.warn("신선도 창보다 오래돼서 예보 없이 두고 가는 판이 남아 있다. 그중 가장 오래된 판의 "
            + "관측 시각={}", oldestLeftBehind);
    }

    private static Instant olderOf(
        Instant kept,
        Optional<Instant> candidate
    ) {
        if (candidate.isEmpty()) {
            return kept;
        }
        if (kept == null || candidate.get().isBefore(kept)) {
            return candidate.get();
        }
        return kept;
    }

    private void writeForecastsOf(
        final long routeVersionId,
        Instant notBefore,
        RuntimeSnapshot runtime
    ) {
        RouteStops stops = routeVersionRepository.readStops(routeVersionId);
        List<PendingForecastBatch> batches = vehicleTrajectoryRepository.findBatchesAwaitingForecast(
            routeVersionId, notBefore, properties.batchLimit());
        for (PendingForecastBatch batch : batches) {
            forecastBatchWriter.writeForecastsOf(batch, stops, runtime);
        }
    }
}
