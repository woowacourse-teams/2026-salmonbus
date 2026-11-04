package com.gustler.backend.routecatalog.application;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Reference;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource.Contribution;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** HTTP 대기 중에는 DB 트랜잭션을 열지 않고 정상 변경만 발행한다. */
@Component
public class RouteGeometryRefresher implements RefreshRouteGeometry {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RouteGeometryRefresher.class);
    private final RouteSourceArchive archive;
    private final RouteGeometryStore store;
    private final RouteGeometrySource source;
    private final ApiCallQuota quota;
    private final List<SupplementaryRouteGeometrySource> supplementary;

    public RouteGeometryRefresher(RouteGeometryStore store, RouteGeometrySource source, ApiCallQuota quota) {
        this(store, source, quota, List.of());
    }

    public RouteGeometryRefresher(
            RouteGeometryStore store,
            RouteGeometrySource source,
            ApiCallQuota quota,
            List<SupplementaryRouteGeometrySource> supplementary) {
        this(store,source,quota,supplementary,Optional.empty());
    }

    @Autowired
    public RouteGeometryRefresher(RouteGeometryStore store, RouteGeometrySource source,
            ApiCallQuota quota, List<SupplementaryRouteGeometrySource> supplementary,
            Optional<RouteSourceArchive> archive) {
        this.archive=archive.orElse(RouteSourceArchive.NONE);
        this.supplementary = List.copyOf(supplementary);
        this.store = store;
        this.source = source;
        this.quota = quota;
    }

    @Override
    public Outcome refresh(String routeId, OffsetDateTime requestedAt) {
        long started = System.nanoTime();
        Outcome outcome = null;
        try {
            outcome = refreshOnce(routeId, requestedAt);
            return outcome;
        } finally {
            String publicRouteId = routeId != null && routeId.matches("[0-9]{9}") ? routeId : "INVALID";
            LOG.info("event=route_geometry_acquisition_timing routeId={} outcome={} durationMs={}",
                    publicRouteId, outcome == null ? "FAILED" : outcome,
                    (System.nanoTime() - started) / 1_000_000);
        }
    }

    private Outcome refreshOnce(String routeId, OffsetDateTime requestedAt) {
        Optional<Reference> reference = store.current(routeId);
        if (reference.isEmpty()) {
            return Outcome.NO_REFERENCE;
        }
        boolean allowed = quota.reserveRouteCatalog(requestedAt, 1);
        Optional<Read> primary = Optional.empty();
        List<RouteSourceReceipt> receipts = new ArrayList<>();
        List<String> reusedManifests = new ArrayList<>();
        if (allowed) {
            try {
                if (archive == RouteSourceArchive.NONE) {
                    primary = source.read(reference.get().sourceRouteId());
                } else {
                    var captured = source.capture(reference.get().sourceRouteId());
                    primary = captured.read();
                    receipts.addAll(captured.receipts());
                }
            } catch (RuntimeException failed) {
                /* HTTP 예외 본문·키를 전파하지 않는다. */
            }
        }
        if (receipts.isEmpty()) receipts.add(receipt("GBIS_API",reference.get().sourceRouteId(),
                primary.isPresent() ? RouteSourceReceipt.Status.UNRECORDED_LEGACY
                    : allowed ? RouteSourceReceipt.Status.UNAVAILABLE : RouteSourceReceipt.Status.NO_QUOTA,requestedAt));
        List<Contribution> contributions = new ArrayList<>();
        for (var provider : supplementary) {
            try {
                contributions.add(provider.read(reference.get().sourceRouteId()));
            } catch (RuntimeException failed) {
                contributions.add(Contribution.unavailable(provider.id()));
            }
        }
        for (var contribution : contributions) {
            receipts.addAll(contribution.receipts());
            reusedManifests.addAll(contribution.reusedManifestIds());
            if (contribution.receipts().isEmpty() && contribution.reusedManifestIds().isEmpty()) {
                var status = switch(contribution.source().status()) {
                    case "NOT_CONFIGURED" -> RouteSourceReceipt.Status.NOT_CONFIGURED;
                    case "STALE" -> RouteSourceReceipt.Status.STALE;
                    case "AVAILABLE" -> RouteSourceReceipt.Status.UNRECORDED_LEGACY;
                    default -> RouteSourceReceipt.Status.UNAVAILABLE;
                };
                receipts.add(receipt(contribution.source().id(),reference.get().sourceRouteId(),status,requestedAt));
            }
        }
        String acquisitionManifest = archive.record("ROUTE_GEOMETRY_ACQUISITION",receipts,reusedManifests);
        Optional<Read> read = primary;
        String selectedSource = "GBIS_API";
        if (read.isEmpty()) {
            for (var contribution : contributions) {
                if (contribution.shape().isPresent()) {
                    read = contribution.shape();
                    selectedSource = contribution.source().id();
                    break;
                }
            }
        }
        if (read.isEmpty()) {
            return allowed ? Outcome.SOURCE_UNAVAILABLE : Outcome.NO_QUOTA;
        }
        if (!supplementary.isEmpty()) {
            List<RouteShapeEvidence.Source> sources = new ArrayList<>();
            sources.add(
                    new RouteShapeEvidence.Source(
                            "GBIS_API",
                            primary.isPresent()
                                    ? "AVAILABLE"
                                    : allowed ? "UNAVAILABLE" : "NO_QUOTA",
                            primary.map(value -> value.shape().digest()).orElse(null)));
            contributions.forEach(value -> sources.add(value.source()));
            var nodes = contributions.stream().flatMap(value -> value.nodes().stream()).toList();
            java.util.Map<
                            String,
                            List<com.gustler.backend.routegeometry.api.RouteDisplayInput.Point>>
                    grid = new java.util.HashMap<>();
            for (var node : nodes) {
                grid.computeIfAbsent(cell(node.x(), node.y()), ignored -> new ArrayList<>())
                        .add(node);
            }
            var typed =
                    contributions.stream()
                            .flatMap(value -> value.webPoints().stream())
                            .map(
                                    value ->
                                            new RouteShapeEvidence.TypedPoint(
                                                    value.point(),
                                                    value.type(),
                                                    value.direction(),
                                                    nearNode(value.point(), grid)))
                            .toList();
            Read selected = read.orElseThrow();
            read =
                    Optional.of(
                            new Read(
                                    selected.shape(),
                                    selected.receivedAt(),
                                    new RouteShapeEvidence(selectedSource, sources, typed)));
        }
        Read selected = read.orElseThrow();
        read = Optional.of(new Read(selected.shape(),selected.receivedAt(),selected.evidence(),acquisitionManifest));
        return switch (store.publish(reference.get(), read.get())) {
            case UPDATED -> Outcome.UPDATED;
            case UNCHANGED -> Outcome.UNCHANGED;
            case REFERENCE_CHANGED -> Outcome.REFERENCE_CHANGED;
        };
    }

    private static RouteSourceReceipt receipt(String id,String route,RouteSourceReceipt.Status status,OffsetDateTime at) {
        return new RouteSourceReceipt(id,"ROUTE_ACQUISITION",route.matches("[0-9]{1,20}")?route:"UNSPECIFIED",status,
                null,at.toInstant(),"NOT_PROVIDED",RouteSourceReceipt.CoordinateSystem.UNKNOWN,null);
    }

    private static String cell(double x, double y) {
        return (int) Math.floor(x * 1000) + ":" + (int) Math.floor(y * 1000);
    }

    private static boolean nearNode(
            com.gustler.backend.routegeometry.api.RouteDisplayInput.Point point,
            java.util.Map<
                            String,
                            List<com.gustler.backend.routegeometry.api.RouteDisplayInput.Point>>
                    grid) {
        for (int x = -1; x <= 1; x++)
            for (int y = -1; y <= 1; y++) {
                for (var node :
                        grid.getOrDefault(
                                cell(point.x() + x * 0.001, point.y() + y * 0.001), List.of())) {
                    if (Math.hypot(
                                            (node.x() - point.x())
                                                    * Math.cos(Math.toRadians(point.y())),
                                            node.y() - point.y())
                                    * 111320
                            <= 8) return true;
                }
            }
        return false;
    }
}
