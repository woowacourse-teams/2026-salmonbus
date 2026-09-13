package com.gustler.backend.collector;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.gustler.backend.collector.GbisLocationResult.GbisSystemError;
import com.gustler.backend.collector.GbisLocationResult.NoVehicles;
import com.gustler.backend.collector.GbisLocationResult.Success;
import com.gustler.backend.collector.dto.BusLocationResponse.BusLocation;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * 설정에 노선을 적고 수집을 한 판 돌리면 판본이 열리고 관측이 쌓이는지 본다.
 * 상류만 시험용 대역이고 나머지는 실제 배선과 DB 를 그대로 쓴다.
 */
@IntegrationTest
class ObservationCollectorTest {

    private static final ZoneId KOREA = ZoneId.of("Asia/Seoul");
    /** 한국 시각 2026-08-28 11:14:04. 낮이라 20초 간격이다. */
    private static final Instant TICK = Instant.parse("2026-08-28T02:14:04Z");
    private static final Instant NEXT_TICK = Instant.parse("2026-08-28T02:14:24Z");
    private static final LocalDate KOREA_8_28 = LocalDate.of(2026, 8, 28);

    private static final String ROUTE_3330 = "204000057";
    private static final String STOP_205000217 = "205000217";
    private static final String STOP_277103149 = "277103149";
    private static final String STOP_208000069 = "208000069";
    private static final int TURN_SEQUENCE = 2;
    private static final String QUERY_TIME = "2026-08-28 11:14:04.9";

    private static final String VEHICLE_204000206 = "204000206";
    private static final String VEHICLE_204003542 = "204003542";
    private static final String PLATE_NUMBER = "경기70아0001";
    private static final int NORMAL_BUS = 0;
    private static final int ROUTE_TYPE_11 = 11;
    private static final int TAGLESS_1 = 1;
    private static final int SEATS_43 = 43;
    private static final int CROWD_LEVEL_3 = 3;
    private static final int RUNNING_STATE_DEPARTED = 2;

    private static final int ALREADY_USED_UP = 3;

    @MockitoBean
    private Clock clock;

    @MockitoBean
    private GbisRouteSource routeSource;

    @MockitoBean
    private GbisLocationSource locationSource;

    @Autowired
    private ObservationCollector collector;

    @Autowired
    private JdbcClient jdbcClient;

    private ListAppender<ILoggingEvent> collectorLog;

