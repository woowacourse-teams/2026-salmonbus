package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeometry.*;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;

import java.util.*;

/** 자동 후보는 최종 방문·끝점·교차 검사를 거친다. 요청에서 외부 도로 조회나 임의의 핀 이동을 하지 않는다. */
public final class AutomaticRouteDisplay {
    public static final String REVISION = "automatic-quality-v7";

    private AutomaticRouteDisplay() {}

    private record Evaluation(
            RoutePaths paths,
            Map<Integer, Connection> connections,
            List<QualityIssue> issues,
            boolean blocked) {}

    /** Quality PASSED is structural validity, never proof that a correction occurred. */
    public record BuildDetails(RouteDisplayGeometry geometry,String selectedStage,
                               int appliedRolePointRemovals,int appliedCurves,
                               boolean displayCorrectionApplied,boolean terminalTrimOnly,
                               SourceBoundDisplayCurves.Plan curveCandidate,List<String> curveRejectionReasons) {
        public BuildDetails {curveRejectionReasons=List.copyOf(curveRejectionReasons);}
    }

    public static RouteDisplayGeometry build(RouteGeometry geometry,String inputDigest) {
        return buildDetailed(geometry,inputDigest).geometry();
    }

    public static BuildDetails buildDetailed(RouteGeometry geometry,String inputDigest) {
        RoutePaths original=geometry.paths(),nodeCandidate=original==null?null:EvidenceRoutePaths.build(geometry);
        RouteDisplayGeometry baseline=buildNodeBaseline(geometry,inputDigest,nodeCandidate);
        if(original!=null) {
            RouteDisplayGeometry floor=buildNodeBaseline(geometry,inputDigest,EvidenceRoutePaths.buildSequentialFloor(geometry));
            if(usableBaseline(floor) && (!usableBaseline(baseline)
                    || !RouteDisplayTopology.noNewCrossings(floor.paths(),baseline.paths())
                    || !RouteDisplayTopology.noNewRendererCrossings(floor.paths(),baseline.paths())
                    || !RouteDisplayTopology.noNewContacts(floor.paths(),baseline.paths())
                    || !RouteDisplayTopology.noNewSpike(floor.paths(),baseline.paths()))) {
                baseline=floor;
                nodeCandidate=EvidenceRoutePaths.buildSequentialFloor(geometry);
            }
        }
        boolean eligible=baseline.paths()!=null && baseline.quality().status()!=QualityStatus.SOURCE_ONLY
                && baseline.quality().status()!=QualityStatus.WITHHELD;
        int removed=eligible?Math.max(0,pointCount(original)-pointCount(nodeCandidate)):0;
        boolean terminalOnly=eligible && removed==0 && !original.equals(baseline.paths());
        String stage=!eligible?"SOURCE_FALLBACK":removed>0?"VALIDATED_NODE_BASELINE":terminalOnly?"TERMINAL_TRIM_ONLY":"SOURCE_UNCHANGED_STRUCTURALLY_VALID";
        if(!eligible)return new BuildDetails(baseline,stage,0,0,false,false,null,List.of());
        final SourceBoundDisplayCurves.Plan ordinary;
        final AnchorPreservingDisplayCurves.Result anchored;
        try {
            ordinary=SourceBoundDisplayCurves.build(geometry,baseline.paths(),baseline.connections());
            anchored=AnchorPreservingDisplayCurves.build(geometry,ordinary,baseline.connections());
        }
        catch(IllegalArgumentException unavailable) {
            return new BuildDetails(baseline,stage,removed,0,removed>0,terminalOnly,null,List.of("CURVE_SOURCE_MAPPING_UNAVAILABLE"));
        }
        var curve=anchored.plan();
        if(curve.curveCount()==0)return new BuildDetails(baseline,stage,removed,0,removed>0,terminalOnly,curve,List.of());
        var selected=selectCurveFloor(geometry,inputDigest,baseline,ordinary,curve,anchored.anchoredCornerCount()>0);
        if(selected.geometry()==null)return new BuildDetails(baseline,"VALIDATED_NODE_BASELINE_CURVE_REJECTED",removed,0,removed>0,terminalOnly,curve,selected.rejections());
        curve=selected.plan();
        if(curve.curveCount()==0)return new BuildDetails(baseline,stage,removed,0,removed>0,terminalOnly,curve,List.of());
        return new BuildDetails(selected.geometry(),"SOURCE_BOUND_CURVE",removed,curve.curveCount(),true,false,curve,List.of());
    }

