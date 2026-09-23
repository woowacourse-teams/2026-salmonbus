package com.gustler.backend.forecasting.application.publication;

import com.gustler.backend.forecasting.application.evaluation.SameDayFullOutcomesService;
import com.gustler.backend.forecasting.domain.model.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.model.ModelIdentity;
import com.gustler.backend.forecasting.domain.model.ForecastRuntime;
import com.gustler.backend.forecasting.domain.publication.ForecastTimeSlot;
import com.gustler.backend.forecasting.domain.model.SeatDistribution;
import com.gustler.backend.forecasting.domain.model.SeatForecastInput;
import com.gustler.backend.forecasting.domain.model.SeatForecastModel;
import com.gustler.backend.forecasting.domain.model.SeatForecastResult;
import com.gustler.backend.forecasting.domain.model.SeatRangeException;
import com.gustler.backend.forecasting.domain.model.FullSeatStreak;
import com.gustler.backend.forecasting.domain.model.ObservedSeats;
import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.model.PrecedingVehicle;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.RouteVersionRepository;
import com.gustler.backend.forecasting.domain.publication.SeatForecast;
import com.gustler.backend.forecasting.domain.publication.ForecastPublication;
import com.gustler.backend.forecasting.domain.publication.ForecastPublicationRepository;
import com.gustler.backend.forecasting.domain.publication.PublishedForecast;
import com.gustler.backend.observations.api.CollectionInput;
import com.gustler.backend.observations.api.CollectionInputs;
import com.gustler.backend.forecasting.domain.model.SeatSlope;
import com.gustler.backend.forecasting.domain.model.TrajectoryGap;
import com.gustler.backend.forecasting.domain.model.VehicleTrajectory;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryRepository;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatistics;
import com.gustler.backend.forecasting.domain.statistics.StopDemandStatisticsRepository;
import com.gustler.backend.forecasting.application.quality.RouteDataQualityAccess;
import com.gustler.backend.forecasting.api.ForecastPolicy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.forecasting.domain.model.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.model.SeatDistributionInput;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;

class ForecastBatchWriterTest {

