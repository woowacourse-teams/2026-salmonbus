package com.gustler.backend.migration.archive;

import com.gustler.backend.migration.CanonicalJson;
import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.Sha256;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import tools.jackson.databind.JsonNode;

public record RouteRoster(
    String version,
    LocalDate effectiveFrom,
    LocalDate effectiveThrough,
    String modelRoute,
    String sourceRouteId,
    Integer turnSequence,
    Map<Integer, Station> stations,
    String rosterSha256
) {

    private static final String TRANSIT_ONLY_PREFIX = "277";
    private static final Set<String> MANIFEST_FIELDS = Set.of(
        "effectiveFrom", "effectiveThrough", "modelRoute", "rosterSha256",
        "sourceRouteId", "stations", "turnSequence", "version");
    private static final Set<String> STATION_FIELDS = Set.of(
        "boardingAllowed", "stopId", "stopOrder");

    public RouteRoster {
        // Map.copyOf 의 순회 순서는 JVM 마다 달라진다(ImmutableCollections.SALT).
        // manifest 는 이 순서를 그대로 적으므로 정렬을 유지해야 재현된다.
        stations = Collections.unmodifiableMap(new TreeMap<>(stations));
        if (version == null || version.isBlank() || effectiveFrom == null
            || modelRoute == null || modelRoute.isBlank()
            || sourceRouteId == null || sourceRouteId.isBlank()
            || stations.isEmpty()) {
            throw new MigrationException("ROUTE_ROSTER_INVALID");
        }
        if (effectiveThrough != null && effectiveThrough.isBefore(effectiveFrom)) {
            throw new MigrationException("ROUTE_ROSTER_EFFECTIVE_RANGE_INVALID");
        }
        if (turnSequence != null && !stations.containsKey(turnSequence)) {
            throw new MigrationException("ROUTE_ROSTER_TURN_SEQUENCE_INVALID");
        }
        for (Map.Entry<Integer, Station> entry : stations.entrySet()) {
            if (entry.getKey() != entry.getValue().stopOrder()) {
                throw new MigrationException("ROUTE_ROSTER_STATION_ORDER_INVALID");
            }
        }
        String calculated = digestOf(version, effectiveFrom, effectiveThrough, modelRoute,
            sourceRouteId, turnSequence, stations);
        if (rosterSha256 == null) {
            rosterSha256 = calculated;
        } else if (!calculated.equals(rosterSha256)) {
            throw new MigrationException("ROUTE_ROSTER_DIGEST_MISMATCH");
        }
    }

    public static List<RouteRoster> loadProtocol(
        JsonNode protocol
    ) {
        JsonNode routeReference = ArchiveJson.object(protocol, "route_reference");
        JsonNode versions = ArchiveJson.array(routeReference, "versions");
        List<RouteRoster> rosters = new ArrayList<>();
        for (JsonNode version : versions) {
            String versionId = ArchiveJson.text(version, "route_reference_version_id");
            LocalDate effectiveFrom = LocalDate.parse(ArchiveJson.text(version, "effective_from"));
            String throughText = ArchiveJson.nullableText(version, "effective_through");
            LocalDate effectiveThrough = throughText == null ? null : LocalDate.parse(throughText);
            JsonNode routes = ArchiveJson.object(version, "routes");
            for (Map.Entry<String, JsonNode> route : routes.properties()) {
                JsonNode routeNode = route.getValue();
                String sourceRouteId = ArchiveJson.text(routeNode, "route_id");
                Integer turnSequence = ArchiveJson.nullableInteger(routeNode, "turn_station_seq");
                JsonNode stationNodes = ArchiveJson.object(routeNode, "stations");
                Map<Integer, Station> stations = new TreeMap<>();
                for (Map.Entry<String, JsonNode> station : stationNodes.properties()) {
                    int order;
                    try {
                        order = Integer.parseInt(station.getKey());
                    } catch (NumberFormatException e) {
                        throw new MigrationException("ROUTE_ROSTER_STATION_ORDER_INVALID", e);
                    }
                    if (!station.getValue().isString()) {
                        throw new MigrationException("ROUTE_ROSTER_STATION_ID_INVALID");
                    }
                    String stopId = station.getValue().stringValue();
                    if (stations.put(order, new Station(order, stopId, allowsBoarding(stopId))) != null) {
                        throw new MigrationException("ROUTE_ROSTER_DUPLICATE_STATION_ORDER");
                    }
                }
                rosters.add(new RouteRoster(
                    versionId, effectiveFrom, effectiveThrough, route.getKey(), sourceRouteId,
                    turnSequence, stations, null));
            }
        }
        rosters.sort(Comparator.comparing(RouteRoster::effectiveFrom)
            .thenComparing(RouteRoster::modelRoute));
        if (rosters.isEmpty()) {
            throw new MigrationException("ROUTE_ROSTER_EMPTY");
        }
        return List.copyOf(rosters);
    }

    public static RouteRoster forObservation(
        List<RouteRoster> rosters,
        String modelRoute,
        Instant observedAt
    ) {
        LocalDate kstDate = observedAt.atZone(ZoneId.of("Asia/Seoul")).toLocalDate();
        List<RouteRoster> matching = rosters.stream()
            .filter(roster -> roster.modelRoute.equals(modelRoute))
            .filter(roster -> !kstDate.isBefore(roster.effectiveFrom))
            .filter(roster -> roster.effectiveThrough == null || !kstDate.isAfter(roster.effectiveThrough))
            .toList();
        if (matching.size() != 1) {
            throw new MigrationException("ROUTE_ROSTER_NOT_UNIQUE_FOR_OBSERVATION");
        }
        return matching.getFirst();
    }

    public void requireStation(
        int stopOrder,
        String stopId
    ) {
        Station expected = stations.get(stopOrder);
        if (expected == null || !expected.stopId.equals(stopId)) {
            throw new MigrationException("ROUTE_ROSTER_STATION_MISMATCH");
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("version", version);
        value.put("effectiveFrom", effectiveFrom.toString());
        value.put("effectiveThrough", effectiveThrough == null ? null : effectiveThrough.toString());
        value.put("modelRoute", modelRoute);
        value.put("sourceRouteId", sourceRouteId);
        value.put("turnSequence", turnSequence);
        value.put("stations", stations.values().stream().map(Station::toMap).toList());
        value.put("rosterSha256", rosterSha256);
        return value;
    }

    public static RouteRoster fromManifest(
        JsonNode node
    ) {
        ArchiveJson.requireObjectWithFields(node, MANIFEST_FIELDS, "ROUTE_ROSTER_MANIFEST_FIELDS_INVALID");
        Map<Integer, Station> stations = new TreeMap<>();
        for (JsonNode stationNode : ArchiveJson.array(node, "stations")) {
            Station station = Station.from(stationNode);
            if (stations.put(station.stopOrder, station) != null) {
                throw new MigrationException("ROUTE_ROSTER_DUPLICATE_STATION_ORDER");
            }
        }
        String through = ArchiveJson.nullableText(node, "effectiveThrough");
        return new RouteRoster(
            ArchiveJson.text(node, "version"),
            LocalDate.parse(ArchiveJson.text(node, "effectiveFrom")),
            through == null ? null : LocalDate.parse(through),
            ArchiveJson.text(node, "modelRoute"),
            ArchiveJson.text(node, "sourceRouteId"),
            ArchiveJson.nullableInteger(node, "turnSequence"),
            stations,
            ArchiveJson.text(node, "rosterSha256"));
    }

    public static String databaseDigest(
        String modelRoute,
        String sourceRouteId,
        Integer turnSequence,
        Map<Integer, Station> stations,
        String referenceVersion,
        LocalDate effectiveFrom,
        LocalDate effectiveThrough
    ) {
        return digestOf(referenceVersion, effectiveFrom, effectiveThrough, modelRoute,
            sourceRouteId, turnSequence, stations);
    }

    private static String digestOf(
        String version,
        LocalDate effectiveFrom,
        LocalDate effectiveThrough,
        String modelRoute,
        String sourceRouteId,
        Integer turnSequence,
        Map<Integer, Station> stations
    ) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("version", version);
        identity.put("effectiveFrom", effectiveFrom.toString());
        identity.put("effectiveThrough", effectiveThrough == null ? null : effectiveThrough.toString());
        identity.put("modelRoute", modelRoute);
        identity.put("sourceRouteId", sourceRouteId);
        identity.put("turnSequence", turnSequence);
        identity.put("stations", new TreeMap<>(stations).values().stream().map(Station::toMap).toList());
        return Sha256.of(CanonicalJson.bytesOf(identity));
    }

    private static boolean allowsBoarding(
        String stopId
    ) {
        return !stopId.startsWith(TRANSIT_ONLY_PREFIX);
    }

    @Override
    public String toString() {
        return "RouteRoster[version=" + version + ", modelRoute=" + modelRoute + ", stations="
            + stations.size() + "]";
    }

    public record Station(
        int stopOrder,
        String stopId,
        boolean boardingAllowed
    ) {

        public Station {
            if (stopOrder < 1 || stopId == null || stopId.isBlank() || stopId.length() > 20) {
                throw new MigrationException("ROUTE_ROSTER_STATION_INVALID");
            }
            if (boardingAllowed != allowsBoarding(stopId)) {
                throw new MigrationException("ROUTE_ROSTER_BOARDING_POLICY_MISMATCH");
            }
        }

        Map<String, Object> toMap() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("stopOrder", stopOrder);
            value.put("stopId", stopId);
            value.put("boardingAllowed", boardingAllowed);
            return value;
        }

        static Station from(
            JsonNode node
        ) {
            ArchiveJson.requireObjectWithFields(node, STATION_FIELDS, "ROUTE_ROSTER_STATION_FIELDS_INVALID");
            return new Station(
                ArchiveJson.integer(node, "stopOrder"),
                ArchiveJson.text(node, "stopId"),
                ArchiveJson.bool(node, "boardingAllowed"));
        }
    }
}