    record CurveSelection(RouteDisplayGeometry geometry,SourceBoundDisplayCurves.Plan plan,List<String> rejections) {}
    private record CurveCheck(RouteDisplayGeometry geometry,List<String> rejections) {}

    /** Each optional layer must preserve the last independently valid whole
     * bundle. An invalid ordinary plan is never promoted merely as a fallback. */
    static CurveSelection selectCurveFloor(RouteGeometry geometry,String inputDigest,RouteDisplayGeometry baseline,
            SourceBoundDisplayCurves.Plan ordinary,SourceBoundDisplayCurves.Plan candidate,boolean anchorsApplied) {
        var checked=checkCurvePlan(geometry,inputDigest,baseline,candidate);
        if(checked.geometry()!=null)return new CurveSelection(checked.geometry(),candidate,List.of());
        if(anchorsApplied) {
            var floor=checkCurvePlan(geometry,inputDigest,baseline,ordinary);
            if(floor.geometry()!=null) {
                var notes=new ArrayList<>(ordinary.notes());
                for(String reason:checked.rejections())notes.add("ANCHOR_FINAL_GATE_REJECTED:"+reason);
                var retained=new SourceBoundDisplayCurves.Plan(ordinary.referenceDomain(),ordinary.paths(),ordinary.correspondences(),
                        ordinary.curvature(),ordinary.curveCount(),ordinary.maximumCertifiedDeviationMetres(),
                        ordinary.continuousControlHullCertified(),ordinary.serializedChordCorrespondenceCertified(),notes);
                return new CurveSelection(floor.geometry(),retained,List.of());
            }
        }
        return new CurveSelection(null,candidate,checked.rejections());
    }

    private static CurveCheck checkCurvePlan(RouteGeometry geometry,String inputDigest,RouteDisplayGeometry baseline,
            SourceBoundDisplayCurves.Plan curve) {
        RoutePaths original=geometry.paths();
        List<String> rejected=new ArrayList<>();
        if(!curve.continuousControlHullCertified() || !curve.serializedChordCorrespondenceCertified())rejected.add("CURVE_SOURCE_BOUND_NOT_CERTIFIED");
        if(!SourceBoundDisplayCurves.endpointsPreserved(baseline.paths(),curve.paths()))rejected.add("CURVE_ENDPOINT_CHANGED");
        if(!RouteDisplayTopology.noNewCrossings(baseline.paths(),curve.paths()))rejected.add("CURVE_NEW_CROSSING_LOCATION");
        if(!RouteDisplayTopology.noNewRendererCrossings(baseline.paths(),curve.paths()))rejected.add("CURVE_NEW_RENDERER_CROSSING_LOCATION");
        if(!RouteDisplayTopology.noNewContacts(baseline.paths(),curve.paths()))rejected.add("CURVE_NEW_NONADJACENT_TOUCH_OR_OVERLAP");
        if(!RouteDisplayTopology.noNewSpike(baseline.paths(),curve.paths()))rejected.add("CURVE_NEW_BACKTRACK_SPIKE");
        List<QualityIssue> qualityWarnings=new ArrayList<>();
        // Relative visual measurements do not invalidate a usable generated bundle.
        for(String direction:List.of("UP","DOWN")) {
            var metric=curve.curvature().get(direction);
            if(metric!=null && !metric.noNewFixedFiveMetreRegression())
                qualityWarnings.add(issue("CURVE_FIXED_FIVE_METRE_REGRESSION",direction,null,null));
        }
        // Keep validated terminal cuts and transport protected road occurrences
        // through the combined plan. Reprojecting passenger GPS onto a newly
        // closer curve can select another source position or repeated lap.
        var preserved=RouteStopIntervals.preserveCurveConnections(geometry.busStops(),baseline.paths(),baseline.connections(),curve);
        for(var problem:preserved.blockingIssues())rejected.add(problem.code());
        if(!preserved.usable())return new CurveCheck(null,List.copyOf(new LinkedHashSet<>(rejected)));
        var sourceWitness=SourceStopOccurrenceWitness.throughCurve(geometry,baseline.paths(),baseline.connections(),curve,preserved.connections());
        Evaluation checked=evaluate(geometry,original,curve.paths(),false,preserved.connections(),sourceWitness);
        if(checked.blocked())rejected.add("CURVE_FINAL_VISIT_OR_TOPOLOGY_RECHECK_FAILED");
        if(!checked.blocked())for(var issue:RouteStopIntervals.preservedCurveOccurrences(geometry.busStops(),
                baseline.paths(),baseline.connections(),checked.paths(),checked.connections(),curve).blockingIssues())
            rejected.add(issue.code());
        if(!checked.blocked())for(var stop:geometry.busStops()) {
            if(stop.point()==null)continue;
            Connection before=baseline.connections().get(stop.stopSequence()),after=checked.connections().get(stop.stopSequence());
            if(before==null || after==null) {
                rejected.add("CURVE_STOP_CONNECTION_MISSING");
                continue;
            }
            double increase=RouteStopAlignment.distance(stop.point(),point(after))-RouteStopAlignment.distance(stop.point(),point(before));
            if(increase>5)qualityWarnings.add(issue("CURVE_STOP_CONNECTION_WORSENED",stop.direction(),stop.stopSequence(),increase));
        }
        if(!rejected.isEmpty())return new CurveCheck(null,List.copyOf(new LinkedHashSet<>(rejected)));
        var issues=new ArrayList<>(checked.issues());
        issues.addAll(connectionGapWarnings(geometry,original,checked.connections()));
        issues.addAll(qualityWarnings);
        var result=result(geometry,inputDigest,checked.paths(),checked.connections(),issues.isEmpty()?QualityStatus.PASSED:QualityStatus.REVIEW_REQUIRED,issues);
        return new CurveCheck(result,List.of());
    }

