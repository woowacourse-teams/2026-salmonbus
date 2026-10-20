package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.deployment.ActiveForecastRuntimeResolver;
import com.gustler.backend.forecasting.application.deployment.LoadedModelRegistry;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ModelDeploymentRepository;
import com.gustler.backend.forecasting.domain.model.*;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.TimeSlot;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConditionalBundleTest {
    @TempDir
    Path directory;

    @Test
    void 통계_사용_계약은_별도_버전과_정확한_통계_정책으로만_적재한다() {
        var loaded = DummyBundle.conditional()
            .put("featureContractVersion", ForecastFeatureContract.STATISTICS_VERSION)
            .put("cellStatisticsPolicy", ForecastFeatureContract.STATISTICS_POLICY).loadAt(directory);
        var release = loaded.release();
        assertThat(release.features()).isEqualTo(ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS);
        var actual =
            new SeatDistributionForecastModel(release.predictor(), release.features(), release.routeReference())
                .predict(input(null));
        var direct = loaded.predictor().predict(new SeatDistributionInput(
            ForecastFeatureContract.STOP_DIRECTION_TIME_STATISTICS.vectorOf(input(null)), "3330", 1, 20, 44, null));
        assertThat(actual).isEqualTo(direct);
    }

    @Test
    void 통계_사용_모델을_통계_0_정책으로_표시하면_거절한다() {
        assertThatThrownBy(() -> DummyBundle.conditional()
            .put("featureContractVersion", ForecastFeatureContract.STATISTICS_VERSION).loadAt(directory))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void 기존_조건_모델의_통계_정책만_바꾸면_거절한다() {
        assertThatThrownBy(() -> DummyBundle.conditional()
            .put("cellStatisticsPolicy", ForecastFeatureContract.STATISTICS_POLICY).loadAt(directory))
            .isInstanceOf(RuntimeException.class);
    }

    @Test
    void 새_파일을_검사하고_50열_모델로_실제_좌석분포를_계산한다() {
        LoadedBundle loaded = DummyBundle.conditional().loadAt(directory);
        var release = loaded.release();
        assertThat(release.features()).isEqualTo(ForecastFeatureContract.STOP_DIRECTION_TIME);
        SeatForecastInput input = input(null);
        SeatForecastResult actual =
            new SeatDistributionForecastModel(release.predictor(), release.features(),
                release.routeReference()).predict(input);
        SeatForecastResult direct = loaded.predictor().predict(new SeatDistributionInput(
            ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input), "3330", 1, 20, 44, null));
        assertThat(actual).isEqualTo(direct);
        assertThat(new FileModelBundleInspector().inspect(directory.toString()).featureContractVersion())
            .isEqualTo(ForecastFeatureContract.CONDITIONAL_VERSION);
    }

    @Test
    void 새_입력에서도_당일_보정_집계를_그대로_예측기에_전달한다() {
        LoadedBundle loaded = DummyBundle.conditional().loadAt(directory);
        var release = loaded.release();
        SeatForecastInput input = input(new SameDayFullOutcomes(100, 80, 0.2));
        SeatForecastResult actual =
            new SeatDistributionForecastModel(release.predictor(), release.features(),
                release.routeReference()).predict(input);
        SeatForecastResult direct = loaded.predictor().predict(new SeatDistributionInput(
            ForecastFeatureContract.STOP_DIRECTION_TIME.vectorOf(input), "3330", 1, 20, 44,
            input.sameDayFullOutcomes()));
        assertThat(actual).isEqualTo(direct);
        assertThat(actual.distribution()).isNotEqualTo(new SeatDistributionForecastModel(
            release.predictor(), release.features(), release.routeReference()).predict(input(null)).distribution());
    }

    @Test
    void 활성_배포를_바꾸면_입력_규칙도_같이_바뀌고_기존_번들로_복구된다() throws Exception {
        Path legacyPath = Files.createDirectory(directory.resolve("legacy"));
        Path conditionalPath = Files.createDirectory(directory.resolve("conditional"));
        var legacy = DummyBundle.valid().loadAt(legacyPath).release();
        var conditional = DummyBundle.conditional().loadAt(conditionalPath).release();
        LoadedModelRegistry registry = new LoadedModelRegistry();
        registry.add(legacy);
        registry.add(conditional);
        ModelDeploymentRepository deployments = mock(ModelDeploymentRepository.class);
        var selected = new java.util.concurrent.atomic.AtomicReference<>(new ActiveModelDeployment(
            1, legacy.featureContractVersion(), legacy.releaseId(), legacy.bundleDigest()));
        when(deployments.findActive()).thenAnswer(ignored -> java.util.Optional.of(selected.get()));
        var resolver = new ActiveForecastRuntimeResolver(deployments, registry);
        var before = resolver.resolveActive().orElseThrow();
        var oldResult = before.model().predict(input(null));
        selected.set(
            new ActiveModelDeployment(2, conditional.featureContractVersion(), conditional.releaseId(),
                conditional.bundleDigest()));
        var current = resolver.resolveActive().orElseThrow();
        assertThat(current.model().predict(input(null))).isEqualTo(new SeatDistributionForecastModel(
            conditional.predictor(), conditional.features(), conditional.routeReference()).predict(input(null)));
        assertThat(before.model().predict(input(null))).isEqualTo(oldResult);
        selected.set(before.deployment());
        assertThat(resolver.resolveActive().orElseThrow().model().predict(input(null))).isEqualTo(oldResult);
    }

    @Test
    void 새로운_계약을_예전_파일_형식이라고_적으면_거절한다() {
        assertThatThrownBy(() -> DummyBundle.conditional()
                .put("bundleSchemaVersion", "a18-live-bundle-v1")
                .loadAt(directory))
            .isInstanceOf(BundleRejectedException.class);
    }

    @Test
    void 모르는_새_계약이나_다른_시간_규칙을_거절한다() {
        assertThatThrownBy(() -> DummyBundle.conditional()
                .put("featureContractVersion", "unknown-v2")
                .loadAt(directory))
            .isInstanceOf(BundleRejectedException.class)
            .hasMessageContaining("FEATURE_CONTRACT_VERSION");
        assertThatThrownBy(
            () -> DummyBundle.conditional().put("timeSlotSource", "UTC").loadAt(directory))
            .isInstanceOf(BundleRejectedException.class).hasMessageContaining("FEATURE_CONTRACT_VERSION");
    }

    @Test
    void 새_계약에_기존_31열_계수를_넣으면_거절한다() {
        assertThatThrownBy(
            () -> DummyBundle.valid().put("bundleSchemaVersion", "a18-live-bundle-v2")
                .put("featureContractVersion", ForecastFeatureContract.CONDITIONAL_VERSION).loadAt(directory))
            .isInstanceOf(BundleRejectedException.class).hasMessageContaining("FEATURE_NAMES");
    }

    @Test
    void 정류장_기준_파일이_없거나_바뀌면_적재를_거절한다() throws Exception {
        DummyBundle.conditional().writeTo(directory);
        Path reference = directory.resolve("route-reference.json");
        Files.writeString(reference, "{}");
        assertThatThrownBy(() -> LoadedBundle.from(BundleFiles.under(directory)))
            .isInstanceOf(BundleRejectedException.class).hasMessageContaining("ROUTE_REFERENCE");
        Files.delete(reference);
        assertThatThrownBy(() -> LoadedBundle.from(BundleFiles.under(directory)))
            .isInstanceOf(BundleRejectedException.class).hasMessageContaining("ROUTE_REFERENCE");
    }

    @Test
    void 정류장_기준의_symlink를_거절한다() throws Exception {
        DummyBundle.conditional().writeTo(directory);
        Path reference = directory.resolve("route-reference.json");
        Path real = directory.resolve("real-reference.json");
        Files.move(reference, real);
        Files.createSymbolicLink(reference, real);
        assertThatThrownBy(() -> LoadedBundle.from(BundleFiles.under(directory)))
            .isInstanceOf(BundleRejectedException.class).hasMessageContaining("ROUTE_REFERENCE");
    }

    private static SeatForecastInput input(SameDayFullOutcomes sameDay) {
        ObservedVehicle vehicle = new ObservedVehicle("bus", 1L, 1, Instant.parse("2026-10-01T08:00:00+09:00"), 20, 1);
        List<RouteStop> stops = List.of(new RouteStop(1, 1, "same", true, RouteDirection.UP),
            new RouteStop(1, 2, "same", true, RouteDirection.DOWN),
            new RouteStop(1, 3, "last", true, RouteDirection.DOWN));
        VehicleTrajectory trajectory = new VehicleTrajectory(10L, vehicle, new ObservedSeats.Known(20),
            new SeatSlope.Known(-2), new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new FullSeatStreak.SeenToEnd(0), 44);
        return new SeatForecastInput(new VehicleStopTarget(vehicle, stops.get(1)), trajectory,
            new StopDemandStatistics(1, TimeSlot.MORNING, 0, List.of()), new RouteStops(1, "204000057", stops),
            TimeSlot.MORNING, sameDay);
    }
}
