package com.gustler.backend.api.push.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.api.board.support.BoardDatabaseFixture;
import com.gustler.backend.api.board.support.ForecastBoardFixture;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.support.FixedDaytimeClockConfig;
import com.gustler.backend.support.IntegrationTest;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * 알림 문구가 예보 기록에서 읽은 예측으로 만들어지는지 실제 판정보드 조회와 실제 DB로 검증한다.
 */
@IntegrationTest
@Transactional
@Import(FixedDaytimeClockConfig.class)
class NotificationMessageForecastIntegrationTest {
    private static final FavoriteTarget TARGET = new FavoriteTarget(
        ForecastBoardFixture.ROUTE_ID,
        ForecastBoardFixture.STOP_ID,
        FavoriteDirection.DOWN);
    private static final String TITLE = ForecastBoardFixture.ROUTE_NAME + "번 · " + ForecastBoardFixture.STOP_NAME;

    @Autowired
    private NotificationMessageResolver resolver;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private Clock clock;

    private ForecastBoardFixture fixture;
    private OffsetDateTime now;

    @BeforeEach
    void setUp() {
        fixture = new ForecastBoardFixture(jdbcClient, objectMapper);
        now = OffsetDateTime.now(clock).withNano(0);
    }

    @Test
    void 예측이_있으면_넣은_확률의_구간과_기대_좌석을_문구에_싣는다() {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertForecast(vehicle, modelId, 0.25, 12.5);
        fixture.insertArrival(now.minusSeconds(10).toInstant(), 300);

        // when
        NotificationMessage actual = resolver.apply(TARGET);

        // then
        assertThat(actual).isEqualTo(new NotificationMessage(
            TITLE,
            "약 5분 후 도착 · 탑승확률 높음\n도착 시 빈 좌석 12석 예상"));
    }

    @Test
    void 예측_없이_끝난_실행_줄이면_확률과_좌석을_정보_없음으로_쓴다() {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(route, now.minusMinutes(1));
        fixture.insertRunWithoutForecast(vehicle, modelId, "OUT_OF_SCOPE");
        fixture.insertArrival(now.minusSeconds(10).toInstant(), 300);

        // when
        NotificationMessage actual = resolver.apply(TARGET);

        // then
        assertThat(actual).isEqualTo(new NotificationMessage(
            TITLE,
            "약 5분 후 도착 · 탑승확률 정보 없음\n예상 빈 좌석 정보 없음"));
    }

    @Test
    void 관측의_만료_시각이_지나면_예측이_있어도_숫자_없이_확인_불가_문구를_보낸다() {
        // given
        BoardDatabaseFixture.RouteContext route = fixture.insertRoute(now.minusDays(1));
        final long modelId = fixture.insertActiveModel(now.minusDays(1));
        ForecastBoardFixture.ObservedVehicle vehicle = fixture.insertApproachingVehicle(
            route,
            now.minusMinutes(5).minusSeconds(1));
        fixture.insertForecast(vehicle, modelId, 0.25, 12.5);

        // when
        NotificationMessage actual = resolver.apply(TARGET);

        // then
        assertThat(actual).isEqualTo(NotificationMessage.unavailable());
    }
}
