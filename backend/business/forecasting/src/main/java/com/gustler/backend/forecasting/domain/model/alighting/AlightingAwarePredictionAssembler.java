package com.gustler.backend.forecasting.domain.model.alighting;

import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.VehicleStopTarget;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 하차 반영 모델에서 스냅샷 하나와 노선 정류장으로 목표 정류장별 예측값(기대 좌석, 만석 확률)을 만드는 규칙이다.
 *
 * <p>목표 정류장은 {@link RouteStops#targetsAheadOf}가 고르고, 확정 판본은 차량 방향과 다른 방향의 정류장을 뺀다.
 * 확정 판본에서 스냅샷 기준 관측이 출퇴근 시간 밖이면 시간 확장 모델로 예측하고, 모델이 시간 확장을 지원하지 않으면
 * 그 차량은 예측하지 않는다.
 * 그 밖에는 출퇴근 모델에 {@link AlightingAwareFeatureSnapshot#forTarget} 입력을 넣는다.
 *
 * <p>한 배치(같은 모델·통계·정류장)에 하나를 만들어 쓴다. 상태가 없어 스레드 안전하지만, 스냅샷은 만든 스레드에서만 쓴다.
 */
public final class AlightingAwarePredictionAssembler {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final AlightingAwareSeatForecastModel model;
    private final AlightingAwareRouteStatistics statistics;
    private final RouteStops stops;

    public AlightingAwarePredictionAssembler(
        AlightingAwareSeatForecastModel model,
        AlightingAwareRouteStatistics statistics,
        RouteStops stops
    ) {
        this.model = Objects.requireNonNull(model);
        this.statistics = Objects.requireNonNull(statistics);
        this.stops = Objects.requireNonNull(stops);
    }

    /**
     * 차량 하나의 목표 정류장별 예측을 정류장 순서대로 만든다.
     *
     * @param snapshot 예측할 차량의 입력
     * @return 모델이 이 차량을 예측하지 않으면(시간 확장 모델 미지원) 빈 값. 목표 정류장이 없으면 목표가 빈 예측
     */
    public Optional<VehiclePrediction> predict(
        AlightingAwareFeatureSnapshot snapshot
    ) {
        final boolean extended = statistics.confirmedProfile() && outsideRush(snapshot);
        if (extended && !model.supportsExtendedHours()) {
            return Optional.empty();
        }

        List<TargetPrediction> targets = stops.targetsAheadOf(snapshot.observation())
            .stream()
            .filter(target -> !isOppositeDirection(snapshot, target))
            .map(target -> new TargetPrediction(
                target,
                predict(snapshot, target, extended)
            ))
            .toList();
        return Optional.of(new VehiclePrediction(snapshot, extended, targets));
    }

    private boolean isOppositeDirection(AlightingAwareFeatureSnapshot snapshot, VehicleStopTarget target) {
        return statistics.confirmedProfile() && statistics.stop(target.stopOrder()).down() != snapshot.source().down();
    }

    private boolean outsideRush(AlightingAwareFeatureSnapshot snapshot) {
        ZonedDateTime sourceTime = snapshot.source().receivedAt().atZone(SEOUL);
        return !statistics.timeCell(sourceTime).isRush();
    }

    private SeatForecastResult predict(
        AlightingAwareFeatureSnapshot snapshot,
        VehicleStopTarget target,
        final boolean extended
    ) {
        if (extended) {
            ZonedDateTime local = snapshot.source().receivedAt().atZone(SEOUL);
            final boolean offDay = "off".equals(statistics.dayType(local.toLocalDate()));
            return model.predictExtended(
                statistics.route(),
                ExtendedAlightingPredictor.inputs(snapshot, target, offDay)
            );
        }
        return model.predict(snapshot.forTarget(target.stopOrder()));
    }

    /**
     * 차량 하나의 예측이다.
     *
     * @param snapshot 예측에 쓴 입력
     * @param extended 시간 확장 모델로 예측했으면 참
     * @param targets 목표 정류장별 예측. 정류장 순서다
     */
    public record VehiclePrediction(
        AlightingAwareFeatureSnapshot snapshot,
        boolean extended,
        List<TargetPrediction> targets
    ) {

        public VehiclePrediction {
            targets = List.copyOf(targets);
        }
    }

    /**
     * 목표 정류장 하나의 예측이다.
     *
     * @param target 목표 정류장과 기준 관측
     * @param result 모델 출력
     */
    public record TargetPrediction(VehicleStopTarget target, SeatForecastResult result) {

    }
}
