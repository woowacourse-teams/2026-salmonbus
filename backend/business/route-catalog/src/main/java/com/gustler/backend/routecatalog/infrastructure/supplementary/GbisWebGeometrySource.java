package com.gustler.backend.routecatalog.infrastructure.supplementary;

import com.gustler.backend.routecatalog.domain.geometry.*;
import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.CoordinateSystem;
import com.gustler.backend.gbis.api.GbisCapturePolicy;
import com.gustler.backend.gbis.api.GbisResponseCapture;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.*;

/** 인증 API와 별도로 공개 웹 점열·type을 읽는다. 두 경로를 독립 제공기관으로 세지 않는다. */
@Component
@ConditionalOnProperty(name = "route-geometry.multi-source.enabled", havingValue = "true")
public class GbisWebGeometrySource implements SupplementaryRouteGeometrySource {
    private final ObjectMapper json;
    private final Clock clock;
    private final BoundedJsonHttp.Fetch fetch;
    private final URI endpoint;
    static final String DEFAULT_ENDPOINT="https://www.gbis.go.kr/gbis2014/schBusAPI.action";

    public GbisWebGeometrySource(ObjectMapper json, Clock clock) {
        this(json, clock, new BoundedJsonHttp()::getWithMetadata, true);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GbisWebGeometrySource(ObjectMapper json,Clock clock,
            @org.springframework.beans.factory.annotation.Value("${route-geometry.multi-source.gbis-web-endpoint:https://www.gbis.go.kr/gbis2014/schBusAPI.action}") String endpoint) {
        this(json,clock,new BoundedJsonHttp()::getWithMetadata,true,endpoint);
    }

    GbisWebGeometrySource(
            ObjectMapper json, Clock clock, java.util.function.Function<URI, byte[]> fetch) {
        this(json, clock, BoundedJsonHttp.legacy(fetch), true);
    }

    GbisWebGeometrySource(ObjectMapper json, Clock clock, BoundedJsonHttp.Fetch fetch, boolean metadata) {
        this(json,clock,fetch,metadata,DEFAULT_ENDPOINT);
    }
    GbisWebGeometrySource(ObjectMapper json, Clock clock, BoundedJsonHttp.Fetch fetch, boolean metadata,String endpoint) {
        this.endpoint=ConfiguredGeometryEndpoint.parse(endpoint);
        this.json = json;
        this.clock = clock;
        this.fetch = fetch;
    }

    public String id() {
        return "GBIS_WEB";
    }

    public Contribution read(String routeId) {
        if (!routeId.matches("[0-9]{9}"))
            return failure("UNSPECIFIED", Status.INVALID_RESPONSE, null, clock.instant());
        BoundedJsonHttp.Result response;
        try {
            response = fetch.get(URI.create(endpoint+"?cmd=searchMapLineJson&routeId="+routeId));
        } catch (RuntimeException failure) {
            return failure(routeId, Status.TRANSPORT_FAILURE, null, clock.instant());
        }
        var acquired = clock.instant();
        if (response.httpStatus() == null) return failure(routeId, Status.TRANSPORT_FAILURE, null, acquired);
        if (response.httpStatus() != 200) return failure(routeId, Status.HTTP_ERROR, response.httpStatus(), acquired);
        try {
            var parsed = parse(response.body());
            var screened = GbisCapturePolicy.screen(response.body(), "", json);
            Status status = screened.disposition() == GbisResponseCapture.Disposition.AVAILABLE
                    ? Status.SUCCESS : screened.disposition() == GbisResponseCapture.Disposition.SENSITIVE_RESPONSE
                    ? Status.SENSITIVE_RESPONSE : Status.INVALID_RESPONSE;
            var receipt = new RouteSourceReceipt(id(), "MAP_LINE", routeId, status, response.httpStatus(), acquired,
                    "NOT_PROVIDED", CoordinateSystem.WEB_MERCATOR_ASSUMED,
                    status == Status.SUCCESS ? response.body() : null);
            var source = status == Status.SUCCESS ? parsed.source() : new RouteShapeEvidence.Source(id(), "AVAILABLE",
                    sha(json.writeValueAsBytes(parsed.webPoints())));
            return new Contribution(parsed.shape(), source, parsed.webPoints(), parsed.nodes(), List.of(receipt), List.of());
        } catch (RuntimeException failure) {
            return failure(routeId, Status.INVALID_RESPONSE, response.httpStatus(), acquired);
        }
    }

    private Contribution failure(String scope, Status status, Integer httpStatus, java.time.Instant acquired) {
        return new Contribution(Optional.empty(), new RouteShapeEvidence.Source(id(), "UNAVAILABLE", null),
                List.of(), List.of(), List.of(new RouteSourceReceipt(id(), "MAP_LINE", scope, status,
                        httpStatus, acquired, "NOT_PROVIDED", CoordinateSystem.WEB_MERCATOR_ASSUMED, null)), List.of());
    }

    Contribution parse(byte[] bytes) {
        var root = json.readTree(bytes);
        if (!root.path("success").asBoolean(false))
            throw new IllegalArgumentException("WEB_UNAVAILABLE");
        var gg = root.path("result").path("gg");
        List<RouteShapeEvidence.TypedPoint> typed = new ArrayList<>();
        List<RouteShape.Point> visible = new ArrayList<>();
        for (String direction : List.of("UP", "DOWN")) {
            var section = gg.path(direction.equals("UP") ? "upLine" : "downLine");
            var rows = section.path("list");
            if (!rows.isArray()
                    || rows.size() < 2
                    || rows.size() > 100000
                    || section.path("count").asInt(-1) != rows.size())
                throw new IllegalArgumentException("WEB_COUNT");
            int previous = -1, count = 0;
            for (var row : rows) {
                int sequence = Integer.parseInt(row.path("seq").asText());
                int type = Integer.parseInt(row.path("type").asText());
                if (sequence <= previous || type < 1 || type > 3)
                    throw new IllegalArgumentException("WEB_ORDER_OR_TYPE");
                previous = sequence;
                double x = Double.parseDouble(row.path("lon").asText()) / 6378137d;
                double y = Double.parseDouble(row.path("lat").asText()) / 6378137d;
                Point point =
                        new Point(
                                Math.toDegrees(x),
                                Math.toDegrees(2 * Math.atan(Math.exp(y)) - Math.PI / 2));
                typed.add(new RouteShapeEvidence.TypedPoint(point, type, direction, false));
                if (type != 2) {
                    visible.add(new RouteShape.Point(point.x(), point.y()));
                    count++;
                }
            }
            if (count < 2) throw new IllegalArgumentException("WEB_DIRECTION_MISSING");
        }
        RouteShape shape = new RouteShape(visible);
        String digest = sha(bytes);
        return new Contribution(
                Optional.of(new RouteGeometrySource.Read(shape, OffsetDateTime.now(clock))),
                new RouteShapeEvidence.Source(id(), "AVAILABLE", digest),
                typed,
                List.of());
    }

    static String sha(byte[] bytes) {
        try {
            return HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
