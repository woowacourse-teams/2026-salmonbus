package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.application.les.LesFeatureQuery;
import com.gustler.backend.forecasting.application.les.LesFeatureUnavailableException;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.les.LesFeatureVector;
import com.gustler.backend.forecasting.domain.model.les.LesForecastModel;
import com.gustler.backend.forecasting.domain.model.les.LesRouteStatistics;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LesForecastPublicationTest {
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final PendingForecastBatch batch = new PendingForecastBatch(10, 1, 1, now);
    private final RouteStops stops = new RouteStops(1, "204000057", List.of(new RouteStop(1, 2, "stop-2", true)));
    private final VehicleTrajectoryQuery legacyTrajectories = mock(VehicleTrajectoryQuery.class);
    private final StopDemandStatisticsRepository legacyStatistics = mock(StopDemandStatisticsRepository.class);
    private final SameDayFullOutcomesService legacyOutcomes = mock(SameDayFullOutcomesService.class);
    private final ForecastBatchCommitter committer = mock(ForecastBatchCommitter.class);
    private final RouteDataQualityAccess quality = mock(RouteDataQualityAccess.class);
    private final LesFeatureQuery query = mock(LesFeatureQuery.class);
    private final LesForecastModel model = mock(LesForecastModel.class);
    private final LesRouteStatistics statistics = mock(LesRouteStatistics.class);
    private final ForecastBatchWriter writer = new ForecastBatchWriter(legacyTrajectories, committer,
        legacyOutcomes, legacyStatistics, Clock.fixed(now, ZoneOffset.UTC), quality);

    private RuntimeSnapshot runtime() {
        when(quality.current(1)).thenReturn(1L);
        when(model.statistics(stops)).thenReturn(statistics);
        when(model.bundleDigest()).thenReturn("b".repeat(64));
        when(statistics.route()).thenReturn("3330");
        when(statistics.dayType(any())).thenReturn("work");
        writer.setLesFeatures(query);
        return new RuntimeSnapshot(new ActiveModelDeployment(7, "les-online-v1", "les-test", "b".repeat(64)),
            new SupportedForecastScope(List.of("3330")), model, now.minusSeconds(86400));
    }

    @Test
    void 기존_통계와_보정_없이_독립_출력과_근거를_발행한다() {
        RuntimeSnapshot runtime = runtime();
        LesFeatureSnapshot snapshot = mock(LesFeatureSnapshot.class);
        LesFeatureVector vector = new LesFeatureVector(new double[88], new double[80]);
        when(query.read(10, stops, statistics)).thenReturn(Map.of(100L, snapshot));
        when(snapshot.observation()).thenReturn(new ObservedVehicle("bus", 1, 1, now, 12, null));
        when(snapshot.vehicleObservationId()).thenReturn(100L);
        when(snapshot.forTarget(2)).thenReturn(vector);
        when(model.predict(vector)).thenReturn(SeatForecastResult.les(3.7, 0.85));
        when(model.evidence("3330", 2)).thenReturn("{\"route\":\"3330\",\"alighting\":{\"stops\":[]}}");
        when(committer.commit(eq(batch), any(), anyList())).thenReturn(1);

        assertThat(writer.writeForecastsOf(batch, stops, runtime)).isEqualTo(1);
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commit(eq(batch), published.capture(), eq(List.of()));
        SeatForecast row = published.getValue().predictions().getFirst();
        assertThat(row.expectedSeats()).isEqualTo(3.7);
        assertThat(row.seatFullChance()).isEqualTo(0.85);
        assertThat(row.seatFullChanceRaw()).isEqualTo(0.85);
        assertThat(row.demandStatisticsRevision()).isZero();
        assertThat(row.inferenceBundleHash()).isEqualTo("b".repeat(64));
        assertThat(row.featureSnapshot()).contains("seat88", "probability80", "asOf", "alighting");
        verifyNoInteractions(legacyStatistics, legacyOutcomes, legacyTrajectories);
    }

    @Test
    void 이력이_부족하면_빈_발행이나_기존_모델로_대체하지_않는다() {
        RuntimeSnapshot runtime = runtime();
        when(query.read(10, stops, statistics))
            .thenThrow(new LesFeatureUnavailableException("LES_HISTORY_ORIGIN_UNVERIFIED"));
        assertThatThrownBy(() -> writer.writeForecastsOf(batch, stops, runtime))
            .hasMessage("LES_HISTORY_ORIGIN_UNVERIFIED");
        verifyNoInteractions(committer, legacyStatistics, legacyOutcomes, legacyTrajectories);
    }

    @Test
    void 미지원_시간은_입력_조회_없이_빈_발행으로_확정한다() {
        RuntimeSnapshot runtime = runtime();
        PendingForecastBatch outside = new PendingForecastBatch(11, 1, 1, Instant.parse("2026-11-04T03:00:00Z"));
        writer.writeForecastsOf(outside, stops, runtime);
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commit(eq(outside), published.capture(), eq(List.of()));
        assertThat(published.getValue().predictions()).isEmpty();
        verifyNoInteractions(query, legacyStatistics, legacyOutcomes, legacyTrajectories);
    }
}
