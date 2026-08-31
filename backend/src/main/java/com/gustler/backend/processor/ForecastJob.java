package com.gustler.backend.processor;

import java.util.List;
import java.util.Optional;
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

    private final VehicleTrajectoryRepository vehicleTrajectoryRepository;
    private final RouteVersionRepository routeVersionRepository;
    private final ModelDeploymentRepository modelDeploymentRepository;
    private final ForecastBatchWriter forecastBatchWriter;
    private final Optional<SeatForecastModel> seatForecastModel;
    private final ForecastProperties properties;

    public ForecastJob(
        VehicleTrajectoryRepository vehicleTrajectoryRepository,
        RouteVersionRepository routeVersionRepository,
        ModelDeploymentRepository modelDeploymentRepository,
        ForecastBatchWriter forecastBatchWriter,
        Optional<SeatForecastModel> seatForecastModel,
        ForecastProperties properties
    ) {
        this.vehicleTrajectoryRepository = vehicleTrajectoryRepository;
        this.routeVersionRepository = routeVersionRepository;
        this.modelDeploymentRepository = modelDeploymentRepository;
        this.forecastBatchWriter = forecastBatchWriter;
        this.seatForecastModel = seatForecastModel;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${forecast.interval}")
    public void writeForecasts() {
        Optional<ActiveModelDeployment> deployment = modelDeploymentRepository.findActive();
        if (seatForecastModel.isEmpty() || deployment.isEmpty()) {
            return;
        }
        for (Long routeVersionId : routeVersionRepository.findActiveVersionIds()) {
            writeForecastsOf(routeVersionId, deployment.get(), seatForecastModel.get());
        }
    }

    private void writeForecastsOf(
        final long routeVersionId,
        ActiveModelDeployment deployment,
        SeatForecastModel model
    ) {
        RouteStops stops = routeVersionRepository.readStops(routeVersionId);
        List<PendingForecastBatch> batches =
            vehicleTrajectoryRepository.findBatchesAwaitingForecast(routeVersionId, properties.batchLimit());
        for (PendingForecastBatch batch : batches) {
            forecastBatchWriter.writeForecastsOf(batch, stops, deployment, model);
        }
    }
}
