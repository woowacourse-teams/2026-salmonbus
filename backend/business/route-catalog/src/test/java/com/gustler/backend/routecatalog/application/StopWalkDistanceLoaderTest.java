package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Result;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Status;
import com.gustler.backend.routecatalog.support.RouteCatalogIntegrationTest;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;

@RouteCatalogIntegrationTest
class StopWalkDistanceLoaderTest {
    private static final OffsetDateTime FIRST_LOAD = OffsetDateTime.parse("2026-11-04T15:00:00+09:00");
    private static final OffsetDateTime SECOND_LOAD = OffsetDateTime.parse("2026-11-05T15:00:00+09:00");
    private static final OffsetDateTime REVERT_LOAD = OffsetDateTime.parse("2026-11-06T15:00:00+09:00");
    private static final String TWO_PAIRS = "562d2d24f2ca0f5c7b9712c3c355b0eb2ae8c6183276b9a3ebf9dda3cae52432";
    private static final String ONE_PAIR = "d856d1bff4b0434203b3adf463e9c9ffac0b3e7f9c8cc7277e8e242d6623440d";

    @Autowired
    private StopWalkDistanceLoader loader;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void reset() {
        jdbc.sql("DELETE FROM stop_walk_distance").update();
        jdbc.sql("DELETE FROM stop_walk_dataset").update();
    }

    @Test
    void 처음_넣으면_파일의_쌍을_모두_적재한다() {
        Result result = load("two-pairs", FIRST_LOAD);

        assertThat(result).isEqualTo(new Result(Status.LOADED, TWO_PAIRS, 2));
        assertThat(jdbc.sql("SELECT station_b, walk_meters FROM stop_walk_distance ORDER BY station_b")
            .query((row, index) -> row.getString(1) + "=" + row.getInt(2)).list())
            .containsExactly("205000220=161", "205000231=1180");
        assertThat(jdbc.sql("SELECT pair_count FROM stop_walk_dataset").query(Integer.class).single()).isEqualTo(2);
    }

    @Test
    void 같은_파일을_다시_넣으면_쓰지_않는다() {
        load("two-pairs", FIRST_LOAD);

        Result result = load("two-pairs", SECOND_LOAD);

        assertThat(result.status()).isEqualTo(Status.UNCHANGED);
        assertThat(jdbc.sql("SELECT loaded_at FROM stop_walk_dataset").query(OffsetDateTime.class).single())
            .isEqualTo(FIRST_LOAD);
    }

    @Test
    void 다른_파일을_넣으면_이전_거리를_남기지_않고_바꾼다() {
        load("two-pairs", FIRST_LOAD);

        Result result = load("one-pair", SECOND_LOAD);

        assertThat(result).isEqualTo(new Result(Status.LOADED, ONE_PAIR, 1));
        assertThat(count("stop_walk_distance")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT checksum FROM stop_walk_dataset").query(String.class).single()).isEqualTo(ONE_PAIR);
    }

    @Test
    void 이전_파일로_되돌리면_다시_적재한다() {
        load("two-pairs", FIRST_LOAD);
        load("one-pair", SECOND_LOAD);

        Result result = load("two-pairs", REVERT_LOAD);

        assertThat(result.status()).isEqualTo(Status.LOADED);
        assertThat(count("stop_walk_distance")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT loaded_at FROM stop_walk_dataset").query(OffsetDateTime.class).single())
            .isEqualTo(REVERT_LOAD);
    }

    @Test
    void 적재_기록은_DB에서도_한_행만_둔다() {
        load("two-pairs", FIRST_LOAD);

        Throwable failure = catchThrowable(() -> jdbc.sql("""
            INSERT INTO stop_walk_dataset(checksum,osm_base,pair_count,loaded_at)
            VALUES (?, '2026-11-02T21:21:26Z', 0, ?)
            """).params("0".repeat(64), SECOND_LOAD).update());

        assertThat(failure).isInstanceOf(DuplicateKeyException.class);
    }

    private Result load(String name, OffsetDateTime at) {
        return loader.load(fixture(name + ".csv.gz"), fixture(name + ".json"), at);
    }

    private static Path fixture(String name) {
        try {
            return Path.of(StopWalkDistanceLoaderTest.class.getResource("/walking-fixture/" + name).toURI());
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