    @BeforeEach
    void 상류_대역과_시계를_세운다() {
        given(clock.getZone()).willReturn(KOREA);
        given(clock.instant()).willReturn(TICK);
        given(routeSource.read(ROUTE_3330)).willReturn(new GbisRouteResult.Success(upstreamRoute()));
        given(locationSource.read(ROUTE_3330)).willReturn(new Success(QUERY_TIME, List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217))));
        collectorLog = startCapturingCollectorLog();
    }

    @AfterEach
    void 쌓인_것을_비운다() {
        jdbcClient.sql("""
                TRUNCATE vehicle_observation, observation_batch, route_stop, route_version, route,
                    daily_call_quota RESTART IDENTITY CASCADE
                """)
            .update();
    }

    @Test
    void 판본이_없던_노선은_상류에서_받아_판본을_연다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(currentRouteVersionCount()).isEqualTo(1);
    }

    @Test
    void 판본을_열_때_노선_행도_같이_생긴다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(displayNameOf(ROUTE_3330)).isEqualTo("3330");
    }

    @Test
    void 판본이_이미_있으면_노선정보를_다시_부르지_않는다() {
        // given
        collector.collectOnce(ROUTE_3330);

        // when
        given(clock.instant()).willReturn(NEXT_TICK);
        collector.collectOnce(ROUTE_3330);

        // then
        then(routeSource).should().read(ROUTE_3330);
    }

    @Test
    void 상류가_정상으로_답한_판은_SUCCESS_ROWS로_닫힌다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("outcome", String.class)).isEqualTo("SUCCESS_ROWS");
    }

    @Test
    void 상류가_정상으로_답한_판은_본_차량만큼_관측을_쌓는다() {
        // given
        given(locationSource.read(ROUTE_3330)).willReturn(new Success(QUERY_TIME, List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217),
            busAt(VEHICLE_204003542, 3, STOP_208000069))));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(observationCount()).isEqualTo(2);
    }

    @Test
    void 운행_차량이_없다는_응답도_판을_SUCCESS_EMPTY로_닫는다() {
        // given
        given(locationSource.read(ROUTE_3330)).willReturn(new NoVehicles(QUERY_TIME));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("outcome", String.class)).isEqualTo("SUCCESS_EMPTY");
    }

    @Test
    void 상류가_오류로_답한_판은_FAILED_UPSTREAM으로_닫힌다() {
        // given
        given(locationSource.read(ROUTE_3330))
            .willReturn(new GbisSystemError(QUERY_TIME, "시스템 에러가 발생했습니다."));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("outcome", String.class)).isEqualTo("FAILED_UPSTREAM");
    }

    @Test
    void 한_판마다_수집_주기_판본이_남는다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("collection_strategy_version", String.class))
            .isEqualTo(CollectionSchedule.CURRENT_STRATEGY_VERSION);
    }

    @Test
    void 한_판은_위치정보_한도를_한_번_쓴다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(reservedCallsOf(CallQuota.BUS_LOCATION)).isEqualTo(1);
    }

    @Test
    void 노선정보를_한_번_읽는_데_한도를_두_번_쓴다() {
        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(reservedCallsOf(CallQuota.BUS_ROUTE)).isEqualTo(2);
    }

    @Test
    void 같은_초에_두_번_부르면_묶음은_한_행으로_남는다() {
        // given
        collector.collectOnce(ROUTE_3330);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(batchCount()).isEqualTo(1);
    }

    @Test
    void 다음_차례에_부르면_묶음이_한_행_더_남는다() {
        // given
        collector.collectOnce(ROUTE_3330);

        // when
        given(clock.instant()).willReturn(NEXT_TICK);
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(batchCount()).isEqualTo(2);
    }

    @Test
    void 같은_초에_두_번_부르면_관측도_마지막_시도의_것만_남는다() {
        // given 첫 시도에는 차량이 둘이었다
        given(locationSource.read(ROUTE_3330)).willReturn(new Success(QUERY_TIME, List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217),
            busAt(VEHICLE_204003542, 3, STOP_208000069))));
        collector.collectOnce(ROUTE_3330);

        // when 다시 부르니 차량이 하나다
        given(locationSource.read(ROUTE_3330)).willReturn(new Success(QUERY_TIME, List.of(
            busAt(VEHICLE_204000206, 1, STOP_205000217))));
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(observationCount()).isEqualTo(1);
    }

    @Test
    void 노선_행_확보와_판본_열기가_한_트랜잭션에서_끝난다() {
        // given 정류소 이름이 열 길이를 넘어 판본의 정류소를 넣는 데서 터진다
        given(routeSource.read(ROUTE_3330))
            .willReturn(new GbisRouteResult.Success(routeWithTooLongStopName()));

        // when
        assertThatThrownBy(() -> collector.collectOnce(ROUTE_3330)).isInstanceOf(RuntimeException.class);

        // then 노선 행만 남고 판본이 없는 상태가 안 된다
        assertThat(routeRowCount()).isZero();
    }

    @Test
    void 상류를_부른_뒤_뜻밖의_예외가_나도_묶음이_DISPATCHING으로_안_남는다() {
        // given RestClientException 이 아닌 것이라 GbisLocationSource 가 안 접어준다
        given(locationSource.read(ROUTE_3330)).willThrow(new IllegalStateException("파서가 터졌다"));

        // when
        collector.collectOnce(ROUTE_3330);

        // then 보낸 것은 맞고 결과만 모르는 자리로 닫힌다
        assertThat(onlyBatchColumn("outcome", String.class)).isEqualTo("UNKNOWN_AFTER_DISPATCH");
    }

    @Test
    void 위치정보_한도가_없으면_판이_NOT_RESERVED로_남는다() {
        // given
        useUpQuotaOf(CallQuota.BUS_LOCATION);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("outcome", String.class)).isEqualTo("NOT_RESERVED");
    }

    @Test
    void 위치정보_한도가_없으면_막힌_사유가_판에_남는다() {
        // given
        useUpQuotaOf(CallQuota.BUS_LOCATION);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(onlyBatchColumn("failure_code", String.class)).isEqualTo("LOCAL_QUOTA_EXHAUSTED");
    }

    @Test
    void 위치정보_한도가_없으면_상류를_부르지_않는다() {
        // given
        useUpQuotaOf(CallQuota.BUS_LOCATION);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        then(locationSource).shouldHaveNoInteractions();
    }

    @Test
    void 위치정보_한도가_없으면_경고_로그를_남긴다() {
        // given
        useUpQuotaOf(CallQuota.BUS_LOCATION);

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(warningMessages()).anyMatch(message -> message.contains("하루 호출 한도가 남지 않아"));
    }

    @Test
    void 노선정보를_못_읽으면_판을_열지_않는다() {
        // given
        given(routeSource.read(ROUTE_3330)).willReturn(new GbisRouteResult.Failed("상류가 답하지 않았다"));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(batchCount()).isZero();
    }

    @Test
    void 노선정보를_못_읽으면_위치정보_한도를_안_쓴다() {
        // given
        given(routeSource.read(ROUTE_3330)).willReturn(new GbisRouteResult.Failed("상류가 답하지 않았다"));

        // when
        collector.collectOnce(ROUTE_3330);

        // then
        assertThat(quotaRowCountOf(CallQuota.BUS_LOCATION)).isZero();
    }

    /**
     * 정류소 이름이 route_stop.name 의 varchar(60) 을 넘는다.
     * 노선 행은 들어가고 판본의 정류소를 넣는 데서 터지므로, 트랜잭션이 걸려 있으면 노선 행도 같이 되돌아간다.
     */
    private static UpstreamRoute routeWithTooLongStopName() {
        return new UpstreamRoute(
            ROUTE_3330, "3330", "범계역", "강남역",
            RouteStops.from(null, List.of(
                new UpstreamRouteStop(1, STOP_205000217, "범".repeat(61)))),
            new RouteTimetable("05:00", "22:35", "05:00", "23:55"));
    }

    private int routeRowCount() {
        return jdbcClient.sql("SELECT count(*) FROM route").query(Integer.class).single();
    }

    private static UpstreamRoute upstreamRoute() {
        return new UpstreamRoute(
            ROUTE_3330,
            "3330",
            "범계역",
            "강남역",
            RouteStops.from(TURN_SEQUENCE, List.of(
                new UpstreamRouteStop(1, STOP_205000217, "범계역"),
                new UpstreamRouteStop(2, STOP_277103149, "안양대교(경유)"),
                new UpstreamRouteStop(3, STOP_208000069, "안양역"))),
            new RouteTimetable("05:00", "22:35", "05:00", "23:55"));
    }

    private static BusLocation busAt(
        String vehicleId,
        final int stopSequence,
        String stopId
    ) {
        return new BusLocation(
            PLATE_NUMBER, vehicleId, NORMAL_BUS, ROUTE_3330, ROUTE_TYPE_11,
            stopId, stopSequence, RUNNING_STATE_DEPARTED, SEATS_43, CROWD_LEVEL_3, TAGLESS_1);
    }

    private ListAppender<ILoggingEvent> startCapturingCollectorLog() {
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        ((Logger) LoggerFactory.getLogger(ObservationCollector.class)).addAppender(appender);
        return appender;
    }

    private List<String> warningMessages() {
        return collectorLog.list.stream()
            .map(ILoggingEvent::getFormattedMessage)
            .toList();
    }

    private void useUpQuotaOf(
        CallQuota quota
    ) {
        jdbcClient.sql("""
                INSERT INTO daily_call_quota (provider, api_service, kst_date, reserved_calls, daily_limit)
                VALUES (?, ?, ?, ?, ?)
                """)
            .params(quota.provider(), quota.apiService(), KOREA_8_28, ALREADY_USED_UP, ALREADY_USED_UP)
            .update();
    }

    private int reservedCallsOf(
        CallQuota quota
    ) {
        return jdbcClient.sql("""
                SELECT reserved_calls FROM daily_call_quota
                WHERE provider = ? AND api_service = ? AND kst_date = ?
                """)
            .params(quota.provider(), quota.apiService(), KOREA_8_28)
            .query(Integer.class)
            .single();
    }

    private int quotaRowCountOf(
        CallQuota quota
    ) {
        return jdbcClient.sql("SELECT count(*) FROM daily_call_quota WHERE api_service = ?")
            .param(quota.apiService())
            .query(Integer.class)
            .single();
    }

    private <T> T onlyBatchColumn(
        final String column,
        Class<T> type
    ) {
        return jdbcClient.sql("SELECT %s FROM observation_batch".formatted(column))
            .query(type)
            .optional()
            .orElse(null);
    }

    private int batchCount() {
        return jdbcClient.sql("SELECT count(*) FROM observation_batch").query(Integer.class).single();
    }

    private int observationCount() {
        return jdbcClient.sql("SELECT count(*) FROM vehicle_observation").query(Integer.class).single();
    }

    private int currentRouteVersionCount() {
        return jdbcClient.sql("SELECT count(*) FROM route_version WHERE valid_to IS NULL")
            .query(Integer.class)
            .single();
    }

    private String displayNameOf(
        final String publicRouteId
    ) {
        return jdbcClient.sql("SELECT display_name FROM route WHERE public_route_id = ?")
            .param(publicRouteId)
            .query(String.class)
            .single();
    }
}
