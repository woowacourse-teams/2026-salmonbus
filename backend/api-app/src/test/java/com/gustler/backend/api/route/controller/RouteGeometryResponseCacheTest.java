package com.gustler.backend.api.route.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.api.route.application.RouteDisplayFingerprint;
import com.gustler.backend.api.route.application.RouteGeometryFingerprint;
import com.gustler.backend.api.route.controller.RouteGeometryResponseCache.CachedRouteGeometry;
import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Shape;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Stop;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.util.unit.DataSize;

class RouteGeometryResponseCacheTest {
    private static final Duration MAX_AGE = Duration.ofMinutes(10);

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final AtomicInteger loads = new AtomicInteger();
    private final AtomicLong now = new AtomicLong();

    private RouteGeometryResponseCache cache(DataSize maxBytes, Duration joinTimeout) {
        return new RouteGeometryResponseCache(maxBytes, MAX_AGE, joinTimeout, meters, now::get);
    }

    private RouteGeometryResponseCache cache() {
        return cache(DataSize.ofMegabytes(32), Duration.ofSeconds(1));
    }

    private static RouteGeometryFingerprint fingerprint(String routeId, String revision) {
        return new RouteGeometryFingerprint(routeId, revision, null, null, null, "e", RouteDisplayFingerprint.disabled());
    }