    private static boolean usableBaseline(RouteDisplayGeometry result) {
        return result.paths()!=null && result.quality().status()!=QualityStatus.SOURCE_ONLY
                && result.quality().status()!=QualityStatus.WITHHELD;
    }

    private static int pointCount(RoutePaths paths) {return paths==null?0:paths.up().points().size()+paths.down().points().size();}

    private static RouteDisplayGeometry buildNodeBaseline(RouteGeometry geometry,String inputDigest,RoutePaths nodeCandidate) {
        RoutePaths original = geometry.paths();
        if (original == null) {
            var problems = new ArrayList<QualityIssue>();
            problems.add(issue("DIRECTION_BOUNDARY_UNAVAILABLE", null, null, null));
            if ((long) geometry.busStops().size() * geometry.points().size() <= 2_000_000) {
                for (var stop : geometry.busStops())
                    if (stop.point() != null) {
                        double gap =
                                RouteStopAlignment.nearestDistance(geometry.points(), stop.point());
                        if (gap > 100)
                            problems.add(
                                    issue(
                                            "SOURCE_STOP_TOO_FAR",
                                            stop.direction(),
                                            stop.stopSequence(),
                                            gap));
                    }
            }
            return result(
                    geometry,
                    inputDigest,
                    null,
                    Map.of(),
                    problems.size() > 1 ? QualityStatus.WITHHELD : QualityStatus.SOURCE_ONLY,
                    problems);
        }
        RoutePaths candidate = nodeCandidate;
        Evaluation checked = evaluate(geometry, original, candidate, true);
        if (!checked.blocked()) {
            List<QualityIssue> problems = new ArrayList<>(checked.issues());
            problems.addAll(connectionGapWarnings(geometry, original, checked.connections()));
            checked = new Evaluation(checked.paths(), checked.connections(), problems, false);
        }
        if (!checked.blocked())
            return result(
                    geometry,
                    inputDigest,
                    checked.paths(),
                    checked.connections(),
                    checked.issues().isEmpty()
                            ? QualityStatus.PASSED
                            : QualityStatus.REVIEW_REQUIRED,
                    checked.issues());
        // 후보가 실패하면 전체 원본 경로를 별도로 검사한다. 잘못된 연결 일부만 섞지 않는다.
        Evaluation source = evaluate(geometry, original, original, false);
        var issues = new ArrayList<>(new LinkedHashSet<>(checked.issues()));
        for (var issue : source.issues()) if (!issues.contains(issue)) issues.add(issue);
        if (source.blocked())
            return result(geometry, inputDigest, null, Map.of(), QualityStatus.WITHHELD, issues);
        return result(
                geometry,
                inputDigest,
                source.paths(),
                source.connections(),
                QualityStatus.SOURCE_ONLY,
                issues);
    }

