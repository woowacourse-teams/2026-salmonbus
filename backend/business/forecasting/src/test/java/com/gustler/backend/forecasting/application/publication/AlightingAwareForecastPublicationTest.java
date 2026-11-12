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

import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareInputConfirmation;
import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatchQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AlightingAwareForecastPublicationTest {
    private final Instant now = Instant.parse("2026-11-04T00:00:00Z");
    private final PendingForecastBatch batch = new PendingForecastBatch(10, 1, 1, now);
    private final RouteStops stops = new RouteStops(1, "204000057", List.of(new RouteStop(1, 2, "stop-2", true)));
    private final ForecastBatchCommitter committer = mock(ForecastBatchCommitter.class);
    private final RouteWorkAccess quality = mock(RouteWorkAccess.class);
    private final AlightingAwareFeatureQuery query = mock(AlightingAwareFeatureQuery.class);
    private final AlightingAwareSeatForecastModel model = mock(AlightingAwareSeatForecastModel.class);
    private final AlightingAwareRouteStatistics statistics = mock(AlightingAwareRouteStatistics.class);
    private final ForecastBatchWriter writer = new ForecastBatchWriter( committer, Clock.fixed(now, ZoneOffset.UTC), quality);

    private RuntimeSnapshot runtime() {
        when(quality.current(1)).thenReturn(1L);
        when(model.statistics(stops)).thenReturn(statistics);
        when(model.bundleDigest()).thenReturn("b".repeat(64));
        when(statistics.route()).thenReturn("3330");
        when(statistics.profile()).thenReturn(new AlightingAwareRouteStatistics.Profile(
            "alighting-original-v1", "alighting-identity-safe-v1", "observed-route-day-max-through-closure-v1"));
        when(statistics.timeCell(any())).thenAnswer(call -> AlightingTimeCell.of(call.getArgument(0), false));
        writer.setCompactSettlementWrite(true);
        writer.setAlightingAwareFeatures(query);
        return new RuntimeSnapshot(new ActiveModelDeployment(7, "alighting-online-v1", "alighting-test", "b".repeat(64)),
            new SupportedForecastScope(List.of("3330")), model, now.minusSeconds(86400));
    }

    @Test
    void 기존_통계와_보정_없이_독립_출력과_근거를_발행한다() {
        RuntimeSnapshot runtime = runtime();
        AlightingAwareFeatureSnapshot snapshot = mock(AlightingAwareFeatureSnapshot.class);
        AlightingAwareFeatureVector vector = new AlightingAwareFeatureVector(new double[88], new double[80]);
        when(query.read(10, stops, statistics)).thenReturn(Map.of(100L, snapshot));
        when(snapshot.observation()).thenReturn(new ObservedVehicle("bus", 1, 1, now, 12, null));
        when(snapshot.vehicleObservationId()).thenReturn(100L);
        when(snapshot.capacity()).thenReturn(44);
        when(snapshot.forTarget(2)).thenReturn(vector);
        when(model.predict(vector)).thenReturn(SeatForecastResult.alighting(3.7, 0.85));
        when(model.settlementEvidence("3330")).thenReturn(Map.of(2, "{\"holidays\":[\"2026-12-25\",\"2026-11-20\"],\"alighting\":{\"stops\":[]}}"));
        when(committer.commit(eq(batch), any())).thenReturn(1);

        assertThat(writer.writeForecastsOf(batch, stops, runtime)).isEqualTo(1);
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commit(eq(batch), published.capture());
        SeatForecast row = published.getValue().predictions().getFirst();
        assertThat(row.expectedSeats()).isEqualTo(3.7);
        assertThat(row.seatFullChance()).isEqualTo(0.85);
        assertThat(row.seatFullChanceRaw()).isEqualTo(0.85);
        assertThat(row.demandStatisticsRevision()).isZero();
        assertThat(row.inferenceBundleHash()).isEqualTo("b".repeat(64));
        assertThat(row.featureSnapshot()).isEqualTo("{\"capacity\":44}");
        assertThat(published.getValue().settlementContext()).contains("[\"2026-11-20\",\"2026-12-25\"]");
    }

    @Test
    void 이력이_부족하면_빈_발행이나_기존_모델로_대체하지_않는다() {
        RuntimeSnapshot runtime = runtime();
        when(query.read(10, stops, statistics))
            .thenThrow(new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED"));
        assertThatThrownBy(() -> writer.writeForecastsOf(batch, stops, runtime))
            .hasMessage("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED");
        verifyNoInteractions(committer);
    }

    @Test
    void 확인_시각이_지원_시간_밖이면_빈_발행으로_처리를_끝낸다() {
        // given
        RuntimeSnapshot runtime = runtime();
        when(statistics.confirmedProfile()).thenReturn(true);
        AlightingAwareInputConfirmation confirmation = mock(AlightingAwareInputConfirmation.class);
        writer.setConfirmedInput(confirmation);
        when(confirmation.confirm(10, now)).thenReturn(true);
        when(query.read(10, stops, statistics))
            .thenThrow(new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_OUTSIDE_VALIDATED_RUSH_SCOPE"));

        // when
        writer.writeForecastsOf(batch, stops, runtime);

        // then
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commitConfirmed(eq(batch), published.capture());
        assertThat(published.getValue().predictions()).isEmpty();
    }

    @Test
    void 미지원_시간은_입력_조회_없이_빈_발행으로_확정한다() {
        RuntimeSnapshot runtime = runtime();
        PendingForecastBatch outside = new PendingForecastBatch(11, 1, 1, Instant.parse("2026-11-04T03:00:00Z"));
        writer.writeForecastsOf(outside, stops, runtime);
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commit(eq(outside), published.capture());
        assertThat(published.getValue().predictions()).isEmpty();
        verifyNoInteractions(query);
    }
}