    private static RouteGeometry geometry(String routeId, String name, final int points, final boolean shape) {
        Point a = new Point(127, 37);
        Point b = new Point(127.2, 37.2);
        List<Point> line = new ArrayList<>();
        for (int index = 0; index < points; index++) {
            line.add(new Point(127 + index * 0.0001, 37 + index * 0.0001));
        }
        return new RouteGeometry(routeId, 7, name, "A", "B", 2,
                List.of(new Stop("206000637", 1, "A", "UP", true, a), new Stop("206000638", 2, "B", "UP", true, b),
                        new Stop("206000637", 3, "A", "DOWN", true, a)),
                shape ? new Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"), Instant.EPOCH, line) : null);
    }

    private static RouteGeometry ready(String routeId, String name) {
        return geometry(routeId, name, 3, true);
    }

    /** 서비스처럼 지문이 있으면 재사용을 먼저 묻고, 없으면 조립한다. */
    private CachedRouteGeometry serve(RouteGeometryResponseCache cache, RouteGeometryFingerprint fingerprint,
            RouteGeometry geometry) {
        try (RouteGeometryResponseCache.Lookup lookup = cache.lookup()) {
            if (fingerprint != null) {
                var reused = lookup.reuse(fingerprint);
                if (reused.isPresent()) {
                    return reused.get();
                }
            }
            loads.incrementAndGet();
            return lookup.create(geometry, fingerprint);
        }
    }

    private double count(String result) {
        return meters.counter("salmonbus.route.geometry.cache", "result", result).count();
    }

    @Test
    void 같은_지문이면_만들지_않고_둔_응답을_준다() {
        var cache = cache();
        var key = fingerprint("204000057", "r1");

        var first = serve(cache, key, ready("204000057", "3330"));
        var second = serve(cache, key, ready("204000057", "다른 이름"));

        assertThat(second).isSameAs(first);
        assertThat(first.etag()).isEqualTo("W/\"g-" + first.body().geometryRevision() + "\"");
        assertThat(loads).hasValue(1);
        assertThat(count("hit")).isEqualTo(1);
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void 지문이_달라지면_다시_만들고_그_노선의_이전_응답을_바꾼다() {
        var cache = cache();
        var old = fingerprint("204000057", "r1");
        var changed = fingerprint("204000057", "r2");
        serve(cache, old, ready("204000057", "3330"));

        var next = serve(cache, changed, ready("204000057", "3330-1"));
        var back = serve(cache, old, ready("204000057", "3330"));

        assertThat(next.body().name()).isEqualTo("3330-1");
        assertThat(back.body().name()).isEqualTo("3330");
        assertThat(loads).hasValue(3);
        assertThat(meters.get("salmonbus.route.geometry.cache.entries").gauge().value()).isEqualTo(1);
    }

    @Test
    void UNAVAILABLE과_지문이_없는_응답은_두지_않는다() {
        var cache = cache();
        var key = fingerprint("204000057", "r1");

        var unavailable = serve(cache, key, geometry("204000057", "3330", 3, false));
        serve(cache, key, geometry("204000057", "3330", 3, false));
        serve(cache, null, ready("204000057", "3330"));
        serve(cache, null, ready("204000057", "3330"));

        assertThat(unavailable.etag()).isNull();
        assertThat(loads).hasValue(4);
        assertThat(meters.get("salmonbus.route.geometry.cache.entries").gauge().value()).isZero();
    }

    @Test
    void 지문이_같아도_안전_상한이_지나면_다시_조립한다() {
        var cache = cache();
        var key = fingerprint("204000057", "r1");
        var first = serve(cache, key, ready("204000057", "3330"));

        now.addAndGet(MAX_AGE.minusNanos(1).toNanos());
        serve(cache, key, ready("204000057", "3330"));
        now.addAndGet(1);
        var rebuilt = serve(cache, key, ready("204000057", "3330"));

        assertThat(loads).hasValue(2);
        assertThat(rebuilt).isNotSameAs(first);
        assertThat(rebuilt.etag()).isEqualTo(first.etag());
        assertThat(count("expired")).isEqualTo(1);
    }

    @Test
    void 안전_상한은_0이하로_둘_수_없고_캐시를_끄는_값은_크기_상한_0이다() {
        assertThatThrownBy(() -> new RouteGeometryResponseCache(DataSize.ofMegabytes(32), Duration.ZERO,
                Duration.ofSeconds(1), meters, now::get)).isInstanceOf(IllegalArgumentException.class);
        assertThat(cache(DataSize.ofBytes(0), Duration.ofSeconds(1)).enabled()).isFalse();
        assertThat(RouteGeometryResponseCache.disabled().enabled()).isFalse();
    }

    @Test
    void 크기는_좌표_점_개수로_어림하고_합계가_상한을_넘으면_오래_쓰지_않은_노선부터_지운다() {
        var small = serve(cache(), null, geometry("204000057", "3330", 100, true));
        final long size = RouteGeometryResponseCache.estimatedBytesOf(small.body());
        assertThat(size).isGreaterThanOrEqualTo(100 * RouteGeometryResponseCache.POINT_BYTES);
        var cache = cache(DataSize.ofBytes(size * 2 + size / 2), Duration.ofSeconds(1));
        var first = fingerprint("204000057", "r1");
        var second = fingerprint("204000058", "r1");
        var third = fingerprint("204000059", "r1");

        serve(cache, first, geometry("204000057", "3330", 100, true));
        serve(cache, second, geometry("204000058", "3331", 100, true));
        serve(cache, first, geometry("204000057", "3330", 100, true));
        serve(cache, third, geometry("204000059", "3332", 100, true));
        loads.set(0);
        serve(cache, first, geometry("204000057", "3330", 100, true));
        serve(cache, second, geometry("204000058", "3331", 100, true));

        assertThat(loads).hasValue(1);
        assertThat(meters.get("salmonbus.route.geometry.cache.estimated.bytes").gauge().value())
                .isLessThanOrEqualTo(size * 2 + size / 2);
    }

    @Test
    void 상한보다_큰_응답은_두지_않는다() {
        var cache = cache(DataSize.ofBytes(RouteGeometryResponseCache.BASE_BYTES), Duration.ofSeconds(1));
        var key = fingerprint("204000057", "r1");

        serve(cache, key, ready("204000057", "3330"));
        serve(cache, key, ready("204000057", "3330"));

        assertThat(loads).hasValue(2);
    }

    @Test
    void 같은_지문을_동시에_요청하면_한_번만_조립하고_같은_응답을_준다() throws Exception {
        var cache = cache();
        var key = fingerprint("204000057", "r1");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<CachedRouteGeometry> leader = pool.submit(() -> {
                try (var lookup = cache.lookup()) {
                    assertThat(lookup.reuse(key)).isEmpty();
                    started.countDown();
                    release.await();
                    loads.incrementAndGet();
                    return lookup.create(ready("204000057", "3330"), key);
                }
            });
            started.await();
            Future<CachedRouteGeometry> follower = pool.submit(() -> serve(cache, key, ready("204000057", "3330")));
            while (count("joined") < 1) {
                Thread.onSpinWait();
            }
            release.countDown();

            assertThat(follower.get(5, TimeUnit.SECONDS)).isSameAs(leader.get(5, TimeUnit.SECONDS));
            assertThat(loads).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 먼저_조립하는_요청이_기다림_상한을_넘기면_직접_조립한다() throws Exception {
        var cache = cache(DataSize.ofMegabytes(32), Duration.ofMillis(50));
        var key = fingerprint("204000057", "r1");
        var release = new CountDownLatch(1);
        var claimed = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> stuck = pool.submit(() -> {
                try (var lookup = cache.lookup()) {
                    lookup.reuse(key);
                    claimed.countDown();
                    release.await();
                }
                return null;
            });
            claimed.await();

            var response = serve(cache, key, ready("204000057", "3330"));

            assertThat(response.etag()).isNotNull();
            assertThat(loads).hasValue(1);
            assertThat(count("join_missed")).isEqualTo(1);
            release.countDown();
            stuck.get(5, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void 조립을_맡은_요청이_실패해_닫히면_기다리던_요청이_곧바로_직접_조립한다() throws Exception {
        var cache = cache(DataSize.ofMegabytes(32), Duration.ofMinutes(1));
        var key = fingerprint("204000057", "r1");
        var claimed = new CountDownLatch(1);
        var fail = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> failing = pool.submit(() -> {
                try (var lookup = cache.lookup()) {
                    lookup.reuse(key);
                    claimed.countDown();
                    fail.await();
                    throw new IllegalStateException("db");
                }
            });
            claimed.await();
            Future<CachedRouteGeometry> follower = pool.submit(() -> serve(cache, key, ready("204000057", "3330")));
            while (count("joined") < 1) {
                Thread.onSpinWait();
            }
            fail.countDown();

            assertThat(follower.get(5, TimeUnit.SECONDS).etag()).isNotNull();
            assertThat(failing).failsWithin(5, TimeUnit.SECONDS);
            assertThat(loads).hasValue(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void ETag는_본문의_geometryRevision으로_만들고_UNAVAILABLE에는_없다() {
        var ready = CachedRouteGeometry.of(RouteGeometryResponse.from(ready("204000057", "3330"), false));
        var unavailable = CachedRouteGeometry.of(
                RouteGeometryResponse.from(geometry("204000057", "3330", 3, false), false));

        assertThat(ready.etag()).isEqualTo("W/\"g-" + ready.body().geometryRevision() + "\"");
        assertThat(unavailable.etag()).isNull();
    }
}
