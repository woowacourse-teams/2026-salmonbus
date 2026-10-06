package com.gustler.localgbis;

import com.github.tomakehurst.wiremock.common.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

final class ReplayEngine {
    private static final List<String> ROUTES = List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.of("Asia/Seoul"));
    private final Map<String, Route> routes;
    private final Configuration configuration;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final String runId;
    private final AtomicLong startedAt = new AtomicLong(Long.MIN_VALUE);

    ReplayEngine(Path routeFile, Path scenarioFile) throws Exception {
        this(readRoutes(routeFile), Json.read(Files.readString(scenarioFile), Configuration.class),
            Clock.systemUTC(), System::nanoTime, UUID.randomUUID().toString().substring(0, 8));
    }

    ReplayEngine(Dataset dataset, Configuration configuration, Clock clock, LongSupplier nanoTime, String runId) {
        require("local-routes-v1".equals(dataset.version()), "노선 자료 버전이 다릅니다.");
        require(dataset.routes().stream().map(Route::displayName).toList().equals(ROUTES), "8개 노선의 순서를 확인해 주세요.");
        require("local-replay-v1".equals(configuration.version()) && configuration.stepSeconds() >= 1
            && configuration.stepSeconds() <= 300 && configuration.idleSeconds() >= 1
            && configuration.idleSeconds() <= 600, "재생 간격을 확인해 주세요.");
        this.configuration = configuration;
        this.clock = clock;
        this.nanoTime = nanoTime;
        this.runId = runId;
        Map<String, Route> indexed = new LinkedHashMap<>();
        for (Route route : dataset.routes()) {
            require(route.routeId().matches("[0-9]{9}") && indexed.put(route.routeId(), route) == null
                && route.stops().size() >= 2 && route.stops().size() <= 512
                && route.turnSequence() > 1 && route.turnSequence() < route.stops().size(), "노선과 회차 순번을 확인해 주세요.");
            for (int index = 0; index < route.stops().size(); index++) {
                Stop stop = route.stops().get(index);
                require(stop.sequence() == index + 1 && stop.stationId().matches("[0-9]{9}")
                    && !stop.name().isBlank() && Double.isFinite(stop.x()) && Double.isFinite(stop.y()), "정류장 자료를 확인해 주세요.");
            }
        }
        routes = Map.copyOf(indexed);
    }

    static Dataset readRoutes(Path file) throws Exception {
        require(!Files.isSymbolicLink(file) && Files.size(file) <= 1_048_576, "노선 파일을 확인해 주세요.");
        return Json.read(Files.readString(file), Dataset.class);
    }

    Reply response(String operation, String mode, String routeId) {
        Route route = routes.get(routeId);
        if (route == null) {
            return new Reply(404, Map.of("error", "unsupported-local-route"));
        }
        return switch (operation) {
            case "route-info" -> new Reply(200, envelope(0, Map.of("busRouteInfoItem", info(route))));
            case "route-stations" -> new Reply(200, envelope(0, Map.of("busRouteStationList", stations(route))));
            case "location" -> locations(route, mode);
            default -> new Reply(400, Map.of("error", "unsupported-local-operation"));
        };
    }

    private Map<String, Object> info(Route route) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("routeName", route.displayName());
        item.put("startStationName", route.startStopName());
        item.put("endStationName", route.endStopName());
        item.put("upFirstTime", route.timetable().upFirstDepartureTime());
        item.put("upLastTime", route.timetable().upLastDepartureTime());
        item.put("downFirstTime", route.timetable().downFirstDepartureTime());
        item.put("downLastTime", route.timetable().downLastDepartureTime());
        return item;
    }

    private List<Map<String, Object>> stations(Route route) {
        return route.stops().stream().map(stop -> Map.<String, Object>of("stationId", stop.stationId(),
            "stationName", stop.name(), "stationSeq", stop.sequence(), "turnSeq", route.turnSequence(),
            "turnYn", stop.sequence() == route.turnSequence() ? "Y" : "N", "x", stop.x(), "y", stop.y())).toList();
    }

    private Reply locations(Route route, String mode) {
        if (mode.equals("empty")) {
            return new Reply(200, envelope(4, null));
        }
        if (mode.equals("upstream-error")) {
            return new Reply(503, envelope(1, null));
        }
        if (!mode.equals("normal") && !mode.equals("unknown-seat")) {
            return new Reply(400, Map.of("error", "unsupported-local-scenario"));
        }
        final long now = nanoTime.getAsLong();
        startedAt.compareAndSet(Long.MIN_VALUE, now);
        final long elapsed = Math.max(0, (now - startedAt.get()) / 1_000_000_000L);
        final int downCount = route.stops().size() - route.turnSequence() + 1;
        final long period = (long) Math.max(route.turnSequence(), downCount) * configuration.stepSeconds() + configuration.idleSeconds();
        final long cycle = elapsed / period;
        final int step = (int) ((elapsed % period) / configuration.stepSeconds());
        List<Map<String, Object>> vehicles = new ArrayList<>();
        if (step < route.turnSequence()) {
            vehicles.add(vehicle(route, step + 1, route.turnSequence(), step, cycle, "U", mode));
        }
        if (step < downCount) {
            vehicles.add(vehicle(route, route.turnSequence() + step, route.stops().size(), step, cycle, "D", mode));
        }
        return vehicles.isEmpty() ? new Reply(200, envelope(4, null))
            : new Reply(200, envelope(0, Map.of("busLocationList", vehicles)));
    }

    private Map<String, Object> vehicle(Route route, final int sequence, final int lastSequence,
                                      final int step, final long cycle, String direction, String mode) {
        Stop stop = route.stops().get(sequence - 1);
        final int remaining = mode.equals("unknown-seat") ? -1 : step == 0 ? 40 : Math.floorMod(40 - step * 7, 41);
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("plateNo", "LOCAL-" + route.displayName() + "-" + direction);
        value.put("vehId", "L" + runId + "-" + route.routeId() + "-" + cycle + direction);
        value.put("lowPlate", 2);
        value.put("routeId", route.routeId());
        value.put("routeTypeCd", 11);
        value.put("stationId", stop.stationId());
        value.put("stationSeq", sequence);
        value.put("stateCd", sequence == lastSequence ? 0 : 2);
        value.put("remainSeatCnt", remaining);
        value.put("crowded", remaining < 0 ? 0 : remaining == 0 ? 4 : remaining < 10 ? 3 : remaining < 25 ? 2 : 1);
        value.put("taglessCd", 0);
        return value;
    }

    private Map<String, Object> envelope(final int code, Object body) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("comMsgHeader", "");
        response.put("msgHeader", Map.of("queryTime", QUERY_TIME.format(clock.instant()), "resultCode", code,
            "resultMessage", code == 0 ? "개발용 정상 응답" : code == 4 ? "결과가 존재하지 않습니다." : "개발용 상류 오류"));
        if (body != null) {
            response.put("msgBody", body);
        }
        return Map.of("response", response);
    }

    private static void require(final boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    record Reply(int status, Map<String, Object> body) { }
    public record Dataset(String version, List<Route> routes) { }
    public record Configuration(String version, int stepSeconds, int idleSeconds) { }
    public record Route(String routeId, String displayName, String startStopName, String endStopName,
                        int turnSequence, String contentDigest, Timetable timetable, List<Stop> stops) { }
    public record Timetable(String upFirstDepartureTime, String upLastDepartureTime,
                            String downFirstDepartureTime, String downLastDepartureTime) { }
    public record Stop(int sequence, String stationId, String name, String direction,
                       boolean boardingAllowed, double x, double y) { }
}
