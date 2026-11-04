package com.gustler.backend.routecatalog.infrastructure.supplementary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometryEvidence;
import org.springframework.scheduling.annotation.Scheduled;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.jdbc.core.simple.JdbcClient;

import tools.jackson.databind.ObjectMapper;

import java.time.*;
import java.util.concurrent.atomic.AtomicBoolean;

class BmsEvidenceCacheTest {
    private static final byte[] GOOD =
            "{\"TBBMSNODEM\":[{\"head\":[{\"list_total_count\":2},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},{\"row\":[{\"NODE_ID\":\"1\",\"LAT\":3745.674,\"LOGT\":12702.382},{\"NODE_ID\":\"2\",\"LAT\":3745.674,\"LOGT\":12702.497}]}]}"
                    .getBytes();

    private static JdbcClient successfulCacheJdbc() {
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).params(any(Object[].class)).update()).thenReturn(1);
        return jdbc;
    }

    @Test
    void 정상_공통캐시를_쓰고_후속조회실패시_유효캐시로_계속한다() {
        var fail = new AtomicBoolean();
        var jdbc = successfulCacheJdbc();
        var cache =
                new GgBmsNodeGeometrySource(
                        new ObjectMapper(),
                        jdbc,
                        Clock.fixed(Instant.EPOCH, ZoneOffset.UTC),
                        "test-key",
                        uri -> {
                            if (fail.get()) throw new IllegalStateException();
                            return GOOD;
                        });
        cache.refresh();
        assertThat(cache.read("204000057").nodes()).hasSize(2);
        fail.set(true);
        cache.refresh();
        assertThat(cache.read("204000057").source().status()).isEqualTo("AVAILABLE");
        assertThat(cache.read("204000081").nodes()).hasSize(2);
    }

    @Test
    void 부분페이지와_중복은_빈캐시를_정상으로_표시하지않는다() {
        var jdbc = successfulCacheJdbc();
        var partial =
                new GgBmsNodeGeometrySource(
                        new ObjectMapper(),
                        jdbc,
                        Clock.systemUTC(),
                        "test-key",
                        uri ->
                                new String(GOOD)
                                        .replace("list_total_count\":2", "list_total_count\":3")
                                        .getBytes());
        partial.refresh();
        assertThat(partial.read("204000057").source().status()).isEqualTo("UNAVAILABLE");
        var duplicate =
                new GgBmsNodeGeometrySource(
                        new ObjectMapper(),
                        jdbc,
                        Clock.systemUTC(),
                        "test-key",
                        uri ->
                                new String(GOOD)
                                        .replace("NODE_ID\":\"2", "NODE_ID\":\"1")
                                        .getBytes());
        duplicate.refresh();
        assertThat(duplicate.read("204000057").nodes()).isEmpty();
    }

    @Test
    void 만료된_캐시와_미설정키는_새근거로_사용하지않는다() {
        var clock = mock(Clock.class);
        when(clock.instant()).thenReturn(Instant.EPOCH);
        var jdbc = successfulCacheJdbc();
        var cache =
                new GgBmsNodeGeometrySource(
                        new ObjectMapper(), jdbc, clock, "test-key", uri -> GOOD);
        cache.refresh();
        when(clock.instant()).thenReturn(Instant.EPOCH.plus(Duration.ofHours(25)));
        assertThat(cache.read("204000057").source().status()).isEqualTo("STALE");
        assertThat(cache.read("204000057").nodes()).isEmpty();
        var none =
                new GgBmsNodeGeometrySource(
                        new ObjectMapper(),
                        jdbc,
                        Clock.systemUTC(),
                        "",
                        uri -> {
                            throw new AssertionError();
                        });
        none.refresh();
        assertThat(none.read("204000057").source().status()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void 공통갱신포트로_한번_가져온_BMS를_여러노선이_쓰고_독립타이머는_등록하지않는다() throws Exception {
        var requests = new AtomicInteger();
        var cache = new GgBmsNodeGeometrySource(new ObjectMapper(), successfulCacheJdbc(),
            Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "test-key", uri -> {
                requests.incrementAndGet();
                return GOOD;
            });
        RefreshRouteGeometryEvidence refresh = cache;
        refresh.refresh();
        assertThat(cache.read("204000057").nodes()).hasSize(2);
        assertThat(cache.read("204000081").nodes()).hasSize(2);
        assertThat(requests.get()).isEqualTo(1);
        assertThat(GgBmsNodeGeometrySource.class.getMethod("refresh").getAnnotation(Scheduled.class)).isNull();
    }

}