    private static List<QualityIssue> connectionGapWarnings(RouteGeometry geometry, RoutePaths original,
            Map<Integer, Connection> selectedConnections) {
        Map<Integer, Connection> before = new HashMap<>();
        var up = RouteStopAlignment.align(original.up().points(), stops(geometry, "UP"), true);
        var down = RouteStopAlignment.align(original.down().points(), stops(geometry, "DOWN"), false);
        if (up.usable()) before.putAll(up.connections());
        if (down.usable()) before.putAll(down.connections());
        List<QualityIssue> warnings = new ArrayList<>();
        for (var stop : geometry.busStops()) {
            Connection old = before.get(stop.stopSequence()), selected = selectedConnections.get(stop.stopSequence());
            if (stop.point() == null || old == null || selected == null) continue;
            double increase = RouteStopAlignment.distance(stop.point(), point(selected))
                    - RouteStopAlignment.distance(stop.point(), point(old));
            if (increase > 5) warnings.add(issue("STOP_CONNECTION_WORSENED", stop.direction(), stop.stopSequence(), increase));
        }
        return warnings;
    }

    /** 원본 경로만 검사한다. 보조 기하를 생성하거나 원본 끝점을 자르지 않는다. */
    public static RouteDisplayGeometry sourceOnly(RouteGeometry geometry, String inputDigest) {
        RoutePaths original = geometry.paths();
        // build의 이 분기는 방향 경계가 없는 원본만 판정하며 후보를 만들지 않는다.
        if (original == null) return build(geometry, inputDigest);
        Evaluation source = evaluate(geometry, original, original, false);
        return result(
                geometry,
                inputDigest,
                source.blocked() ? null : source.paths(),
                source.blocked() ? Map.of() : source.connections(),
                source.blocked() ? QualityStatus.WITHHELD : QualityStatus.SOURCE_ONLY,
                source.issues());
    }

    private static Evaluation evaluate(
            RouteGeometry geometry, RoutePaths reference, RoutePaths paths, boolean trim) {
        return evaluate(geometry,reference,paths,trim,null);
    }

    /** A curve may transport validated stop occurrences through its source map.
     * These are still checked against the actual final serialized path. Raw and
     * node-baseline builds continue to use the ordered projection algorithm. */
    private static Evaluation evaluate(RouteGeometry geometry,RoutePaths reference,RoutePaths paths,
            boolean trim,Map<Integer,Connection> preservedConnections) {
        return evaluate(geometry,reference,paths,trim,preservedConnections,null);
    }

