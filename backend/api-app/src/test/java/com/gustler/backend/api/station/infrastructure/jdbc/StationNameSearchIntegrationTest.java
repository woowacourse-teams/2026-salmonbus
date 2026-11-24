package com.gustler.backend.api.station.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.api.station.InvalidStationSearchException;
import com.gustler.backend.api.station.StationSearchTestData;
import com.gustler.backend.api.station.application.StationReadRepository;
import com.gustler.backend.api.station.domain.MapStation;
import com.gustler.backend.api.station.domain.StationSearchQuery;
import com.gustler.backend.support.IntegrationTest;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;

@IntegrationTest
class StationNameSearchIntegrationTest {
    private static final int LIMIT = 30;

    @Autowired
    private StationReadRepository repository;

    @Autowired
    private JdbcClient jdbc;

    private StationSearchTestData data;

    @BeforeEach
    void clear() {
        data = new StationSearchTestData(jdbc);
        data.clear();
    }

    private List<MapStation> search(String raw) {
        return repository.search(StationSearchQuery.parse(raw), LIMIT);
    }

    private List<String> idsOf(String raw) {
        return search(raw).stream().map(MapStation::stationId).toList();
    }

    @Test
    void 이름이_같은_정류장이_앞부분_일치보다_앞이고_앞부분_일치가_포함보다_앞이다() {
        // given
        data.station("100000001", "신강남");
        data.station("100000002", "강남역");
        data.station("100000003", "강남");

        // when
        List<String> ids = idsOf("강남");

        // then
        assertThat(ids).containsExactly("100000003", "100000002", "100000001");
    }

    @Test
    void 같은_정도_안에서는_우리_노선_정류장이_먼저이고_정도가_더_중요하다() {
        // given
        data.station("100000001", "강남역");
        data.salmonStop("204000057", "100000009", "강남역", 127.11, 37.39);
        data.salmonStop("204000058", "100000008", "신강남역", 127.12, 37.39);

        // when
        List<String> ids = idsOf("강남역");

        // then
        assertThat(ids).containsExactly("100000009", "100000001", "100000008");
    }

    @Test
    void 같은_정도와_종류에서는_이름_순이고_같으면_정류장_ID_순이다() {
        // given
        data.station("100000003", "마포나");
        data.station("100000002", "마포가");
        data.station("100000001", "마포가");

        // when
        List<String> ids = idsOf("마포");

        // then
        assertThat(ids).containsExactly("100000001", "100000002", "100000003");
    }

    @Test
    void 결과는_30개까지이고_자르기_전에_순서를_정한다() {
        // given
        IntStream.rangeClosed(1, 40).forEach(n ->
            data.station("1000000%02d".formatted(n), "마포역%02d".formatted(41 - n)));
        data.station("100000099", "마포역");

        // when
        List<MapStation> found = search("마포역");

        // then
        assertThat(found).hasSize(30);
        assertThat(found.getFirst().name()).isEqualTo("마포역");
        assertThat(found.get(1).name()).isEqualTo("마포역01");
        assertThat(found.getLast().name()).isEqualTo("마포역29");
    }

    @Test
    void 실제_이름의_기호를_무시하고_찾는다() {
        // given
        data.station("100000001", "솔샘중고등학교.팔팔·미송경로당");

        // when & then
        assertThat(idsOf("솔샘중고등학교팔팔")).containsExactly("100000001");
        assertThat(idsOf("솔샘중고등학교.팔팔")).containsExactly("100000001");
    }

    @ParameterizedTest
    @ValueSource(strings = {".", "(", ")", "-", "·", "㈜", "&", "_", " "})
    void 이름과_검색어_양쪽에서_기호와_공백을_무시한다(String symbol) {
        // given
        data.station("100000001", "가나" + symbol + "다라");
        data.station("100000002", "가나다라마");

        // when & then
        assertThat(idsOf("가나다라")).containsExactly("100000001", "100000002");
        assertThat(idsOf("가나" + symbol + "다라")).containsExactly("100000001", "100000002");
    }

    @Test
    void 공백이_있는_검색어도_붙여_쓴_이름을_찾는다() {
        // given
        data.station("100000001", "강남역");

        // when & then
        assertThat(idsOf("강남 역")).containsExactly("100000001");
    }

    @Test
    void 영문은_대소문자를_구별하지_않는다() {
        // given
        data.station("100000001", "KT&G Plaza");
        data.station("100000002", "kt&g plaza 2");

        // when & then
        assertThat(idsOf("Kt g PLAZA")).containsExactly("100000001", "100000002");
    }

    @Test
    void 검색어의_퍼센트와_밑줄은_이름과_맞춰_보지_않는다() {
        // given
        data.station("100000001", "가나");
        data.station("100000002", "가_나%다");

        // when & then
        assertThat(idsOf("가%")).containsExactly("100000001", "100000002");
        assertThat(idsOf("가_나")).containsExactly("100000001", "100000002");
        assertThat(idsOf("다%")).containsExactly("100000002");
    }

