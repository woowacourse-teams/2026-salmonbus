package com.gustler.backend.forecasting.application.publication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.AfterEach;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.forecasting.api.ForecastPolicy;
import com.gustler.backend.forecasting.domain.deployment.ActiveModelDeployment;
import com.gustler.backend.forecasting.domain.deployment.ForecastRuntime;
import com.gustler.backend.forecasting.domain.deployment.RuntimeSnapshot;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.RouteStop;
import com.gustler.backend.forecasting.domain.model.RouteStops;
import com.gustler.backend.forecasting.domain.publication.PendingForecastBatch;
import com.gustler.backend.forecasting.domain.publication.RouteStopsQuery;
import com.gustler.backend.forecasting.domain.publication.VehicleTrajectoryQuery;
import com.gustler.backend.forecasting.domain.route.RouteVersionQuery;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PublishPendingForecastsRouteCoverageTest {

    private static final Instant NOW = Instant.parse("2026-09-30T02:30:00Z");
    private static final long ROUTE_VERSION_3330 = 1L;
    private static final long ROUTE_VERSION_9007 = 3L;
    private static final RouteStops STOPS_3330 = stopsOf(ROUTE_VERSION_3330, "204000057");
    private static final RouteStops STOPS_9007 = stopsOf(ROUTE_VERSION_9007, "204000070");
    private static final RuntimeSnapshot RUNTIME = new RuntimeSnapshot(
        new ActiveModelDeployment(1L, "feature-v1", "release-1", "0".repeat(64)),
        new SupportedForecastScope(List.of("1650", "3330")),
        input -> null,
        NOW);

    @Mock
    private VehicleTrajectoryQuery vehicleTrajectoryQuery;

    @Mock
    private RouteVersionQuery routeVersions;

    @Mock
    private RouteStopsQuery routeStops;

    @Mock
    private ForecastRuntime forecastRuntime;

    @Mock
    private ForecastBatchWriter forecastBatchWriter;

    private PublishPendingForecastsService job;
    private final ListAppender<ILoggingEvent> modelLogs = new ListAppender<>();

    @AfterEach
    void 로그_수집을_해제한다() {
        ((Logger) LoggerFactory.getLogger(ForecastModelUsageLog.class)).detachAppender(modelLogs);
        modelLogs.stop();
    }

    @BeforeEach
    void 예보_작업을_멈춘_시계로_세운다() {
        modelLogs.start();
        ((Logger) LoggerFactory.getLogger(ForecastModelUsageLog.class)).addAppender(modelLogs);
        job = new PublishPendingForecastsService(
            vehicleTrajectoryQuery,
            routeVersions,
            routeStops,
            forecastRuntime,
            forecastBatchWriter,
            new ForecastPolicy(Duration.ofMinutes(5), 20, 3000, 400),
            Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void 계수_묶음이_안_담는_노선은_예보도_밀린_배치_확인도_하지_않는다() {
        // given
        final PendingForecastBatch batch = new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007, ROUTE_VERSION_3330));
        when(routeStops.readStops(ROUTE_VERSION_3330)).thenReturn(STOPS_3330);
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), any(), anyInt()))
            .thenReturn(List.of(batch));
        when(vehicleTrajectoryQuery.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any()))
            .thenReturn(Optional.empty());

        // when
        job.writeForecasts();

        // then
        verify(forecastBatchWriter).writeForecastsOf(batch, STOPS_3330, RUNTIME);
        verify(vehicleTrajectoryQuery, never()).findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt());
        verify(vehicleTrajectoryQuery, never()).findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any());
    }

    @Test
    void 여덟_노선_계수_묶음이면_새_노선도_예보한다() {
        // given
        final RuntimeSnapshot eightRouteRuntime = new RuntimeSnapshot(
            new ActiveModelDeployment(2L, "feature-v1", "release-2", "1".repeat(64)),
            new SupportedForecastScope(List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500")),
            input -> null,
            NOW);
        final PendingForecastBatch batch = new PendingForecastBatch(20L, ROUTE_VERSION_9007, 3L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(eightRouteRuntime));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007));
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt()))
            .thenReturn(List.of(batch));
        when(vehicleTrajectoryQuery.findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any()))
            .thenReturn(Optional.empty());

        // when
        job.writeForecasts();

        // then
        verify(forecastBatchWriter).writeForecastsOf(batch, STOPS_9007, eightRouteRuntime);
    }

    @Test
    void 학습_정류장과_다른_노선은_건너뛰고_다음_노선은_계속_예보한다() {
        var model = new com.gustler.backend.forecasting.domain.model.SeatForecastModel() {
            @Override
            public boolean supportsRoute(RouteStops stops) {
                return stops.routeVersionId() == ROUTE_VERSION_3330;
            }
            @Override
            public com.gustler.backend.forecasting.domain.model.SeatForecastResult predict(
                com.gustler.backend.forecasting.domain.model.SeatForecastInput input) {
                throw new AssertionError("이 테스트의 계산은 writer가 담당한다");
            }
        };
        RuntimeSnapshot runtime = new RuntimeSnapshot(RUNTIME.deployment(),
            new SupportedForecastScope(List.of("3330", "9007")), model, NOW);
        var batch = new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007, ROUTE_VERSION_3330));
        when(routeStops.readStops(ROUTE_VERSION_3330)).thenReturn(STOPS_3330);
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), any(), anyInt()))
            .thenReturn(List.of(batch));
        when(vehicleTrajectoryQuery.findOldestLeftBehindAt(eq(ROUTE_VERSION_3330), any(), any())).thenReturn(Optional.empty());
        job.writeForecasts();
        verify(forecastBatchWriter).writeForecastsOf(batch, STOPS_3330, runtime);
        verify(vehicleTrajectoryQuery, never()).findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt());
        verify(vehicleTrajectoryQuery, never()).findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any());
    }

    @Test
    void 같은_모델로_반복_발행해도_사용_로그는_한번만_남긴다() {
        prepareOneRoute();
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        job.writeForecasts();
        job.writeForecasts();
        assertThat(modelLogs.list).hasSize(1);
        assertThat(modelLogs.list.getFirst().getFormattedMessage())
            .contains("event=forecast_model_used", "routeId=\"204000057\"", "routeName=\"3330\"", "routeVersionId=1",
                "batchId=10", "modelDeploymentId=1", "releaseId=\"release-1\"", "featureContractVersion=\"feature-v1\"");
    }

    @Test
    void 모델을_교체하거나_이전_모델로_복구하면_다시_기록한다() {
        prepareOneRoute();
        RuntimeSnapshot next = new RuntimeSnapshot(
            new ActiveModelDeployment(2L, "feature-v2", "release-2", "1".repeat(64)),
            RUNTIME.scope(), RUNTIME.model(), NOW);
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME), Optional.of(next), Optional.of(RUNTIME));
        job.writeForecasts();
        job.writeForecasts();
        job.writeForecasts();
        assertThat(modelLogs.list).extracting(ILoggingEvent::getFormattedMessage)
            .satisfiesExactly(
                line -> assertThat(line).contains("modelDeploymentId=1"),
                line -> assertThat(line).contains("modelDeploymentId=2"),
                line -> assertThat(line).contains("modelDeploymentId=1"));
    }

    @Test
    void 발행_실패는_모델_사용_성공으로_기록하지_않고_재시도_성공때_기록한다() {
        prepareOneRoute();
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        when(forecastBatchWriter.writeForecastsOf(any(), any(), any()))
            .thenThrow(new IllegalStateException("commit failed")).thenReturn(1);
        job.writeForecasts();
        assertThat(modelLogs.list).isEmpty();
        job.writeForecasts();
        assertThat(modelLogs.list).hasSize(1);
    }

    @Test
    void 회차에서_한_노선의_발행이_실패해도_다음_노선을_발행한다() {
        // given
        RuntimeSnapshot runtime = new RuntimeSnapshot(RUNTIME.deployment(),
            new SupportedForecastScope(List.of("3330", "9007")), RUNTIME.model(), NOW);
        PendingForecastBatch failing = new PendingForecastBatch(20L, ROUTE_VERSION_9007, 3L, NOW.minusSeconds(20));
        PendingForecastBatch next = new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20));
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(runtime));
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007, ROUTE_VERSION_3330));
        when(routeStops.readStops(ROUTE_VERSION_9007)).thenReturn(STOPS_9007);
        when(routeStops.readStops(ROUTE_VERSION_3330)).thenReturn(STOPS_3330);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_9007), any(), anyInt()))
            .thenReturn(List.of(failing));
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), any(), anyInt()))
            .thenReturn(List.of(next));
        when(forecastBatchWriter.writeForecastsOf(failing, STOPS_9007, runtime))
            .thenThrow(new IllegalStateException("commit failed"));
        when(forecastBatchWriter.writeForecastsOf(next, STOPS_3330, runtime)).thenReturn(1);

        // when
        job.writeForecasts();

        // then
        verify(forecastBatchWriter).writeForecastsOf(next, STOPS_3330, runtime);
        verify(vehicleTrajectoryQuery, never()).findOldestLeftBehindAt(eq(ROUTE_VERSION_9007), any(), any());
    }

    @Test
    void 이벤트로_요청한_노선은_그_노선의_미발행_batch만_발행한다() {
        // given
        prepareOneRoute();
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));

        // when
        job.writeForecastsOfRoute(ROUTE_VERSION_3330);

        // then
        verify(forecastBatchWriter).writeForecastsOf(
            new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20)), STOPS_3330, RUNTIME);
        verify(vehicleTrajectoryQuery).findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), eq(NOW.minus(Duration.ofMinutes(5))), eq(20));
        verify(routeStops, never()).readStops(ROUTE_VERSION_9007);
        verify(vehicleTrajectoryQuery, never()).findOldestLeftBehindAt(any(Long.class), any(), any());
    }

    @Test
    void 이벤트로_요청한_노선이_활성_버전이_아니거나_도는_모델이_없으면_발행하지_않는다() {
        // given
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME), Optional.empty());
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_9007));

        // when
        job.writeForecastsOfRoute(ROUTE_VERSION_3330);
        job.writeForecastsOfRoute(ROUTE_VERSION_9007);

        // then
        verify(routeStops, never()).readStops(any(Long.class));
        verify(forecastBatchWriter, never()).writeForecastsOf(any(), any(), any());
    }

    @Test
    void 기준_불일치_로그는_반복하지_않고_복구한_모델을_다시_기록한다() {
        ForecastModelUsageLog usage = new ForecastModelUsageLog();
        usage.referenceMismatch("204000057", "3330", 1, RUNTIME);
        usage.referenceMismatch("204000057", "3330", 1, RUNTIME);
        usage.committed("204000057", "3330", 1, 10, RUNTIME);
        usage.committed("204000057", "3330", 2, 11, RUNTIME);
        assertThat(modelLogs.list).hasSize(3);
        assertThat(modelLogs.list.getFirst().getFormattedMessage()).contains("ROUTE_REFERENCE_MISMATCH");
        assertThat(modelLogs.list.getLast().getFormattedMessage()).contains("routeVersionId=2");
    }

    @Test
    void 저장이_0건이면_사용_로그와_중복_억제_상태를_갱신하지_않는다() {
        prepareOneRoute();
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        when(forecastBatchWriter.writeForecastsOf(any(), any(), any())).thenReturn(0, 1, 0, 1);
        job.writeForecasts();
        assertThat(modelLogs.list).isEmpty();
        job.writeForecasts();
        job.writeForecasts();
        job.writeForecasts();
        assertThat(modelLogs.list).hasSize(1);
    }

    @Test
    void 노선명을_모르면_ID를_유지하고_이름은_빈_문자열로_기록한다() {
        new ForecastModelUsageLog().referenceMismatch("204000057", "", 1, RUNTIME);
        assertThat(modelLogs.list.getFirst().getFormattedMessage())
            .contains("routeId=\"204000057\"", "routeName=\"\"");
    }

    @Test
    void 외부_트랜잭션_커밋_실패는_기록하지_않고_성공_후에만_기록한다() {
        prepareOneRoute();
        when(forecastRuntime.resolveActive()).thenReturn(Optional.of(RUNTIME));
        var manager = new org.springframework.transaction.support.AbstractPlatformTransactionManager() {
            boolean failCommit = true;
            @Override protected Object doGetTransaction() { return new Object(); }
            @Override protected void doBegin(Object tx, org.springframework.transaction.TransactionDefinition definition) { }
            @Override protected void doCommit(org.springframework.transaction.support.DefaultTransactionStatus status) {
                assertThat(modelLogs.list).isEmpty();
                if (failCommit) throw new org.springframework.transaction.TransactionSystemException("commit failed");
            }
            @Override protected void doRollback(org.springframework.transaction.support.DefaultTransactionStatus status) { }
        };
        var transaction = new org.springframework.transaction.support.TransactionTemplate(manager);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> job.writeForecasts()))
            .isInstanceOf(org.springframework.transaction.TransactionSystemException.class);
        assertThat(modelLogs.list).isEmpty();
        manager.failCommit = false;
        transaction.executeWithoutResult(status -> job.writeForecasts());
        assertThat(modelLogs.list).hasSize(1);
    }

    @Test
    void 특수문자_문자열은_필드_경계를_유지하고_원래_값으로_복원된다() {
        String name = "3330 판교\"역\"\\분기\n다음\r\t";
        new ForecastModelUsageLog().committed("204000057", name, 1, 10, RUNTIME);
        String line = modelLogs.list.getFirst().getFormattedMessage();
        assertThat(line).doesNotContain("\n", "\r", "\t");
        // logfmt의 따옴표 값은 JSON 문자열 이스케이프를 사용한다.
        var field = java.util.regex.Pattern.compile("([A-Za-z]+)=(\"(?:\\\\.|[^\"\\\\])*\"|[^ ]+)").matcher(line);
        var values = new java.util.LinkedHashMap<String, String>();
        var json = new tools.jackson.databind.ObjectMapper();
        int consumed = 0;
        while (field.find()) {
            assertThat(line.substring(consumed, field.start())).isBlank();
            String value = field.group(2);
            values.put(field.group(1), value.startsWith("\"") ? json.readValue(value, String.class) : value);
            consumed = field.end();
        }
        assertThat(consumed).isEqualTo(line.length());
        assertThat(values).hasSize(8).containsEntry("routeName", name).containsEntry("routeVersionId", "1");
    }

    private void prepareOneRoute() {
        when(forecastBatchWriter.writeForecastsOf(any(), any(), any())).thenReturn(1);
        when(routeVersions.findActiveVersionIds()).thenReturn(List.of(ROUTE_VERSION_3330));
        when(routeStops.readStops(ROUTE_VERSION_3330)).thenReturn(STOPS_3330);
        when(vehicleTrajectoryQuery.findBatchesAwaitingForecast(eq(ROUTE_VERSION_3330), any(), anyInt()))
            .thenReturn(List.of(new PendingForecastBatch(10L, ROUTE_VERSION_3330, 1L, NOW.minusSeconds(20))));
    }

    private static RouteStops stopsOf(final long routeVersionId, String sourceRouteId) {
        return new RouteStops(routeVersionId, sourceRouteId, List.of(new RouteStop(routeVersionId, 1, "stop-1", true)), routeVersionId == 1 ? "3330" : "9007");
    }
}