    private static Evaluation evaluate(RouteGeometry geometry,RoutePaths reference,RoutePaths paths,
            boolean trim,Map<Integer,Connection> preservedConnections,SourceStopOccurrenceWitness.Assessment curveWitness) {
        List<QualityIssue> issues = new ArrayList<>();
        Map<Integer, Connection> connections = new HashMap<>();
        boolean blocked = false;
        if(preservedConnections!=null) {
            var intervalCheck=RouteStopIntervals.evaluate(geometry.busStops(),paths,preservedConnections);
            if(trim || !intervalCheck.usable()) {
                issues.addAll(intervalCheck.blockingIssues());
                if(trim)issues.add(issue("CURVE_PRESERVED_CONNECTIONS_CANNOT_BE_RETRIMMED",null,null,null));
                return new Evaluation(paths,Map.of(),List.copyOf(issues),true);
            }
        }
        if (!paths.up().points().getLast().equals(reference.up().points().getLast())
                || !paths.down().points().getFirst().equals(reference.down().points().getFirst())) {
            issues.add(issue("TURN_BOUNDARY_CHANGED", null, null, null));
            blocked = true;
        }
        if (!RouteDisplayTopology.noNewCrossings(reference, paths)) {
            issues.add(issue("NEW_CROSSING_LOCATION", null, null, null));
            blocked = true;
        }
        if (!RouteDisplayTopology.noNewSpike(reference, paths)) {
            issues.add(issue("NEW_BACKTRACK_SPIKE", null, null, null));
            blocked = true;
        }
        var up = stops(geometry, "UP");
        var down = stops(geometry, "DOWN");
        var upResult = preservedConnections==null?RouteStopAlignment.align(paths.up().points(), up, true)
                :new RouteStopAlignment.Result(subset(preservedConnections,up),null);
        var downResult = preservedConnections==null?RouteStopAlignment.align(paths.down().points(), down, false)
                :new RouteStopAlignment.Result(subset(preservedConnections,down),null);
        for (var entry : List.of(Map.entry("UP", upResult), Map.entry("DOWN", downResult))) {
            if (!entry.getValue().usable()) {
                issues.add(issue(entry.getValue().failure(), entry.getKey(), null, null));
                blocked = true;
            } else connections.putAll(entry.getValue().connections());
        }
        if (blocked) return new Evaluation(paths, Map.of(), issues, true);
        for (var stop : geometry.busStops()) {
            if (stop.point() == null) {
                issues.add(
                        issue(
                                "MISSING_STOP_COORDINATE",
                                stop.direction(),
                                stop.stopSequence(),
                                null));
                continue;
            }
            Connection connection = connections.get(stop.stopSequence());
            if (connection == null) {
                issues.add(
                        issue(
                                "MISSING_VISIT_CONNECTION",
                                stop.direction(),
                                stop.stopSequence(),
                                null));
                blocked = true;
                continue;
            }
            double gap = RouteStopAlignment.distance(stop.point(), point(connection));
            if (stop.boardingAllowed() && gap > 15)
                issues.add(
                        issue("STOP_CONNECTION_GAP", stop.direction(), stop.stopSequence(), gap));
            // The existing100m association cap remains hard. Boarding GPS
            // confidence above30m is decided only after final occurrence checks.
            if (gap > RouteStopAlignment.MAX_OFFSET) blocked = true;
        }
        RoutePaths.Path upPath = paths.up(), downPath = paths.down();
        for (String direction : List.of("UP", "DOWN")) {
            var visits = direction.equals("UP") ? up : down;
            if (visits.isEmpty()) continue;
            var terminal = direction.equals("UP") ? visits.getFirst() : visits.getLast();
            if (terminal.point() == null) {
                issues.add(
                        issue(
                                "TERMINAL_COORDINATE_UNAVAILABLE",
                                direction,
                                terminal.stopSequence(),
                                null));
                continue;
            }
            var path = direction.equals("UP") ? upPath : downPath;
            var connection = connections.get(terminal.stopSequence());
            double before = distanceAt(path.points(), connection), length = length(path.points());
            double overhang = direction.equals("UP") ? before : length - before;
            if (overhang > 100) {
                issues.add(
                        issue("TERMINAL_OVERHANG", direction, terminal.stopSequence(), overhang));
                blocked = true;
                continue;
            }
            double gap = RouteStopAlignment.distance(terminal.point(), point(connection));
            if (trim && gap <= 15 && overhang > 0.01) {
                var cut = cut(path, connection, direction.equals("UP"));
                if (cut == null) {
                    issues.add(
                            issue(
                                    "TERMINAL_PATH_COLLAPSE",
                                    direction,
                                    terminal.stopSequence(),
                                    null));
                    blocked = true;
                } else if (direction.equals("UP")) upPath = cut;
                else downPath = cut;
            }
        }
        if (blocked) return new Evaluation(paths, Map.of(), issues, true);
        RoutePaths selected = new RoutePaths(upPath, downPath);
        // 잘라낸 최종 배열에서 모든 방문의 선분 인덱스를 다시 계산한다.
        var finalUp = preservedConnections==null?RouteStopAlignment.align(selected.up().points(), up, true)
                :new RouteStopAlignment.Result(subset(preservedConnections,up),null);
        var finalDown = preservedConnections==null?RouteStopAlignment.align(selected.down().points(), down, false)
                :new RouteStopAlignment.Result(subset(preservedConnections,down),null);
        if (!finalUp.usable() || !finalDown.usable()) {
            issues.add(issue("FINAL_CONNECTION_RECHECK_FAILED", null, null, null));
            return new Evaluation(paths, Map.of(), issues, true);
        }
        if (!RouteDisplayTopology.noNewCrossings(reference, selected)) {
            issues.add(issue("FINAL_NEW_CROSSING_LOCATION", null, null, null));
            return new Evaluation(paths, Map.of(), issues, true);
        }
        Map<Integer, Connection> finalConnections = new HashMap<>(finalUp.connections());
        finalConnections.putAll(finalDown.connections());
        var intervals=RouteStopIntervals.evaluate(geometry.busStops(),selected,finalConnections);
        if(!intervals.usable()) {
            issues.addAll(intervals.blockingIssues());
            return new Evaluation(selected,Map.of(),List.copyOf(issues),true);
        }
        var witness=curveWitness;
        for(var stop:geometry.busStops())if(stop.point()!=null && stop.boardingAllowed()) {
            var connection=finalConnections.get(stop.stopSequence());
            double gap=RouteStopAlignment.distance(stop.point(),point(connection));
            if(gap>RouteStopAlignment.MAX_OFFSET)return new Evaluation(selected,Map.of(),List.copyOf(issues),true);
            if(gap>30) {
                if(witness==null)witness=SourceStopOccurrenceWitness.assess(geometry,selected,finalConnections);
                if(!witness.supports(stop))return new Evaluation(selected,Map.of(),List.copyOf(issues),true);
            }
        }
        return new Evaluation(selected, Map.copyOf(finalConnections), List.copyOf(issues), false);
    }

