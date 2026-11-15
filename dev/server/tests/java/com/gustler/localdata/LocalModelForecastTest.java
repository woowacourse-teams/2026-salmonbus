package com.gustler.localdata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.gustler.backend.forecasting.application.forecast.ForecastBatch;
import com.gustler.backend.forecasting.application.forecast.ForecastObservationRow;
import com.gustler.backend.forecasting.application.forecast.ForecastStage;
import com.gustler.backend.forecasting.application.forecast.ForecastStageResult;
import com.gustler.backend.forecasting.application.forecast.RouteForecastState;
import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.model.RouteDirection;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwarePredictionAssembler;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.infrastructure.bundle.FileModelBundleLoader;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 저장소의 개발용 번들이 예보 단계와 예측 조립을 거쳐 실제로 예측을 내는지 본다. DB와 수집은 쓰지 않는다.
 * 번들은 상수 모델이라 예측값은 언제나 같다. 정확도 검사가 아니다.
 */
class LocalModelForecastTest {
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    /** 월요일 출근 시간이다. 개발용 번들은 평일 출퇴근 시간만 예보한다. */
    private static final Instant RUSH_HOUR = ZonedDateTime.of(2026, 11, 16, 8, 0, 0, 0, SEOUL).toInstant();
    private static final int CAPACITY = 44;
    private static final int SEATS = 30;
    private static final double CONSTANT_SEATS = (1.3 + 10) / 3;

    @TempDir
    Path root;

    @Test
    void 개발용_번들은_출근_시간에_지원하는_여덟_노선의_차량을_예보한다() throws Exception {
        // given
        Path data = Path.of(System.getProperty("dev.data.directory"));
        Path directory = root.resolve("alighting-dev-v1");
        ReferenceBundle.prepare(data.resolve("model-reference"), directory);
        ModelRelease release = new FileModelBundleLoader(true).filesUnder(directory.toString()).load();
        ForecastStage stage = new ForecastStage((source, vehicles, at, batch) -> capacities(vehicles));
        List<RouteDataset.Route> routes = RouteDataset.read(data.resolve("routes.json")).routes();

        // when
        Map<String, List<AlightingAwarePredictionAssembler.TargetPrediction>> targetsByRoute = new LinkedHashMap<>();
        long versionId = 1;
        for (RouteDataset.Route route : routes) {
            targetsByRoute.put(route.displayName(), forecastTargets(release, stage, versionId, route));
            versionId++;
        }

        // then
        assertThat(targetsByRoute).hasSize(8).allSatisfy((route, targets) ->
            assertThat(targets).as(route).isNotEmpty().allSatisfy(target -> {
                assertThat(target.result().expectedSeats()).isCloseTo(CONSTANT_SEATS, within(1e-9));
                assertThat(target.result().fullChance()).isEqualTo(0.5);
            }));
    }

    /** 노선 하나에서 첫 정류장을 막 떠난 차량 한 대를 예보 단계에 넣고, 그 차량의 목표 정류장별 예측을 돌려준다. */
    private static List<AlightingAwarePredictionAssembler.TargetPrediction> forecastTargets(
        ModelRelease release,
        ForecastStage stage,
        final long versionId,
        RouteDataset.Route route
    ) {
        RouteStops stops = routeStops(versionId, route);
        AlightingAwareRouteStatistics statistics = release.model().statistics(stops);
        ForecastStageResult result = stage.advance(RouteForecastState.fromVerifiedOrigin(statistics.route()),
            statistics, "GBIS", departingFirstStop(versionId));
        assertThat(result.status()).as(route.displayName()).isEqualTo(ForecastStageResult.Status.FORECAST);
        assertThat(result.withheld()).as(route.displayName()).isEmpty();
        assertThat(result.snapshots()).as(route.displayName()).hasSize(1);
        return new AlightingAwarePredictionAssembler(release.model(), statistics, stops)
            .predict(result.snapshots().getFirst())
            .orElseThrow()
            .targets();
    }

    private static RouteStops routeStops(final long versionId, RouteDataset.Route route) {
        List<RouteStop> stops = route.stops().stream()
            .map(stop -> new RouteStop(versionId, stop.sequence(), stop.stationId(), stop.boardingAllowed(),
                RouteDirection.valueOf(stop.direction())))
            .toList();
        return new RouteStops(versionId, route.routeId(), stops, route.displayName());
    }

    /** 첫 정류장을 막 떠난 차량 한 대를 담은, 수신 직후 착수한 배치다. */
    private static ForecastBatch departingFirstStop(final long versionId) {
        ForecastObservationRow row = new ForecastObservationRow(versionId, "local-forecast-check", 1, 2, 1, SEATS,
            null, 1);
        return new ForecastBatch(versionId, versionId, RUSH_HOUR, RUSH_HOUR.plusSeconds(1), "LIVE", "SUCCESS_ROWS",
            1, List.of(row));
    }

    private static Map<String, Integer> capacities(List<String> vehicles) {
        return vehicles.stream().collect(Collectors.toMap(vehicle -> vehicle, vehicle -> CAPACITY));
    }
}
