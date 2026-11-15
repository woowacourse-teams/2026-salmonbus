package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.application.RouteWorkAccess;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureQuery;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareFeatureUnavailableException;
import com.gustler.backend.forecasting.application.alighting.AlightingAwareInputConfirmation;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureSnapshot;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareFeatureVector;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareObservation;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareRouteStatistics;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingAwareSeatForecastModel;
import com.gustler.backend.forecasting.domain.model.alighting.AlightingTimeCell;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ForecastBatchWriterLegacyListenerTest {
    private final Instant now = Instant.parse("2026-11-03T23:30:00Z");
    private final PendingForecastBatch batch = new PendingForecastBatch(10, 1, 1, now);
    private final RouteStops stops = new RouteStops(1, "204000057", List.of(new RouteStop(1, 2, "stop-2", true)));
    private final ForecastBatchCommitter committer = mock(ForecastBatchCommitter.class);
    private final RouteWorkAccess quality = mock(RouteWorkAccess.class);
    private final AlightingAwareFeatureQuery query = mock(AlightingAwareFeatureQuery.class);
    private final AlightingAwareInputConfirmation confirmation = mock(AlightingAwareInputConfirmation.class);
    private final AlightingAwareSeatForecastModel model = mock(AlightingAwareSeatForecastModel.class);
    private final AlightingAwareRouteStatistics statistics = mock(AlightingAwareRouteStatistics.class);
    private final ForecastBatchWriter writer =
        new ForecastBatchWriter(committer, Clock.fixed(now, ZoneOffset.UTC), quality);
    private final List<LegacyForecastResult> received = new ArrayList<>();

    private RuntimeSnapshot runtime(final boolean confirmed) {
        when(quality.current(1)).thenReturn(1L);
        when(model.statistics(stops)).thenReturn(statistics);
        when(model.bundleDigest()).thenReturn("b".repeat(64));
        when(statistics.route()).thenReturn("3330");
        when(statistics.confirmedProfile()).thenReturn(confirmed);
        when(statistics.stop(2)).thenReturn(new AlightingAwareRouteStatistics.Stop(2, "stop-2", "정류장", false, true));
        when(statistics.profile()).thenReturn(new AlightingAwareRouteStatistics.Profile(
            "alighting-original-v1", "alighting-identity-safe-v1", "observed-route-day-max-through-closure-v1"));
        when(statistics.timeCell(any())).thenAnswer(call -> AlightingTimeCell.of(call.getArgument(0), false));
        writer.setCompactSettlementWrite(true);
        writer.setAlightingAwareFeatures(query);
        writer.setConfirmedInput(confirmation);
        writer.setLegacyForecastListener(received::add);
        return new RuntimeSnapshot(
            new ActiveModelDeployment(7, "alighting-online-v1", "alighting-test", "b".repeat(64)),
            new SupportedForecastScope(List.of("3330")), model, now.minusSeconds(86400));
    }

    private AlightingAwareFeatureSnapshot snapshot() {
        AlightingAwareFeatureSnapshot snapshot = mock(AlightingAwareFeatureSnapshot.class);
        AlightingAwareFeatureVector vector = new AlightingAwareFeatureVector(new double[88], new double[80]);
        when(snapshot.observation()).thenReturn(new ObservedVehicle("bus", 1, 1, now, 12, null));
        when(snapshot.source()).thenReturn(new AlightingAwareObservation(100, 10, 0, now.plusSeconds(1), "bus", 1, 1, 1,
            12, 0, 1, now, false));
        when(snapshot.vehicleObservationId()).thenReturn(100L);
        when(snapshot.capacity()).thenReturn(44);
        when(snapshot.forTarget(2)).thenReturn(vector);
        when(model.predict(vector)).thenReturn(SeatForecastResult.alighting(3.7, 0.85));
        when(model.settlementEvidence("3330")).thenReturn(Map.of(2, "{\"alighting\":{\"stops\":[]}}"));
        return snapshot;
    }

    @Test
    void 발행을_커밋한_뒤_커밋한_예측과_착수_시각을_넘긴다() {
        // given
        RuntimeSnapshot runtime = runtime(true);
        when(confirmation.confirm(10, now)).thenReturn(true);
        AlightingAwareFeatureSnapshot snapshot = snapshot();
        when(query.read(10, stops, statistics)).thenReturn(Map.of(100L, snapshot));
        when(committer.commitConfirmed(eq(batch), any())).thenReturn(1);

        // when
        final int saved = writer.writeForecastsOf(batch, stops, runtime);

        // then
        ArgumentCaptor<ForecastPublication> published = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(committer).commitConfirmed(eq(batch), published.capture());
        assertSoftly(softly -> {
            softly.assertThat(saved).isOne();
            softly.assertThat(received).singleElement().satisfies(result -> {
                assertThat(result.kind()).isEqualTo(LegacyForecastResult.Kind.PUBLISHED);
                assertThat(result.generatedAt()).isEqualTo(now);
                assertThat(result.statistics()).isSameAs(statistics);
                assertThat(result.predictions()).isEqualTo(published.getValue().predictions()).hasSize(1);
            });
        });
    }

    @Test
    void 확정하지_못한_배치와_범위_밖_배치는_빈_발행을_커밋한_뒤_사유를_넘긴다() {
        // given
        RuntimeSnapshot runtime = runtime(true);
        PendingForecastBatch late = new PendingForecastBatch(11, 1, 1, now);
        when(confirmation.confirm(11, now)).thenReturn(false);
        when(confirmation.confirm(10, now)).thenReturn(true);
        when(query.read(10, stops, statistics))
            .thenThrow(new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_OUTSIDE_VALIDATED_RUSH_SCOPE"));

        // when
        writer.writeForecastsOf(late, stops, runtime);
        writer.writeForecastsOf(batch, stops, runtime);

        // then
        verify(committer).commitConfirmed(eq(late), any());
        verify(committer).commitConfirmed(eq(batch), any());
        assertSoftly(softly -> {
            softly.assertThat(received).extracting(LegacyForecastResult::kind)
                .containsExactly(LegacyForecastResult.Kind.NOT_CONFIRMED, LegacyForecastResult.Kind.OUT_OF_SCOPE);
            softly.assertThat(received).allSatisfy(result -> assertThat(result.predictions()).isEmpty());
        });
    }

    @Test
    void 입력이_부족해_발행하지_않으면_사유를_넘기고_같은_예외를_던진다() {
        // given
        RuntimeSnapshot runtime = runtime(true);
        when(confirmation.confirm(10, now)).thenReturn(true);
        when(query.read(10, stops, statistics))
            .thenThrow(new AlightingAwareFeatureUnavailableException("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED"));

        // when
        Throwable thrown = catchThrowable(() -> writer.writeForecastsOf(batch, stops, runtime));

        // then
        assertSoftly(softly -> {
            softly.assertThat(thrown).hasMessage("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED");
            softly.assertThat(received).singleElement().satisfies(result -> {
                assertThat(result.kind()).isEqualTo(LegacyForecastResult.Kind.FAILED);
                assertThat(result.reason()).isEqualTo("ALIGHTING_AWARE_HISTORY_ORIGIN_UNVERIFIED");
            });
        });
    }

    @Test
    void 받는_쪽이_예외를_던져도_발행_결과와_반환값이_그대로다() {
        // given
        RuntimeSnapshot runtime = runtime(true);
        writer.setLegacyForecastListener(result -> {
            throw new IllegalStateException("줄이 망가졌다");
        });
        when(confirmation.confirm(10, now)).thenReturn(true);
        AlightingAwareFeatureSnapshot snapshot = snapshot();
        when(query.read(10, stops, statistics)).thenReturn(Map.of(100L, snapshot));
        when(committer.commitConfirmed(eq(batch), any())).thenReturn(1);

        // when
        final int saved = writer.writeForecastsOf(batch, stops, runtime);

        // then
        assertThat(saved).isOne();
        verify(committer).commitConfirmed(eq(batch), any());
    }

    @Test
    void 확정_판본이_아닌_통계의_배치는_넘기지_않는다() {
        // given
        RuntimeSnapshot runtime = runtime(false);
        AlightingAwareFeatureSnapshot snapshot = snapshot();
        when(query.read(10, stops, statistics)).thenReturn(Map.of(100L, snapshot));
        when(committer.commit(eq(batch), any())).thenReturn(1);

        // when
        writer.writeForecastsOf(batch, stops, runtime);

        // then
        assertThat(received).isEmpty();
    }
}