    private static RoutePaths.Path cut(RoutePaths.Path path, Connection connection, boolean start) {
        List<Point> result = new ArrayList<>();
        Point boundary = point(connection);
        int edge = connection.segmentIndex();
        if (start) {
            result.add(boundary);
            result.addAll(path.points().subList(edge + 1, path.points().size()));
        } else {
            result.addAll(path.points().subList(0, edge + 1));
            result.add(boundary);
        }
        List<Point> clean = new ArrayList<>();
        for (var point : result)
            if (clean.isEmpty() || !clean.getLast().equals(point)) clean.add(point);
        return clean.stream().distinct().limit(2).count() < 2 ? null : new RoutePaths.Path(clean);
    }

    private static List<RouteGeometry.Stop> stops(RouteGeometry geometry, String direction) {
        return geometry.busStops().stream()
                .filter(stop -> stop.direction().equals(direction))
                .toList();
    }

    private static Map<Integer,Connection> subset(Map<Integer,Connection> connections,List<RouteGeometry.Stop> stops) {
        Map<Integer,Connection> result=new HashMap<>();
        for(var stop:stops)if(connections.containsKey(stop.stopSequence()))result.put(stop.stopSequence(),connections.get(stop.stopSequence()));
        return Map.copyOf(result);
    }

    private static double distanceAt(List<Point> points, Connection connection) {
        double result = 0;
        for (int i = 0; i < connection.segmentIndex(); i++)
            result += RouteStopAlignment.distance(points.get(i), points.get(i + 1));
        return result
                + RouteStopAlignment.distance(
                                points.get(connection.segmentIndex()),
                                points.get(connection.segmentIndex() + 1))
                        * connection.segmentFraction();
    }

    private static double length(List<Point> points) {
        double result = 0;
        for (int i = 1; i < points.size(); i++)
            result += RouteStopAlignment.distance(points.get(i - 1), points.get(i));
        return result;
    }

    private static Point point(Connection connection) {
        return new Point(connection.point().x(), connection.point().y());
    }

    private static QualityIssue issue(
            String code, String direction, Integer sequence, Double meters) {
        return new QualityIssue(
                code, direction, sequence, meters == null ? null : Math.rint(meters * 1000) / 1000);
    }

    private static RouteDisplayGeometry result(
            RouteGeometry geometry,
            String digest,
            RoutePaths paths,
            Map<Integer, Connection> connections,
            QualityStatus status,
            List<QualityIssue> issues) {
        return new RouteDisplayGeometry(
                paths,
                connections,
                Map.of(),
                REVISION
                        + "/"
                        + digest
                        + "/"
                        + geometry.shape().evidence().revision()
                        + "/"
                        + status,
                Source.AUTOMATIC_FALLBACK,
                geometry.shape().evidence().sources(),
                new Quality(status, issues),
                geometry.paths());
    }
}
