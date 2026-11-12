package com.gustler.backend.api.route.controller;

import com.gustler.backend.api.route.application.RouteGeometryFingerprint;
import com.gustler.backend.api.route.application.RouteGeometryResponder;
import com.gustler.backend.api.route.dto.RouteGeometryResponse;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

/**
 * 노선마다 마지막으로 만든 지도 형상 기본 응답(READY·PARTIAL)과 ETag를 그 응답을 정한 입력의 지문과 함께 둔다.
 *
 * <p>지문이 지금 읽은 지문과 같을 때만 둔 응답을 쓴다. 정상이라면 지문이 응답을 정하므로 시간이 지나도 응답이 늦게 바뀌지 않는다. 다만 지문이
 * 응답을 정하는 입력을 놓쳤을 때 낡은 응답이 기한 없이 나가지 않도록, 둔 지 {@code max-age}가 지난 응답은 지문이 같아도 쓰지 않고 다시
 * 조립한다. 지문이 없거나(형상 미수집 등) 응답이 UNAVAILABLE이면 두지 않는다.
 *
 * <p>둔 응답의 크기는 좌표 점 개수로 어림해 합계가 {@code max-bytes}를 넘으면 가장 오래 쓰지 않은 노선부터 지운다. 한 응답이 상한보다 크면
 * 두지 않는다. {@code max-bytes}가 0이면 캐시를 쓰지 않는다. {@code max-age}는 0보다 커야 한다.
 *
 * <p>같은 지문을 다른 요청이 조립하고 있으면 {@code join-timeout}까지 그 결과를 기다리고, 그 안에 받지 못하면 직접 조립한다. 기다리는 동안
 * 트랜잭션과 연결을 쥐고 있지만, 기다리지 않았다면 같은 연결로 같은 조립을 했을 것이다.
 */
@Component
public class RouteGeometryResponseCache {
    static final long POINT_BYTES = 40;
    static final long STOP_BYTES = 200;
    static final long BASE_BYTES = 2_048;

    private final long maxBytes;
    private final long maxAgeNanos;
    private final long joinTimeoutNanos;
    private final LongSupplier ticker;
    private final Map<String, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<RouteGeometryFingerprint, CompletableFuture<CachedRouteGeometry>> loading =
            new ConcurrentHashMap<>();
    private long bytes;
    private final Counter hits;
    private final Counter misses;
    private final Counter joins;
    private final Counter joinTimeouts;
    private final Counter expirations;

    @Autowired
    public RouteGeometryResponseCache(
            @Value("${route-geometry.response-cache.max-bytes:32MB}") DataSize maxBytes,
            @Value("${route-geometry.response-cache.max-age:10m}") Duration maxAge,
            @Value("${route-geometry.response-cache.join-timeout:1s}") Duration joinTimeout,
            MeterRegistry meters) {
        this(maxBytes, maxAge, joinTimeout, meters, System::nanoTime);
    }

    RouteGeometryResponseCache(DataSize maxBytes, Duration maxAge, Duration joinTimeout, MeterRegistry meters,
            LongSupplier ticker) {
        if (maxBytes.isNegative()) {
            throw new IllegalArgumentException("route-geometry.response-cache.max-bytes는 0 이상이어야 한다");
        }
        if (maxAge.isZero() || maxAge.isNegative()) {
            throw new IllegalArgumentException("route-geometry.response-cache.max-age는 0보다 커야 한다. 끄려면 max-bytes를 0으로 둔다");
        }
        if (joinTimeout.isNegative()) {
            throw new IllegalArgumentException("route-geometry.response-cache.join-timeout은 0 이상이어야 한다");
        }
        this.maxBytes = maxBytes.toBytes();
        this.maxAgeNanos = maxAge.toNanos();
        this.joinTimeoutNanos = joinTimeout.toNanos();
        this.ticker = ticker;
        this.hits = counterOf(meters, "hit");
        this.misses = counterOf(meters, "miss");
        this.joins = counterOf(meters, "joined");
        this.joinTimeouts = counterOf(meters, "join_missed");
        this.expirations = counterOf(meters, "expired");
        Gauge.builder("salmonbus.route.geometry.cache.entries", this, RouteGeometryResponseCache::size)
                .register(meters);
        Gauge.builder("salmonbus.route.geometry.cache.estimated.bytes", this, RouteGeometryResponseCache::estimatedBytes)
                .description("둔 응답의 크기를 좌표 점 개수로 어림한 합계")
                .register(meters);
    }

    /** 응답을 두지 않는 캐시. */
    public static RouteGeometryResponseCache disabled() {
        return new RouteGeometryResponseCache(DataSize.ofBytes(0), Duration.ofMinutes(10), Duration.ZERO,
                new SimpleMeterRegistry(), System::nanoTime);
    }

    private static Counter counterOf(MeterRegistry meters, String result) {
        return Counter.builder("salmonbus.route.geometry.cache").tag("result", result).register(meters);
    }

    public boolean enabled() {
        return maxBytes > 0;
    }

    /**
     * 요청 하나의 조회를 시작한다. 조회가 끝나면 반드시 닫아야 한다. 이 요청이 조립을 맡았다가 끝내지 못했으면 닫을 때 기다리는 요청을 놓아 준다.
     */
    public Lookup lookup() {
        return new Lookup();
    }

