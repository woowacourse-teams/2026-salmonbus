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
    static final List<String> MODES = List.of("normal", "mixed", "single", "sparse", "bunched",
        "up-only", "down-only", "arriving", "departed", "in-transit", "few-seats", "full",
        "unknown-seat", "missing-seat", "anonymous", "empty", "unreadable", "invalid-response",
        "upstream-error", "timeout");
    private static final List<String> ROUTES = List.of("1650", "3330", "9007", "9300", "6011", "3000", "5600", "3500");
    private static final DateTimeFormatter QUERY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
        .withZone(ZoneId.of("Asia/Seoul"));
    private final Map<String, Route> routes;
    private final Configuration configuration;
    private final Clock clock;
    private final LongSupplier nanoTime;
    private final String runId;
    private final AtomicLong startedAt = new AtomicLong(Long.MIN_VALUE);
    private final Map<String, Layout> layouts = new java.util.concurrent.ConcurrentHashMap<>();

    ReplayEngine(Path routeFile, Path scenarioFile) throws Exception {
        this(readAllRoutes(routeFile), Json.read(Files.readString(scenarioFile), Configuration.class),
            Clock.systemUTC(), System::nanoTime, UUID.randomUUID().toString().substring(0, 8));
    }

    ReplayEngine(Dataset dataset, Configuration configuration, Clock clock, LongSupplier nanoTime, String runId) {
        require("local-routes-v1".equals(dataset.version()), "노선 자료 버전이 다릅니다.");
        require(dataset.routes().stream().limit(ROUTES.size()).map(Route::displayName).toList().equals(ROUTES),
            "재생할 8개 노선의 순서를 확인해 주세요.");
        require(dataset.routes().stream().map(Route::displayName).distinct().count() == dataset.routes().size(),
            "노선 이름이 중복됩니다.");
        require("local-replay-v1".equals(configuration.version()) && configuration.stepSeconds() >= 1
            && configuration.stepSeconds() <= 300 && configuration.idleSeconds() >= 1
            && configuration.idleSeconds() <= 600 && configuration.vehiclesPerDirection() >= 1
            && configuration.vehiclesPerDirection() <= 6, "재생 간격과 차량 수를 확인해 주세요.");
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

    static Dataset readAllRoutes(Path file) throws Exception {
        Dataset replay = readRoutes(file);
        Dataset catalog = readRoutes(file.resolveSibling("catalog-routes.json"));
        require("local-catalog-v1".equals(catalog.version())
            && catalog.routes().stream().noneMatch(route -> ROUTES.contains(route.displayName())),
            "카탈로그 노선과 재생 노선을 분리해 주세요.");
        return new Dataset(replay.version(), java.util.stream.Stream.concat(
            replay.routes().stream(), catalog.routes().stream()).toList());
    }

    Reply response(String operation, String mode, String routeId) {
        Route route = routes.get(routeId);
        if (route == null) {
            return new Reply(404, Map.of("error", "unsupported-local-route"));
        }
        return switch (operation) {
            case "route-info" -> new Reply(200, envelope(0, Map.of("busRouteInfoItem", info(route))));
            case "route-stations" -> new Reply(200, envelope(0, Map.of("busRouteStationList", stations(route))));
            case "location" -> ROUTES.contains(route.displayName())
                ? locations(route, mode)
                : new Reply(404, Map.of("error", "local-route-observation-disabled"));
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
        if (mode.equals("invalid-response")) {
            return new Reply(200, envelope(0, null));
        }
        if (!MODES.contains(mode)) {
            return new Reply(400, Map.of("error", "unsupported-local-scenario"));
        }
        final long now = nanoTime.getAsLong();
        startedAt.compareAndSet(Long.MIN_VALUE, now);
        final long elapsed = Math.max(0, (now - startedAt.get()) / 1_000_000_000L);
        String kind = mode.equals("sparse") || mode.equals("bunched") ? mode : "grouped";
        Layout layout = layouts.compute(route.routeId(), (id, previous) -> previous == null ? new Layout(kind, 0)
            : previous.kind().equals(kind) ? previous : new Layout(kind, previous.generation() + 1));
        String vehicleRun = layout.generation() == 0 ? runId : UUID.nameUUIDFromBytes(
            (runId + route.routeId() + layout.generation()).getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .toString().substring(0, 8);
        final int downFirst = route.turnSequence() + (configuration.vehiclesPerDirection() == 1 ? 0 : 1);
        final int downCount = route.stops().size() - downFirst + 1;
        List<Map<String, Object>> vehicles = new ArrayList<>();
        if (!mode.equals("down-only")) {
            addDirection(vehicles, route, route.turnSequence(), 1, "U", mode, elapsed, vehicleRun);
        }
        if (!mode.equals("up-only")) {
            addDirection(vehicles, route, downCount, downFirst, "D", mode, elapsed, vehicleRun);
        }
        return vehicles.isEmpty() ? new Reply(200, envelope(4, null))
            : new Reply(200, envelope(0, Map.of("busLocationList", vehicles)));
    }

    private void addDirection(List<Map<String, Object>> vehicles, Route route, final int count,
                              final int firstSequence, String direction, String mode, final long elapsed, String vehicleRun) {
        final int length = configuration.vehiclesPerDirection() == 1
            ? Math.max(route.turnSequence(), route.stops().size() - route.turnSequence() + 1) : count;
        final long period = (long) length * configuration.stepSeconds() + configuration.idleSeconds();
        final int fleet = mode.equals("single") ? 1 : configuration.vehiclesPerDirection();
        final int groups = (fleet + 2) / 3;
        for (int slot = 0; slot < fleet; slot++) {
            final long offset = (mode.equals("sparse") ? period * slot / fleet
                : mode.equals("bunched") ? (long) slot * configuration.stepSeconds() / 2
                : period * (slot / 3) / groups + (long) (slot % 3) * 3 * configuration.stepSeconds()
                    + (long) (slot % 3) * configuration.stepSeconds() / 3) % period;
            final long shifted = elapsed + offset;
            final long trip = shifted / period * configuration.vehiclesPerDirection() + slot;
            final int step = (int) ((shifted % period) / configuration.stepSeconds());
            final long withinStep = shifted % configuration.stepSeconds();
            final int phase = phaseOf(mode, withinStep, step == count - 1);
            if (step < count) {
                vehicles.add(vehicle(route, firstSequence + step, step, trip, slot, phase, direction, mode, vehicleRun));
            }
        }
    }

    private int phaseOf(String mode, final long withinStep, final boolean terminal) {
        return switch (mode) {
            case "arriving" -> 1;
            case "departed" -> 2;
            case "in-transit" -> 0;
            case "unreadable" -> 99;
            default -> configuration.vehiclesPerDirection() == 1 ? terminal ? 0 : 2
                : withinStep * 3 < configuration.stepSeconds() ? 1
                : withinStep * 3 < configuration.stepSeconds() * 2L ? 2 : 0;
        };
    }

    private Map<String, Object> vehicle(Route route, final int sequence,
                                      final int step, final long cycle, final int slot, final int phase,
                                      String direction, String mode, String vehicleRun) {
        Stop stop = route.stops().get(sequence - 1);
        Integer remaining = switch (mode) {
            case "unknown-seat" -> -1;
            case "missing-seat" -> null;
            case "full" -> 0;
            case "few-seats" -> 3;
            case "mixed" -> switch (slot % 6) {
                case 0 -> 32;
                case 1 -> 3;
                case 2 -> 0;
                case 3 -> -1;
                case 4 -> null;
                default -> 16;
            };
            default -> configuration.vehiclesPerDirection() == 1
                ? step == 0 ? 40 : Math.floorMod(40 - step * 7, 41)
                : Math.floorMod(44 - step * 2 - (slot % 3) * 11, 45);
        };
        Map<String, Object> value = new LinkedHashMap<>();
        if (!mode.equals("anonymous")) {
            value.put("plateNo", "LOCAL-" + route.displayName()
                + (configuration.vehiclesPerDirection() == 1 ? "" : "-" + slot) + "-" + direction);
            value.put("vehId", "L" + vehicleRun + "-" + route.routeId() + "-" + cycle + direction);
        }
        value.put("lowPlate", 2);
        value.put("routeId", route.routeId());
        value.put("routeTypeCd", 11);
        value.put("stationId", stop.stationId());
        value.put("stationSeq", sequence);
        value.put("stateCd", phase);
        if (remaining != null) { value.put("remainSeatCnt", remaining); }
        value.put("crowded", remaining == null || remaining < 0 ? 0 : remaining == 0 ? 4 : remaining < 10 ? 3 : remaining < 25 ? 2 : 1);
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
    private record Layout(String kind, int generation) { }
    public record Dataset(String version, List<Route> routes) { }
    public record Configuration(String version, int stepSeconds, int idleSeconds, int vehiclesPerDirection) {
        public Configuration(String version, final int stepSeconds, final int idleSeconds) {
            this(version, stepSeconds, idleSeconds, 1);
        }
    }
    public record Route(String routeId, String displayName, String startStopName, String endStopName,
                        int turnSequence, String contentDigest, Timetable timetable, List<Stop> stops) { }
    public record Timetable(String upFirstDepartureTime, String upLastDepartureTime,
                            String downFirstDepartureTime, String downLastDepartureTime) { }
    public record Stop(int sequence, String stationId, String name, String direction,
                       boolean boardingAllowed, double x, double y) { }
}
