package com.gustler.backend.api.route.application;

import com.gustler.backend.routegeometry.engine.api.AutomaticRouteDisplay;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;

import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.Source;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;


/** 현재 원본 일치 수동 형상을 우선하고, 없거나 손상되면 결정적 자동 대체를 선택한다. */
@Component
public class RouteDisplaySelector {
    private static final Logger LOGGER = LoggerFactory.getLogger(RouteDisplaySelector.class);
    private final RouteDisplayRepository repository;
    private final ObjectMapper json;
    private final boolean enabled;
    private final java.time.Clock clock;


    public RouteDisplaySelector(
            RouteDisplayRepository repository, ObjectMapper json, boolean enabled) {
        this(repository, json, enabled, java.util.Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public RouteDisplaySelector(
            RouteDisplayRepository repository,
            ObjectMapper json,
            @Value("${route-geometry.presentation.enabled:false}") boolean enabled,
            java.util.Optional<java.time.Clock> clock) {
        this.repository = repository;
        this.json = json;
        this.enabled = enabled;
        this.clock = clock.orElseGet(java.time.Clock::systemUTC);
    }

    public RouteGeometry enrich(RouteGeometry geometry) {
        if (!enabled) return geometry;
        if (geometry.shape() == null) {
            var recovery=repository.findSourceRecovery(geometry.routeId(),geometry.referenceVersionId());
            if(recovery.isPresent() && !recovery.get().canProgress()
                    && !recovery.get().status().equals("SUCCEEDED") && !recovery.get().status().equals("SUPERSEDED"))
                return geometry.withDisplay(new RouteDisplayGeometry(null,Map.of(),Map.of(),
                    "source-exhausted/"+geometry.referenceVersionId(),Source.AUTOMATIC_FALLBACK,List.of(),
                    new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.WITHHELD,
                        List.of(new RouteDisplayGeometry.QualityIssue("SOURCE_RECOVERY_EXHAUSTED",null,null,null)))));
            return geometry;
        }
        final RouteDisplayInput input;
        try {
            input = input(geometry);
        } catch (IllegalArgumentException invalid) {
            LOGGER.warn(
                    "event=route_display_source_rejected routeId={} reason=INVALID_INPUT",
                    geometry.routeId());
            return geometry.withDisplay(new RouteDisplayGeometry(null, Map.of(), Map.of(),
                    "invalid-source/" + geometry.shape().id(), Source.AUTOMATIC_FALLBACK,
                    geometry.shape().evidence().at(clock.instant()).sources(),
                    new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.WITHHELD,
                            List.of(new RouteDisplayGeometry.QualityIssue("INVALID_SOURCE_INPUT", null, null, null)))));
        }
        String digest = input.digest();
        var evidence = geometry.shape().evidence().at(clock.instant());
        var manual = repository.findManual(geometry.routeId(), digest);
        if (manual.isPresent()) {
            try {
                ManualRouteDisplay document =
                        json.readValue(manual.get().document(), ManualRouteDisplay.class);
                document.validateFor(input);
                java.util.Optional<RouteDisplayGeometry> source;
                try { source=storedAutomatic(provenanceInput(geometry,digest),digest,evidence); }
                catch(RuntimeException invalidProvenance) { source=java.util.Optional.empty(); }
                String revision = manual.get().revision() + source
                        .map(value -> "/source/" + RouteDisplayGeneration.sha256(value.revision()))
                        .orElse("/source/pending");
                return geometry.withDisplay(convert(document, revision,
                        source.map(RouteDisplayGeometry::sourcePaths).orElse(null)));
            } catch (RuntimeException invalid) {
                LOGGER.warn(
                        "event=route_display_manual_rejected routeId={} reason=INVALID_DOCUMENT",
                        geometry.routeId());
            }
        }
        try { geometry=provenanceInput(geometry,digest); }
        catch(RuntimeException invalidProvenance) {
            return geometry.withDisplay(new RouteDisplayGeometry(null,Map.of(),Map.of(),
                "invalid-provenance/"+geometry.shape().id(),Source.AUTOMATIC_FALLBACK,evidence.sources(),
                new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.WITHHELD,
                    List.of(new RouteDisplayGeometry.QualityIssue("INVALID_SOURCE_PROVENANCE",null,null,null)))));
        }
        var terminalKeys = new java.util.HashSet<String>();
        RouteDisplayGeometry rejected = null;
        RouteDisplayGeometry fallback = null;
        List<String> algorithms = List.of(AutomaticRouteDisplay.REVISION, RouteDisplayGeneration.SOURCE_REVISION);
        String manifestId = geometry.shape().sourceManifestId();
        List<String> keys = algorithms.stream().map(algorithm ->
                RouteDisplayGeneration.key(digest,evidence.revision(),algorithm,manifestId)).toList();
        for (int i=0; i<algorithms.size(); i++) {
            var read = readAutomatic(geometry,digest,evidence,algorithms.get(i));
            if (read.found()) terminalKeys.add(keys.get(i));
            if (read.geometry().isEmpty()) continue;
            var value = read.geometry().get();
            if (value.quality().status() != RouteDisplayGeometry.QualityStatus.WITHHELD) {
                if(i==0)return geometry.withDisplay(value);
                fallback=value;
                continue;
            }
            if (rejected == null) rejected = value;
        }
        if(fallback!=null && terminalKeys.contains(keys.getFirst()))return geometry.withDisplay(fallback);
        var states = repository.findGenerationStates(geometry.routeId(),digest,evidence.revision(),keys);
        var improved=states.get(keys.getFirst());
        boolean initialAttempt=!terminalKeys.contains(keys.getFirst())
                && (improved==null || improved.canProgress() && improved.initialAttempt());
        if(fallback!=null && !initialAttempt)return geometry.withDisplay(fallback);
        boolean pending = initialAttempt || keys.stream().anyMatch(key -> !terminalKeys.contains(key)
                && (!states.containsKey(key) || states.get(key).canProgress()));
        if(!pending)pending=repository.findSourceRecovery(geometry.routeId(),geometry.referenceVersionId())
                .map(RouteDisplayRepository.GenerationState::canProgress).orElse(false);
        if (!pending && rejected != null) return geometry.withDisplay(rejected);
        String phase = pending ? "pending/" : "unavailable/";
        String reason = pending ? "GENERATION_PENDING" : "GENERATION_FAILED";
        return geometry.withDisplay(new RouteDisplayGeometry(null, Map.of(), Map.of(),
                phase + keys.getFirst(), Source.AUTOMATIC_FALLBACK,evidence.sources(),
                new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.WITHHELD,
                        List.of(new RouteDisplayGeometry.QualityIssue(reason,null,null,null))),
                rejected == null ? null : rejected.sourcePaths()));
    }

    private RouteGeometry provenanceInput(RouteGeometry g,String digest) {
        if(g.shape().sourceManifestId()==null) return g;
        var m=repository.findSourceManifest(g.shape().sourceManifestId())
            .orElseThrow(() -> new IllegalStateException("Input provenance missing"));
        if(m.referenceVersionId()==g.referenceVersionId() && m.inputDigest().equals(digest)
                && m.evidenceRevision().equals(g.shape().evidence().revision())) return g;
        // A catalog coordinate refresh can precede geometry refresh. Do not relabel the old receipt as new input.
        var shape=g.shape();
        return new RouteGeometry(g.routeId(),g.referenceVersionId(),g.name(),g.startPoint(),g.endPoint(),g.turnSequence(),g.busStops(),
            new RouteGeometry.Shape(shape.id(),shape.receivedAt(),shape.points(),shape.evidence(),null,null,shape.sourceManifestId()));
    }

    private record ArtifactRead(boolean found, java.util.Optional<RouteDisplayGeometry> geometry) {}

    /** Manual diagnostics may retain an independently verified source path from a withheld result. */
    private java.util.Optional<RouteDisplayGeometry> storedAutomatic(RouteGeometry geometry, String digest,
            com.gustler.backend.routegeometry.api.RouteShapeEvidence evidence) {
        for (String algorithm : List.of(AutomaticRouteDisplay.REVISION,RouteDisplayGeneration.SOURCE_REVISION)) {
            var result=readAutomatic(geometry,digest,evidence,algorithm);
            if(result.geometry().isPresent()) return result.geometry();
        }
        return java.util.Optional.empty();
    }

    private ArtifactRead readAutomatic(RouteGeometry geometry, String digest,
            com.gustler.backend.routegeometry.api.RouteShapeEvidence evidence, String algorithm) {
        String key=RouteDisplayGeneration.key(digest,evidence.revision(),algorithm,geometry.shape().sourceManifestId());
        var stored=repository.findAutomatic(key,geometry.routeId(),digest,evidence.revision(),algorithm);
        if(stored.isEmpty()) return new ArtifactRead(false,java.util.Optional.empty());
        try {
            if(!RouteDisplayGeneration.sha256(stored.get().document()).equals(stored.get().sha256()))
                throw new IllegalArgumentException("Artifact checksum mismatch");
            var document=json.readValue(stored.get().document(),RouteDisplayGeometry.class);
            validateAutomatic(document,geometry,digest,evidence.revision());
            validateProof(document,geometry,stored.get().sourceManifestId());
            if(!document.evidence().equals(evidence.sources())
                    || document.quality().issues().stream().anyMatch(issue -> issue.code().equals("GENERATION_PENDING"))
                    || (algorithm.equals(RouteDisplayGeneration.SOURCE_REVISION)
                        && document.quality().status()!=RouteDisplayGeometry.QualityStatus.SOURCE_ONLY
                        && document.quality().status()!=RouteDisplayGeometry.QualityStatus.WITHHELD))
                throw new IllegalArgumentException("Artifact evidence or source-only grade differs");
            var value=new RouteDisplayGeometry(document.paths(),document.connections(),document.stationPositions(),
                    document.revision()+"/stored/"+stored.get().sha256(),document.source(),document.evidence(),
                    document.quality(),document.sourcePaths(),document.accuracyProof());
            return new ArtifactRead(true,java.util.Optional.of(value));
        } catch(RuntimeException invalid) {
            LOGGER.warn("event=route_display_automatic_rejected routeId={} reason=INVALID_DOCUMENT",geometry.routeId());
            return new ArtifactRead(true,java.util.Optional.empty());
        }
    }

    private static void validateProof(RouteDisplayGeometry document,RouteGeometry source,String consumedManifest) {
        var proof=document.accuracyProof();
        if(proof==null) return; // Pre-proof artifacts remain explicitly UNEVALUATED in the public contract.
        if(!com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.REVISION.equals(proof.verifierRevision())
                || proof.grade()!=com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.Grade.UNEVALUATED
                || !input(source).digest().equals(proof.inputDigest())
                || !com.gustler.backend.routegeometry.engine.api.accuracy.GeometryPayloadFingerprint.of(document).equals(proof.geometryDigest())
                || !java.util.Objects.equals(consumedManifest,proof.sourceManifestId()))
            throw new IllegalArgumentException("Unsupported or unbound stored accuracy proof");
    }

    public static RouteDisplayInput input(RouteGeometry geometry) {
        return RouteDisplayGeneration.input(geometry);
    }

    /** Linear structural checks only; topology and stop projection belong to the worker. */
    private static void validateAutomatic(RouteDisplayGeometry value, RouteGeometry geometry,
            String digest, String evidence) {
        validateSourcePaths(value.sourcePaths(), geometry.points());
        if (value.source() != Source.AUTOMATIC_FALLBACK || value.quality() == null || value.quality().status() == null
                || !value.stationPositions().isEmpty()
                || !value.revision().equals(AutomaticRouteDisplay.REVISION + "/" + digest + "/"
                    + evidence + "/" + value.quality().status()))
            throw new IllegalArgumentException("Unbound automatic artifact");
        if (value.paths() == null) {
            if (!value.connections().isEmpty()
                    || (value.quality().status() != RouteDisplayGeometry.QualityStatus.WITHHELD
                        && value.quality().status() != RouteDisplayGeometry.QualityStatus.SOURCE_ONLY))
                throw new IllegalArgumentException("Missing artifact paths");
            return;
        }
        if (value.quality().status() == RouteDisplayGeometry.QualityStatus.WITHHELD)
            throw new IllegalArgumentException("Withheld artifact exposes paths");
        if (!value.paths().up().points().getLast().equals(value.paths().down().points().getFirst())
                || !com.gustler.backend.routegeometry.engine.api.RouteStopIntervals
                        .evaluate(geometry.busStops(),value.paths(),value.connections()).usable())
            throw new IllegalArgumentException("Invalid selected visit bundle");
        Map<String,Double> previous = new HashMap<>();
        var visits = new java.util.HashSet<Integer>();
        for (var stop : geometry.busStops()) {
            visits.add(stop.stopSequence());
            var connection = value.connections().get(stop.stopSequence());
            if (connection == null) {
                if (stop.point() != null) throw new IllegalArgumentException("Missing visit connection");
                continue;
            }
            if (stop.point() == null) throw new IllegalArgumentException("Connection for visit without source coordinate");
            var points = (stop.direction().equals("UP") ? value.paths().up() : value.paths().down()).points();
            int index = connection.segmentIndex();
            if (index >= points.size() - 1) throw new IllegalArgumentException("Invalid segment index");
            var from = points.get(index); var to = points.get(index + 1);
            double t = connection.segmentFraction();
            if (Math.abs(from.x() + (to.x()-from.x())*t - connection.point().x()) > 1e-9
                    || Math.abs(from.y() + (to.y()-from.y())*t - connection.point().y()) > 1e-9)
                throw new IllegalArgumentException("Connection point differs from segment");
            double progress = index + t;
            if (progress + 1e-9 < previous.getOrDefault(stop.direction(), -1.0))
                throw new IllegalArgumentException("Visit order changed");
            previous.put(stop.direction(), progress);
        }
        if (!visits.containsAll(value.connections().keySet()))
            throw new IllegalArgumentException("Unknown visit connection");
    }

    /** A split may insert one interpolated seam, but must preserve every original point. */
    private static void validateSourcePaths(RoutePaths paths, List<Point> raw) {
        if (paths == null) return;
        var up = paths.up().points();
        var down = paths.down().points();
        Point boundary = up.getLast();
        if (!boundary.equals(down.getFirst()))
            throw new IllegalArgumentException("Source paths do not share the turn boundary");
        // A literal duplicate already present at the source seam belongs once to
        // each direction. Removing it here would invent a source-point deletion.
        var literal = new java.util.ArrayList<Point>(up);
        literal.addAll(down);
        if (literal.equals(raw)) return;
        var joined = new java.util.ArrayList<Point>(up);
        joined.addAll(down.subList(1, down.size()));
        if (joined.equals(raw)) return;
        int seam = up.size() - 1;
        if (joined.size() != raw.size() + 1 || seam <= 0 || seam >= joined.size() - 1)
            throw new IllegalArgumentException("Source paths changed original point sequence");
        joined.remove(seam);
        if (!joined.equals(raw))
            throw new IllegalArgumentException("Source paths changed original point sequence");
        Point a = raw.get(seam - 1), b = raw.get(seam);
        double dx = b.x() - a.x(), dy = b.y() - a.y();
        double fraction = Math.abs(dx) >= Math.abs(dy)
                ? (dx == 0 ? 0 : (boundary.x() - a.x()) / dx)
                : (boundary.y() - a.y()) / dy;
        if (!Double.isFinite(fraction) || fraction < 0 || fraction > 1
                || Math.abs(a.x() + dx * fraction - boundary.x()) > 1e-9
                || Math.abs(a.y() + dy * fraction - boundary.y()) > 1e-9)
            throw new IllegalArgumentException("Source boundary is not on the original segment");
    }

    private static RouteDisplayGeometry convert(ManualRouteDisplay document, String revision, RoutePaths sourcePaths) {
        Map<Integer, ManualRouteDisplay.Connection> connections = new HashMap<>();
        document.connections()
                .forEach((key, value) -> connections.put(Integer.parseInt(key), value));
        Map<Integer, Point> positions = new HashMap<>();
        document.visits()
                .forEach(
                        visit -> {
                            if (visit.displayPoint() != null) {
                                positions.put(
                                        visit.sequence(),
                                        new Point(
                                                visit.displayPoint().x(),
                                                visit.displayPoint().y()));
                            }
                        });
        return new RouteDisplayGeometry(
                new RoutePaths(
                        path(document.displayPaths().get("UP")),
                        path(document.displayPaths().get("DOWN"))),
                connections,
                positions,
                "manual/" + revision,
                Source.MANUAL_REVIEWED, List.of(), null, sourcePaths);
    }

    private static RoutePaths.Path path(ManualRouteDisplay.Path path) {
        List<Point> points =
                path.points().stream().map(point -> new Point(point.x(), point.y())).toList();
        return new RoutePaths.Path(points);
    }
}