    private static final Instant NOW = Instant.parse("2026-09-21T02:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final PendingForecastBatch BATCH = new PendingForecastBatch(100, 1, 1, NOW, 1);
    private static final RouteStops STOPS = new RouteStops(1, "204000057", List.of(
        new RouteStop(1, 4, "stop-4", true), new RouteStop(1, 5, "stop-5", true)));
    private static final StopDemandStatistics STATISTICS = new StopDemandStatistics(
        1, ForecastTimeSlot.of(BATCH, CLOCK), 3, List.of());
    private static final SeatForecastResult RESULT = new SeatForecastResult(
        new SeatDistribution(List.of(0.4, 0.6)), 0.4);

    private final VehicleTrajectoryRepository trajectories = mock(VehicleTrajectoryRepository.class);
    private final ForecastPublicationRepository forecasts = mock(ForecastPublicationRepository.class);
    private final StopDemandStatisticsRepository statistics = mock(StopDemandStatisticsRepository.class);
    private final RouteDataQualityAccess quality =
        mock(RouteDataQualityAccess.class);
    private final SameDayFullOutcomesService outcomes = mock(SameDayFullOutcomesService.class);
    private final CollectionInputs inputs = mock(CollectionInputs.class);
    private final ForecastBatchWriter writer = new ForecastBatchWriter(trajectories, forecasts, outcomes, statistics, CLOCK,
        quality, inputs);
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() {
        when(quality.lock(anyLong())).thenReturn(1L);
        when(inputs.lockForForecast(anyLong())).thenAnswer(invocation -> {
            long batchId = invocation.getArgument(0);
            return new CollectionInput(batchId, batchId == 102 ? 2 : 1, 1, NOW, true, false);
        });
        when(forecasts.save(any())).thenAnswer(invocation -> {
            ForecastPublication publication = invocation.getArgument(0);
            return new PublishedForecast(publication.sourceBatchId(), publication.predictionCount());
        });
        when(statistics.readAsOf(1, STATISTICS.timeSlot(), "feature-v1", NOW)).thenReturn(STATISTICS);
        when(outcomes.outcomesFor(1, NOW)).thenReturn(Map.of());
        logs.start();
        ((Logger) LoggerFactory.getLogger(ForecastBatchWriter.class)).addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        ((Logger) LoggerFactory.getLogger(ForecastBatchWriter.class)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void 예보_생성은_편도_조사_없이_노선_잠금_후_통계를_조회한다() {
        // given
        var snapshot = runtime(input -> RESULT);

        // when
        writer.writeForecastsOf(BATCH, STOPS, snapshot);

        // then
        var order = inOrder(quality, inputs, statistics, forecasts);
        order.verify(quality).lock(BATCH.routeVersionId());
        order.verify(inputs).lockForForecast(BATCH.observationBatchId());
        order.verify(statistics).readAsOf(1, STATISTICS.timeSlot(), "feature-v1", NOW);
    }

    @Test
    void 모델이_계산한_만석_확률과_예상_잔여석을_대상_정류장별로_저장소에_전달한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(10, 12, 44), vehicle(11, 20, 44)));

        // when
        writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation));

        // then
        assertThat(saved()).containsExactlyInAnyOrder(
            expected(10, 4), expected(10, 5), expected(11, 4), expected(11, 5));
        verify(inputs).confirmInput(100, 1, NOW);
        assertThat(logs.list).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void 같은_관측_묶음에서_과거_최대값이_모델_범위를_초과한_차량만_제외한다(boolean badFirst) {
        // given
        VehicleTrajectory good = vehicle(10, 12, 44);
        VehicleTrajectory bad = vehicle(11, 43, 82);
        when(trajectories.readTrajectories(100)).thenReturn(badFirst ? List.of(bad, good) : List.of(good, bad));

        // when
        writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation));

        // then
        assertThat(saved()).containsExactlyInAnyOrder(expected(10, 4), expected(10, 5));
        verify(inputs).confirmInput(100, 1, NOW);
        assertThat(logs.list).singleElement().satisfies(event -> assertThat(event.getFormattedMessage())
            .contains("event=forecast_vehicle_skipped", "batchId=100", "vehicleObservationId=11",
                "modelDeploymentId=7", "currentSeats=43", "capacity=82", "field=capacity",
                "value=82", "minimum=1", "maximum=70")
            .doesNotContain("vehicle-11"));
    }

    @Test
    void 차량의_일부_정류장_계산이_실패하면_그_차량의_예보를_모두_제외한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(11, 43, 44), vehicle(10, 12, 44)));
        SeatForecastModel model = input -> {
            if (input.trajectory().vehicleObservationId() == 11 && input.target().stopOrder() == 5) {
                throw new SeatRangeException("capacity", 82, 1, 70);
            }
            return RESULT;
        };

        // when
        writer.writeForecastsOf(BATCH, STOPS, runtime(model));

        // then
        assertThat(saved()).containsExactlyInAnyOrder(expected(10, 4), expected(10, 5));
        assertThat(logs.list).hasSize(1);
    }

    @Test
    void 모든_차량이_좌석_범위를_초과하면_예보_없이_처리_완료_시각을_기록한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(11, 43, 82), vehicle(12, 82, 82)));

        // when
        writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation));

        // then
        assertThat(saved()).isEmpty();
        verify(inputs).confirmInput(100, 1, NOW);
        assertThat(logs.list).hasSize(2);
    }

    @Test
    void 차량이_없는_관측_묶음도_예보_처리_완료_시각을_기록한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of());

        // when
        writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation));

        // then
        assertThat(saved()).isEmpty();
        verify(inputs).confirmInput(100, 1, NOW);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 좌석_범위와_무관한_계산_오류가_나면_예보와_처리_완료_시각을_저장하지_않는다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(10, 12, 44), vehicle(11, 12, 44)));
        IllegalArgumentException failure = new IllegalArgumentException("feature mismatch");
        SeatForecastModel model = input -> {
            if (input.trajectory().vehicleObservationId() == 11) {
                throw failure;
            }
            return RESULT;
        };

        // when
        Throwable actual = catchThrowable(() -> writer.writeForecastsOf(BATCH, STOPS, runtime(model)));

        // then
        assertThat(actual).isSameAs(failure);

        verify(forecasts, never()).save(any());
        verify(inputs, never()).confirmInput(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 예보_저장에_실패하면_트랜잭션이_롤백되도록_오류를_전파한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(10, 12, 44)));
        IllegalStateException failure = new IllegalStateException("database unavailable");
        doThrow(failure).when(forecasts).save(any());

        // when
        Throwable actual = catchThrowable(() ->
            writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation)));

        // then
        assertThat(actual).isSameAs(failure);

        verify(inputs).confirmInput(100, 1, NOW);
        assertThat(logs.list).isEmpty();
    }

    @Test
    void 로그_출력_처리에서_예외가_나도_정상_차량의_예보를_저장한다() {
        // given
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(11, 43, 82), vehicle(10, 12, 44)));
        Logger logger = (Logger) LoggerFactory.getLogger(ForecastBatchWriter.class);
        AppenderBase<ILoggingEvent> brokenAppender = new AppenderBase<>() {
            @Override
            protected void append(ILoggingEvent event) {
                throw new IllegalStateException("log destination unavailable");
            }
        };
        brokenAppender.setContext(logger.getLoggerContext());
        brokenAppender.start();
        logger.addAppender(brokenAppender);
        try {
            // when
            writer.writeForecastsOf(BATCH, STOPS, runtime(this::predictWithSeatValidation));

            // then
            assertThat(saved()).containsExactlyInAnyOrder(expected(10, 4), expected(10, 5));
            verify(inputs).confirmInput(100, 1, NOW);
        } finally {
            logger.detachAppender(brokenAppender);
            brokenAppender.stop();
        }
    }

    @Test
    void 좌석_범위를_초과한_차량이_있어도_다음_관측_묶음과_다른_노선을_처리한다() {
        // given
        RouteVersionRepository routes = mock(RouteVersionRepository.class);
        ForecastRuntime runtimeProvider = mock(ForecastRuntime.class);
        when(runtimeProvider.resolveActive()).thenReturn(Optional.of(runtime(this::predictWithSeatValidation)));
        when(routes.findActiveVersionIds()).thenReturn(List.of(1L, 2L));
        when(routes.readStops(1)).thenReturn(STOPS);
        when(routes.readStops(2)).thenReturn(new RouteStops(2, "234000050", List.of()));
        when(trajectories.findBatchesAwaitingForecast(1, NOW.minusSeconds(300), 20))
            .thenReturn(List.of(BATCH, new PendingForecastBatch(101, 1, 1, NOW, 1)));
        when(trajectories.findBatchesAwaitingForecast(2, NOW.minusSeconds(300), 20))
            .thenReturn(List.of(new PendingForecastBatch(102, 2, 2, NOW, 1)));
        when(trajectories.readTrajectories(100)).thenReturn(List.of(vehicle(11, 43, 82)));
        when(trajectories.readTrajectories(101)).thenReturn(List.of(vehicle(10, 12, 44)));
        when(trajectories.readTrajectories(102)).thenReturn(List.of());
        when(statistics.readAsOf(2, STATISTICS.timeSlot(), "feature-v1", NOW))
            .thenReturn(new StopDemandStatistics(2, STATISTICS.timeSlot(), 3, List.of()));
        ForecastPolicy properties = new ForecastPolicy(Duration.ofMinutes(5), 20, 3000, 400);
        PublishPendingForecastsService job = new PublishPendingForecastsService(trajectories, routes, runtimeProvider, writer, properties, CLOCK);

        // when
        job.writeForecasts();

        // then
        ArgumentCaptor<ForecastPublication> publications = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(forecasts, times(3)).save(publications.capture());
        assertThat(publications.getAllValues()).extracting(ForecastPublication::predictionCount)
            .containsExactly(0, 2, 0);
        verify(inputs).confirmInput(100, 1, NOW);
        verify(inputs).confirmInput(101, 1, NOW);
        verify(inputs).confirmInput(102, 1, NOW);
    }

    @Test
    void 이미_발행한_배치는_재계산하지_않고_기존_발행을_반환한다() {
        PublishedForecast published = new PublishedForecast(70, 2);
        when(forecasts.findBySourceBatchId(100)).thenReturn(Optional.of(published));

        var actual = writer.writeForecastsOf(BATCH, STOPS, runtime(input -> {
            throw new AssertionError("이미 발행한 예보를 다시 계산하면 안 된다");
        }));

        assertThat(actual).contains(published);
        verify(trajectories, never()).readTrajectories(anyLong());
        verify(forecasts, never()).save(any());
        verify(inputs, never()).confirmInput(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void 조회_이후_수집_시도가_바뀌었으면_예보를_발행하지_않는다() {
        when(inputs.lockForForecast(100)).thenReturn(new CollectionInput(100, 1, 2, NOW, true, false));

        var actual = writer.writeForecastsOf(BATCH, STOPS, runtime(input -> RESULT));

        assertThat(actual).isEmpty();
        verify(statistics, never()).readAsOf(anyLong(), any(), any(), any());
        verify(trajectories, never()).readTrajectories(anyLong());
        verify(forecasts, never()).save(any());
        verify(inputs, never()).confirmInput(anyLong(), org.mockito.ArgumentMatchers.anyInt(), any());
    }

    @Test
    void 평가_근거로_이미_확정한_수집_배치도_아직_발행하지_않았다면_발행한다() {
        when(inputs.lockForForecast(100)).thenReturn(new CollectionInput(100, 1, 1, NOW, true, true));

        writer.writeForecastsOf(BATCH, STOPS, runtime(input -> RESULT));

        ForecastPublication publication = savedPublication();
        assertThat(publication.predictionCount()).isZero();
        assertThat(publication.modelDeploymentId()).isEqualTo(7);
        assertThat(publication.demandStatisticsRevision()).isEqualTo(3);
        assertThat(publication.qualityRevision()).isEqualTo(1);
        verify(inputs).confirmInput(100, 1, NOW);
    }

    private SeatForecastResult predictWithSeatValidation(SeatForecastInput input) {
        // 실제 모델 입력 검증을 사용한다. 계수 계산 연결은 모델 어댑터 테스트가 검증한다.
        new SeatDistributionInput(new double[] {1}, "3330", input.target().distance().stopCount(),
            input.target().remainingSeats(), input.maximumSeatsEverObserved(), null);
        return RESULT;
    }

    private RuntimeSnapshot runtime(SeatForecastModel model) {
        return new RuntimeSnapshot(new ActiveModelDeployment(7, new ModelIdentity("release", "seat", "v1", "0".repeat(64),
            "seat-v1", "feature-v1", "0".repeat(64), NOW.minusSeconds(60))),
            null, model, NOW.minusSeconds(60));
    }

    private VehicleTrajectory vehicle(long id, int seats, int maximumSeats) {
        ObservedVehicle observation = new ObservedVehicle("vehicle-" + id, 1, 3, NOW, seats, null);
        return new VehicleTrajectory(id, observation, new ObservedSeats.Known(seats), new SeatSlope.Known(0),
            new PrecedingVehicle.Unknown(TrajectoryGap.NO_VEHICLE_AHEAD), new FullSeatStreak.SeenToEnd(0),
            maximumSeats);
    }

    private SeatForecast expected(long observationId, int stopOrder) {
        return new SeatForecast(observationId, 1, stopOrder, stopOrder - 3, 7, 3, 0.4, 0.4, 0.6, NOW);
    }

    private List<SeatForecast> saved() {
        return savedPublication().predictions();
    }

    private ForecastPublication savedPublication() {
        ArgumentCaptor<ForecastPublication> captured = ArgumentCaptor.forClass(ForecastPublication.class);
        verify(forecasts).save(captured.capture());
        return captured.getValue();
    }
}
