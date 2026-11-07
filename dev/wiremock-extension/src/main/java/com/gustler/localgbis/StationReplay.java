package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.common.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;

/**
 * 정류소 조회(주변정류소 목록)와 다른 노선 도착정보를 11-05 실측 자료로 재생한다.
 * 자료 파일이 없으면 빈 결과를 돌려 기존 재생에 영향을 주지 않는다.
 */
final class StationReplay {
    static final int AROUND_RADIUS_METERS = 500;
    static final int DAILY_LIMIT = 10_000;
    private static final int COUNTDOWN_PERIOD_SECONDS = 900;
    private final List<AroundStation> stations;
    private final Map<String, RecordedStation> arrivals;
    private final Instant startedAt;
    private final AtomicInteger used = new AtomicInteger();

    private StationReplay(List<AroundStation> stations, Map<String, RecordedStation> arrivals, Instant startedAt) {
        this.stations = List.copyOf(stations);
        this.arrivals = Map.copyOf(arrivals);
        this.startedAt = startedAt;
    }

    static StationReplay empty() {
        return new StationReplay(List.of(), Map.of(), Instant.now());
    }

    static StationReplay read(Path aroundFile, Path arrivalFile, Instant startedAt) throws Exception {
        List<AroundStation> stations = List.of();
        Map<String, RecordedStation> arrivals = Map.of();
        if (Files.isRegularFile(aroundFile)) {
            AroundDataset dataset = Json.read(Files.readString(aroundFile), AroundDataset.class);
            ReplayEngine.require("local-station-around-v1".equals(dataset.version()), "정류소 재생 자료 버전이 다릅니다.");
            stations = dataset.stations();
        }
        Path baselineFile = aroundFile.resolveSibling("station-baseline.json.gz");
        if (Files.isRegularFile(baselineFile)) {
            try (InputStream input = new GZIPInputStream(Files.newInputStream(baselineFile))) {
                AroundDataset baseline = Json.read(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8),
                    AroundDataset.class
                );
                ReplayEngine.require("local-station-around-v1".equals(baseline.version()), "정류소 기초 재생 버전이 다릅니다.");
                Map<String, AroundStation> merged = new LinkedHashMap<>();
                baseline.stations().forEach(station -> merged.put(station.stationId(), station));
                stations.forEach(station -> merged.put(station.stationId(), station));
                stations = List.copyOf(merged.values());
            }
        }
        if (Files.isRegularFile(arrivalFile)) {
            ArrivalDataset dataset = Json.read(Files.readString(arrivalFile), ArrivalDataset.class);
            ReplayEngine.require("local-station-arrivals-v1".equals(dataset.version()), "도착정보 재생 자료 버전이 다릅니다.");
            arrivals = dataset.stations();
        }
        return new StationReplay(stations, arrivals, startedAt);
    }

    /** 실제 API처럼 반경 500m 안 정류장을 거리순으로 돌려준다. 결과가 없으면 결과 코드 4다. */
    List<Map<String, Object>> around(final double x, final double y) {
        used.incrementAndGet();
        final double metersPerLongitude = 111_195 * Math.cos(Math.toRadians(y));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (AroundStation station : stations) {
            final double distance = Math.hypot((station.x() - x) * metersPerLongitude, (station.y() - y) * 111_195);
            if (distance > AROUND_RADIUS_METERS) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("centerYn", station.centerYn());
            row.put("mobileNo", " " + station.mobileNo());
            row.put("regionName", station.regionName());
            row.put("stationId", Long.parseLong(station.stationId()));
            row.put("stationName", station.stationName());
            row.put("x", station.x());
            row.put("y", station.y());
            row.put("distance", (int) Math.round(distance));
            rows.add(row);
        }
        rows.sort(Comparator.comparingInt(row -> (int) row.get("distance")));
        return rows;
    }

    int remaining() {
        return Math.max(0, DAILY_LIMIT - used.get());
    }

    /** 카탈로그에 없는 노선의 실측 행을 돌려준다. 남은 시간은 재생 시각만큼 줄이고 15분 주기로 되돌린다. */
    List<Map<String, Object>> otherRouteRows(String stationId, Set<String> catalogRouteIds, Instant now) {
        RecordedStation recorded = arrivals.get(stationId);
        if (recorded == null) {
            return List.of();
        }
        final long elapsed = Math.max(0, now.getEpochSecond() - startedAt.getEpochSecond());
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : recorded.rows()) {
            if (catalogRouteIds.contains(String.valueOf(source.get("routeId")))) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>(source);
            row.put("stationId", Long.parseLong(stationId));
            for (int ordinal = 1; ordinal <= 2; ordinal++) {
                Object seconds = source.get("predictTimeSec" + ordinal);
                if (!(seconds instanceof Number number)) {
                    continue;
                }
                final long shifted = Math.floorMod(number.longValue() - elapsed, COUNTDOWN_PERIOD_SECONDS);
                row.put("predictTimeSec" + ordinal, shifted);
                row.put("predictTime" + ordinal, (shifted + 59) / 60);
            }
            rows.add(row);
        }
        return rows;
    }

    record AroundDataset(String version, Map<String, Object> source, List<AroundStation> stations) { }
    record AroundStation(String stationId, String stationName, double x, double y, String mobileNo,
        String regionName, String centerYn) { }
    record ArrivalDataset(String version, Map<String, Object> source, Map<String, RecordedStation> stations) { }
    record RecordedStation(String receivedAt, List<Map<String, Object>> rows) { }
}
