package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.ROUTE;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.STATION;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.STOP_SEQUENCE;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.arrivals;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.board;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.boardOfVersion;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.inMinutes;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.inSeconds;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.noSnapshot;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.received;
import static com.gustler.backend.api.salmon.application.SalmonArrivalFixture.stationOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.gustler.backend.api.board.domain.ApproachingVehicle;
import com.gustler.backend.api.board.domain.Board;
import com.gustler.backend.api.board.domain.VehicleForecast;
import com.gustler.backend.api.salmon.application.SalmonDueArrivals.Due;
import com.gustler.backend.arrival.contract.ArrivalSnapshot.Prediction;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class SalmonDueArrivalsTest {
    private static final Instant NOW = Instant.parse("2026-11-11T03:00:00Z");
    private static final Instant STALE = NOW.plusSeconds(600);
    private static final int TWENTY_MINUTES = 1200;
    private static final Board EMPTY_BOARD = board(NOW, STALE);

    @Test
    void 마감과_같은_시각에_닿는_차량은_마감_안이고_1초_늦으면_마감_밖이다() {
        assertThat(due(inSeconds("bus", 1, TWENTY_MINUTES))).containsExactly("bus");
        assertThat(due(inSeconds("bus", 1, TWENTY_MINUTES + 1))).isEmpty();
    }

    @Test
    void 마감_뒤의_차량은_세지_않는다() {
        assertThat(due(inSeconds("near", 1, 300), inSeconds("far", 2, 1500))).containsExactly("near");
    }

    @Test
    void 마감_안의_차량이_둘이면_둘_다_돌려준다() {
        assertThat(due(inSeconds("near", 1, 300), inSeconds("next", 2, 360))).containsExactly("near", "next");
    }

    @Test
    void 분_단위_예측은_마감까지_1분_넘게_남아야_마감_안이고_마감을_1분_넘게_지나야_마감_밖이다() {
        assertThat(due(inMinutes("bus", 1, 19))).containsExactly("bus");
        assertThat(due(inMinutes("bus", 1, 20))).isEmpty();
        assertThat(due(inSeconds("near", 1, 300), inMinutes("unsure", 2, 21))).isEmpty();
        assertThat(due(inSeconds("near", 1, 300), inMinutes("far", 2, 22))).containsExactly("near");
    }

    @Test
    void 예상_도착_시각이_이미_지난_예측은_세지_않는다() {
        Instant received = NOW.minusSeconds(60);

        assertThat(arrivals(received, STALE, inSeconds("gone", 1, 30), inSeconds("next", 2, 400))
            .vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE, NOW).listed()).containsExactly("next");
    }

    @Test
    void 마감_안의_차량_가운데_ID가_없는_차량이_있으면_목록을_비운다() {
        assertThat(due(inSeconds(null, 1, 300))).isEmpty();
        assertThat(due(inSeconds("near", 1, 300), inSeconds(" ", 2, 360))).isEmpty();
        assertThat(due(inSeconds("near", 1, 300), inSeconds(null, 2, 1500))).containsExactly("near");
    }

    @Test
    void 스냅샷이_없거나_만료_시각부터는_쓰지_않는다() {
        assertThat(noSnapshot(NOW).vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE, NOW).listed()).isEmpty();
        assertThat(arrivals(NOW, STALE, inSeconds("bus", 1, 900))
            .vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE, STALE.minusMillis(1)).listed()).containsExactly("bus");
        assertThat(arrivals(NOW, STALE, inSeconds("bus", 1, 900))
            .vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE, STALE).listed()).isEmpty();
    }

    @Test
    void 전광판과_노선이나_판본이나_순번이_다른_방문은_쓰지_않는다() {
        SalmonDueArrivals arrivals = arrivals(NOW, STALE, inSeconds("bus", 1, 300));

        assertThat(arrivals.vehicles("204000058", EMPTY_BOARD, STATION, STOP_SEQUENCE, NOW).listed()).isEmpty();
        assertThat(arrivals.vehicles(ROUTE, boardOfVersion("32", NOW, STALE), STATION, STOP_SEQUENCE, NOW).listed())
            .isEmpty();
        assertThat(arrivals.vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE + 1, NOW).listed()).isEmpty();
    }

    // 아래 사례의 전광판은 판정 30초 전 관측이다. 맨 앞 차량 front는 순번 2 정류장에 있고(세 정류장 앞) 300초 뒤 닿는다.
    // 순번 2에서 이 정류장까지 걸리는 시간은 330초, 한 구간 여유는 110초다.

    @Test
    void 목록의_두_대가_마감_안이면_전광판에만_보이는_뒤_차량의_도착을_맨_앞_차량이_있는_정류장에서_이어_붙인다() {
        SalmonArrivalFixture fixture = twoListed().collectable(2, inSeconds("hidden", 1, 240));

        Due due = dueWithHidden(fixture);

        assertThat(due.listed()).containsExactly("front", "second");
        assertThat(due.hidden()).containsExactly("hidden");
        then(fixture.stations()).should().getForKnownStation(stationOf(2));
    }

    @Test
    void 앞_정류장_목록에_목록의_차량이_같이_보이면_그_차량의_두_예상_시각_차이로_구간_시간을_잰다() {
        // second는 앞 정류장에 100초 뒤, 이 정류장에 360초 뒤 닿는다. 구간 시간은 260초이고 한 구간 여유는 붙지 않는다.
        assertThat(dueWithHidden(twoListed().collectable(2, inSeconds("second", 1, 100), inSeconds("hidden", 2, 940)))
            .hidden()).containsExactly("hidden");
        assertThat(dueWithHidden(twoListed().collectable(2, inSeconds("second", 1, 100), inSeconds("hidden", 2, 941)))
            .hidden()).isEmpty();
    }

    @Test
    void 이어_붙인_도착이_여유를_더해_마감을_넘으면_뒤_차량을_세지_않는다() {
        assertThat(dueWithHidden(twoListed().collectable(2, inSeconds("hidden", 1, 760))).hidden())
            .containsExactly("hidden");
        assertThat(dueWithHidden(twoListed().collectable(2, inSeconds("hidden", 1, 761))).hidden()).isEmpty();
        assertThat(dueWithHidden(twoListed().collectable(2, inSeconds("hidden", 1, 900))).hidden()).isEmpty();
    }

    @Test
    void 앞_정류장의_도착을_받지_못하거나_그_목록에_뒤_차량이_없으면_목록의_두_대만_돌려준다() {
        SalmonArrivalFixture failing = twoListed();
        given(failing.stations().getForKnownStation(anyString())).willThrow(new DataAccessResourceFailureException("db"));

        for (Due due : List.of(dueWithHidden(twoListed()),
            dueWithHidden(twoListed().collectable(2, inSeconds("other", 1, 240))), dueWithHidden(failing))) {
            assertThat(due.listed()).containsExactly("front", "second");
            assertThat(due.hidden()).isEmpty();
        }
    }

    @Test
    void 목록의_둘째_차량이_마감_뒤이거나_전광판에_뒤_차량이_없으면_앞_정류장을_묻지_않는다() {
        SalmonArrivalFixture secondBeyond = received(NOW, STALE)
            .stored(STOP_SEQUENCE, inSeconds("front", 1, 300), inSeconds("second", 2, 1500));
        SalmonArrivalFixture nothingHidden = twoListed();

        assertThat(secondBeyond.reader().vehicles(ROUTE, boardWithHidden(), STATION, STOP_SEQUENCE, NOW))
            .isEqualTo(new Due(List.of("front"), List.of()));
        assertThat(nothingHidden.reader().vehicles(ROUTE,
            board(NOW.minusSeconds(30), STALE, approaching("front", 3), approaching("second", 4)), STATION,
            STOP_SEQUENCE, NOW)).isEqualTo(new Due(List.of("front", "second"), List.of()));
        then(secondBeyond.stations()).should(never()).getForKnownStation(anyString());
        then(nothingHidden.stations()).should(never()).getForKnownStation(anyString());
    }

    private static SalmonArrivalFixture twoListed() {
        return received(NOW, STALE).stored(STOP_SEQUENCE, inSeconds("front", 1, 300), inSeconds("second", 2, 360));
    }

    private static Due dueWithHidden(SalmonArrivalFixture fixture) {
        return fixture.reader().vehicles(ROUTE, boardWithHidden(), STATION, STOP_SEQUENCE, NOW);
    }

    private static Board boardWithHidden() {
        return board(NOW.minusSeconds(30), STALE, approaching("front", 3), approaching("second", 4),
            approaching("hidden", 8));
    }

    private static ApproachingVehicle approaching(String vehicleId, int remainingStops) {
        return new ApproachingVehicle(vehicleId, remainingStops, new VehicleForecast.Unavailable());
    }

    private static List<String> due(Prediction... predictions) {
        return arrivals(NOW, STALE, predictions).vehicles(ROUTE, EMPTY_BOARD, STATION, STOP_SEQUENCE, NOW).listed();
    }
}