    @Test
    void 우리_노선_정류장은_노선_자료의_이름으로_한_번만_나오고_방면과_노선이_채워진다() {
        // given
        data.station("100000009", "명부이름");
        data.salmonStop("204000057", "100000009", "노선이름", 127.11, 37.39);

        // when
        List<MapStation> byRouteName = search("노선이름");

        // then
        assertThat(search("명부이름")).isEmpty();
        assertThat(byRouteName).singleElement().satisfies(station ->
            assertSoftly(softly -> {
                softly.assertThat(station.stationId()).isEqualTo("100000009");
                softly.assertThat(station.name()).isEqualTo("노선이름");
                softly.assertThat(station.direction()).isEqualTo("다음정류장");
                softly.assertThat(station.salmonRouteIds()).containsExactly("204000057");
                softly.assertThat(station.regionName()).isEqualTo("성남");
                softly.assertThat(station.mobileNo()).isEqualTo("07489");
            }));
    }

    @Test
    void 우리_노선_정류장이_아닌_정류장은_방면과_노선이_비어_있다() {
        // given
        data.station("100000001", "일반정류장");

        // when
        List<MapStation> found = search("일반정류장");

        // then
        assertThat(found).singleElement().satisfies(station -> {
            assertThat(station.direction()).isNull();
            assertThat(station.salmonRouteIds()).isEmpty();
        });
    }

    @Test
    void 비활성_정류장은_나오지_않는다() {
        // given
        data.station("100000001", "철거정류장", false);
        data.station("100000002", "철거정류장앞");

        // when & then
        assertThat(idsOf("철거정류장")).containsExactly("100000002");
    }

    @Test
    void 노선마다_좌표가_다르면_노선이_많은_좌표_하나를_고르고_노선은_모두_담는다() {
        // given
        data.salmonStop("204000057", "100000009", "충돌정류장", 127.11, 37.39);
        data.salmonStop("204000058", "100000009", "충돌정류장", 127.12, 37.39);
        data.salmonStop("204000059", "100000009", "충돌정류장", 127.12, 37.39);

        // when
        List<MapStation> found = search("충돌정류장");

        // then
        assertThat(found).singleElement().satisfies(station ->
            assertSoftly(softly -> {
                softly.assertThat(station.x()).isEqualTo(127.12);
                softly.assertThat(station.salmonRouteIds()).containsExactly("204000057", "204000058", "204000059");
            }));
    }

    @Test
    void 노선_수가_같은_좌표_충돌은_작은_좌표를_고른다() {
        // given
        data.salmonStop("204000058", "100000009", "충돌정류장", 127.12, 37.39);
        data.salmonStop("204000057", "100000009", "충돌정류장", 127.11, 37.39);

        // when
        List<MapStation> found = search("충돌정류장");

        // then
        assertThat(found).singleElement().satisfies(station ->
            assertThat(station.x()).isEqualTo(127.11));
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "솔샘중고등학교.팔팔·미송경로당", "Ａ１ＢＣ", "KT&G(주)_본사-2호", "㈜한진 ⑴", "ÀÉÎ ß İstanbul", "ㄱㄴㄷ 가ᄀ", "ABC xyz 0189",
        "áb", "🚀정류장", "가힣힣힢", "-_.()", "\t탭\n줄"
    })
    void 자바의_정규화와_DB의_station_search_key가_같은_키를_만든다(String raw) {
        // given
        String sql = jdbc.sql("SELECT station_search_key(?)").param(raw).query(String.class).single();

        // when & then
        if (sql.isEmpty() || sql.length() > StationSearchQuery.MAX_KEY_LENGTH) {
            assertThatThrownBy(() -> StationSearchQuery.parse(raw)).isInstanceOf(InvalidStationSearchException.class);
            return;
        }
        assertThat(StationSearchQuery.parse(raw).key()).isEqualTo(sql);
    }

    @Test
    void name_key는_name에서_저장되어_생성되고_이름을_바꾸면_따라_바뀐다() {
        // given
        data.station("100000001", "KT&G (주) 본사");

        // when
        String before = jdbc.sql("SELECT name_key FROM bus_station WHERE station_id = '100000001'")
            .query(String.class).single();
        jdbc.sql("UPDATE bus_station SET name = '새 이름' WHERE station_id = '100000001'").update();
        String after = jdbc.sql("SELECT name_key FROM bus_station WHERE station_id = '100000001'")
            .query(String.class).single();
        String storage = jdbc.sql("""
            SELECT attgenerated FROM pg_attribute WHERE attrelid = 'bus_station'::regclass AND attname = 'name_key'
            """).query(String.class).single();

        // then
        assertSoftly(softly -> {
            softly.assertThat(before).isEqualTo("ktg주본사");
            softly.assertThat(after).isEqualTo("새이름");
            softly.assertThat(storage).as("s는 STORED, v는 VIRTUAL").isEqualTo("s");
        });
    }
}