    /**
     * 요청 하나가 쓰는 {@link RouteGeometryResponder}다. 같은 지문을 기다리는 다른 요청에 결과를 넘긴다.
     */
    public final class Lookup implements RouteGeometryResponder<CachedRouteGeometry>, AutoCloseable {
        private RouteGeometryFingerprint claimed;
        private CompletableFuture<CachedRouteGeometry> mine;

        private Lookup() {
        }

        @Override
        public Optional<CachedRouteGeometry> reuse(RouteGeometryFingerprint fingerprint) {
            CachedRouteGeometry cached = find(fingerprint);
            if (cached != null) {
                hits.increment();
                return Optional.of(cached);
            }
            CompletableFuture<CachedRouteGeometry> created = new CompletableFuture<>();
            CompletableFuture<CachedRouteGeometry> running = loading.putIfAbsent(fingerprint, created);
            if (running == null) {
                cached = find(fingerprint);
                if (cached != null) {
                    created.complete(cached);
                    loading.remove(fingerprint, created);
                    hits.increment();
                    return Optional.of(cached);
                }
                claimed = fingerprint;
                mine = created;
                misses.increment();
                return Optional.empty();
            }
            joins.increment();
            CachedRouteGeometry joined = await(running);
            if (joined == null) {
                joinTimeouts.increment();
                misses.increment();
                return Optional.empty();
            }
            return Optional.of(joined);
        }

        @Override
        public CachedRouteGeometry create(RouteGeometry geometry, RouteGeometryFingerprint fingerprint) {
            CachedRouteGeometry response = CachedRouteGeometry.of(RouteGeometryResponse.from(geometry, false));
            if (fingerprint != null) {
                store(fingerprint, response);
            }
            if (mine != null && claimed.equals(fingerprint)) {
                mine.complete(response);
            }
            return response;
        }

        @Override
        public void close() {
            if (mine == null) {
                return;
            }
            mine.complete(null);
            loading.remove(claimed, mine);
        }
    }

    private synchronized CachedRouteGeometry find(RouteGeometryFingerprint key) {
        Entry entry = entries.get(key.routeId());
        if (entry == null || !entry.fingerprint().equals(key)) {
            return null;
        }
        if (ticker.getAsLong() - entry.storedAtNanos() >= maxAgeNanos) {
            remove(key.routeId());
            expirations.increment();
            return null;
        }
        return entry.response();
    }

    private synchronized void store(RouteGeometryFingerprint fingerprint, CachedRouteGeometry response) {
        if (response.etag() == null) {
            return;
        }
        final long size = estimatedBytesOf(response.body());
        remove(fingerprint.routeId());
        if (size > maxBytes) {
            return;
        }
        entries.put(fingerprint.routeId(), new Entry(fingerprint, response, size, ticker.getAsLong()));
        bytes += size;
        Iterator<Entry> eldest = entries.values().iterator();
        while (bytes > maxBytes && eldest.hasNext()) {
            bytes -= eldest.next().bytes();
            eldest.remove();
        }
    }

    private void remove(String routeId) {
        Entry removed = entries.remove(routeId);
        if (removed != null) {
            bytes -= removed.bytes();
        }
    }

    private synchronized int size() {
        return entries.size();
    }

    private synchronized long estimatedBytes() {
        return bytes;
    }

    /**
     * 응답이 메모리에서 차지하는 크기를 어림한다. 좌표 점 하나를 {@value #POINT_BYTES}바이트, 정류장 하나를 {@value #STOP_BYTES}바이트로 센다.
     */
    static long estimatedBytesOf(RouteGeometryResponse body) {
        long points = body.points().size();
        if (body.paths() != null) {
            points += pointsOf(body.paths());
        }
        if (body.displayPaths() != null) {
            points += pointsOf(body.displayPaths());
        }
        return BASE_BYTES + points * POINT_BYTES + (long) body.busStops().size() * STOP_BYTES;
    }

    private static long pointsOf(RouteGeometryResponse.Paths paths) {
        long points = 0;
        if (paths.up() != null && paths.up().points() != null) {
            points += paths.up().points().size();
        }
        if (paths.down() != null && paths.down().points() != null) {
            points += paths.down().points().size();
        }
        return points;
    }

    private CachedRouteGeometry await(CompletableFuture<CachedRouteGeometry> running) {
        try {
            return running.get(joinTimeoutNanos, TimeUnit.NANOSECONDS);
        } catch (TimeoutException | ExecutionException notJoined) {
            return null;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** 응답 본문과 ETag. UNAVAILABLE이면 ETag가 null이다. */
    public record CachedRouteGeometry(RouteGeometryResponse body, String etag) {
        public static CachedRouteGeometry of(RouteGeometryResponse body) {
            if (body.status().equals("UNAVAILABLE")) {
                return new CachedRouteGeometry(body, null);
            }
            return new CachedRouteGeometry(body, "W/\"g-" + body.geometryRevision() + "\"");
        }
    }

    private record Entry(RouteGeometryFingerprint fingerprint, CachedRouteGeometry response, long bytes,
            long storedAtNanos) {
    }
}
