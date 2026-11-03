package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 서로 대응되는 웹 type 근거만 적용한다. 노드 근접만으로 형상점을 지우지 않는다. */
final class EvidenceRoutePaths {
    private EvidenceRoutePaths() {}

    static RoutePaths build(RouteGeometry geometry) {
        RoutePaths original = geometry.paths();
        if (original == null) return null;
        Classification alignment = classify(geometry);
        if (alignment.classifications().isEmpty()) return original;
        Map<Point, List<TypedPoint>> classifications = alignment.classifications();
        var crossings = RouteDisplayTopology.crossings(original);
        if (!crossings.complete()) return original;
        var renderedCrossings=RouteDisplayTopology.rendererCrossings(original);
        var contacts=RouteDisplayTopology.contacts(original);var rendererContacts=RouteDisplayTopology.rendererContacts(original);
        if(!renderedCrossings.complete() || !contacts.complete() || !rendererContacts.complete())return original;
        RoutePaths floor=buildSequentialFloor(geometry);
        RoutePaths candidate=floor;
        crossings=RouteDisplayTopology.crossings(floor);
        renderedCrossings=RouteDisplayTopology.rendererCrossings(floor);
        contacts=RouteDisplayTopology.contacts(floor);rendererContacts=RouteDisplayTopology.rendererContacts(floor);
        // Same-coordinate occurrences are a group only after every source role
        // at that coordinate has independently matched. A real/mixed shape
        // occurrence, boundary or visit protects the entire group.
        var groups=classifications.entrySet().stream()
                .filter(e->!e.getValue().isEmpty() && e.getValue().stream().allMatch(p->p.type()==2))
                .sorted(java.util.Comparator.<Map.Entry<Point,List<TypedPoint>>>comparingInt(e->-e.getValue().size())
                        .thenComparingDouble(e->e.getKey().x()).thenComparingDouble(e->e.getKey().y())).toList();
        for(var group:groups) {
            Point target=group.getKey();boolean safe=true,seen=false;
            if(geometry.busStops().stream().anyMatch(s->s.point()!=null && distance(s.point(),target)<=8))continue;
            for(var named:List.of(Map.entry("UP",candidate.up()),Map.entry("DOWN",candidate.down()))) {
                var points=named.getValue().points();long count=points.stream().filter(target::equals).count();
                if(count==0)continue;seen=true;
                if(target.equals(points.getFirst()) || target.equals(points.getLast())){safe=false;break;}
                var evidence=group.getValue().stream().filter(p->p.direction().equals(named.getKey())).toList();
                if(evidence.size()<count){safe=false;break;}
                boolean confirmed=evidence.stream().anyMatch(TypedPoint::nodeConfirmed);
                for(int i=1;i<points.size()-1;i++)if(points.get(i).equals(target)) {
                    int left=i-1,right=i+1;
                    while(left>=0 && points.get(left).equals(target))left--;
                    while(right<points.size() && points.get(right).equals(target))right++;
                    if(left<0 || right>=points.size()){safe=false;break;}
                    Point a=points.get(left),b=points.get(right);
                    if(!classifications.containsKey(a) || !classifications.containsKey(b) || distance(a,b)<1 || distance(a,b)>500
                            || segmentDistance(target,a,b)>(confirmed?25:10)){safe=false;break;}
                    if(!safe)break;
                }
                if(!safe)break;
            }
            if(!safe || !seen)continue;
            var up=candidate.up().points().stream().filter(p->!p.equals(target)).toList();
            var down=candidate.down().points().stream().filter(p->!p.equals(target)).toList();
            if(up.size()<2 || down.size()<2)continue;
            var proposed=new RoutePaths(new RoutePaths.Path(up),new RoutePaths.Path(down));
            if(RouteDisplayTopology.noNewShortcutCrossings(candidate,proposed,crossings,renderedCrossings)
                    && RouteDisplayTopology.noNewContacts(contacts,rendererContacts,proposed)
                    && RouteDisplayTopology.noNewSpike(floor,proposed))candidate=proposed;
        }
        return RouteDisplayTopology.noNewCrossings(floor, candidate)
                        && RouteDisplayTopology.noNewRendererCrossings(floor,candidate)
                        && RouteDisplayTopology.noNewContacts(floor,candidate)
                        && RouteDisplayTopology.noNewSpike(floor, candidate)
                ? candidate
                : floor;
    }

    /** Sequential role removal preserves a validated whole bundle as the
     * lower floor before atomic shared-role or curve changes. */
    static RoutePaths buildSequentialFloor(RouteGeometry geometry) {
        RoutePaths original = geometry.paths();
        if (original == null) return null;
        Classification alignment = classifyFull(geometry);
        if (alignment.classifications().isEmpty()) return original;
        Map<Point, List<TypedPoint>> classifications = alignment.classifications();
        var crossings = RouteDisplayTopology.crossings(original);
        if (!crossings.complete()) return original;
        var angles = RouteDisplayTopology.angles(original);
        List<Point> up = new ArrayList<>(original.up().points()),
                down = new ArrayList<>(original.down().points());
        clean(up, down, "UP", classifications, geometry, crossings, angles);
        clean(down, up, "DOWN", classifications, geometry, crossings, angles);
        RoutePaths candidate = new RoutePaths(new RoutePaths.Path(up), new RoutePaths.Path(down));
        return RouteDisplayTopology.noNewCrossings(original, candidate)
                        && RouteDisplayTopology.noNewSpike(original, candidate)
                ? candidate
                : original;
    }

    private static void clean(
            List<Point> points,
            List<Point> other,
            String direction,
            Map<Point, List<TypedPoint>> classifications,
            RouteGeometry geometry,
            RouteDisplayTopology.Crossings crossings,
            Map<Point, Double> angles) {
        for (int i = points.size() - 2; i > 0; i--) {
            Point target = points.get(i);
            var evidence =
                    classifications.getOrDefault(target, List.of()).stream()
                            .filter(value -> value.direction().equals(direction))
                            .toList();
            if (evidence.isEmpty() || evidence.stream().anyMatch(value -> value.type() != 2))
                continue;
            if (geometry.busStops().stream()
                    .anyMatch(stop -> stop.point() != null && distance(stop.point(), target) <= 8))
                continue;
            Point a = points.get(i - 1), b = points.get(i + 1);
            if (!classifications.containsKey(a) || !classifications.containsKey(b)) continue;
            boolean confirmed = evidence.stream().anyMatch(TypedPoint::nodeConfirmed);
            if (distance(a, b) < 1
                    || distance(a, b) > 500
                    || segmentDistance(target, a, b) > (confirmed ? 25 : 10)) continue;
            if (!RouteDisplayTopology.safeShortcut(points, i, other, crossings, angles)) continue;
            points.remove(i);
        }
    }

    record Classification(
            Map<Point, List<TypedPoint>> classifications,
            int matchedPoints,
            int unmatchedPoints,
            boolean sharedBoundaryMerged,String normalization) {
        Classification(Map<Point,List<TypedPoint>> classifications,int matchedPoints,int unmatchedPoints,boolean sharedBoundaryMerged) {
            this(classifications,matchedPoints,unmatchedPoints,sharedBoundaryMerged,"FULL_POINT_ORDER_V1");
        }
    }

    /** 좌표를 이동하지 않고 분류만 대응한다. 중복 회차점 이외 삽입·삭제는 추측하지 않는다. */
    static Classification classify(RouteGeometry geometry) {
        Classification full=classifyFull(geometry),visible=classifyVisible(geometry);
        if(visible==null)return full;
        if(full.classifications().isEmpty() || full.unmatchedPoints()>0 || geometry.shape().evidence().selectedSource().equals("GBIS_WEB"))return visible;
        // A one-node/duplicate degeneracy may fit both lineages. Retain only
        // roles on which both complete source interpretations agree.
        Map<Point,List<TypedPoint>> intersection=new HashMap<>();
        full.classifications().forEach((point,labels)->{
            var other=visible.classifications().get(point);
            if(other!=null && signatures(labels).equals(signatures(other)))intersection.put(point,other);
        });
        return new Classification(Map.copyOf(intersection),visible.matchedPoints(),0,visible.sharedBoundaryMerged(),"AMBIGUOUS_FULL_VISIBLE_SAFE_INTERSECTION_V1");
    }
    private static java.util.Set<String> signatures(List<TypedPoint> points) {
        var result=new java.util.HashSet<String>();points.forEach(p->result.add(p.direction()+":"+p.type()));return result;
    }
    private static Classification classifyFull(RouteGeometry geometry) {
        var typed = geometry.shape().evidence().webPoints();
        var raw = geometry.points();
        if (raw.size() > 10000 || typed.isEmpty())
            return new Classification(Map.of(), 0, raw.size(), false);
        int boundary = -1;
        if (typed.size() == raw.size() + 1) {
            for (int i = 0; i < typed.size() - 1; i++) {
                var a = typed.get(i);
                var b = typed.get(i + 1);
                if (a.direction().equals("UP")
                        && b.direction().equals("DOWN")
                        && a.point().equals(b.point())
                        && a.type() == b.type()) {
                    if (boundary >= 0) return new Classification(Map.of(), 0, raw.size(), false);
                    boundary = i;
                }
            }
            if (boundary < 0) return new Classification(Map.of(), 0, raw.size(), false);
        } else if (typed.size() != raw.size())
            return new Classification(Map.of(), 0, raw.size(), false);
        Map<Point, List<TypedPoint>> result = new HashMap<>();
        java.util.Set<Point> unknown = new java.util.HashSet<>();
        int matched = 0;
        for (int rawIndex = 0, webIndex = 0; rawIndex < raw.size(); rawIndex++, webIndex++) {
            var value = typed.get(webIndex);
            Point rawPoint = raw.get(rawIndex);
            if (distance(rawPoint, point(value)) <= 4) {
                result.computeIfAbsent(rawPoint, ignored -> new ArrayList<>()).add(value);
                matched++;
                if (webIndex == boundary) result.get(rawPoint).add(typed.get(webIndex + 1));
            } else unknown.add(rawPoint);
            if (webIndex == boundary) webIndex++;
        }
        if ((long) matched * 100 < (long) raw.size() * 95)
            return new Classification(Map.of(), matched, raw.size() - matched, boundary >= 0);
        // 한 좌표의 다른 출현이 미대응이면 그 좌표도 전부 보존한다.
        unknown.forEach(result::remove);
        return new Classification(Map.copyOf(result), matched, raw.size() - matched, boundary >= 0);
    }

    /** The official web fallback already excludes type2 before saving its
     * primary shape. Only that exact visible sequence (optionally the single
     * shared turn duplicate) is recognized; never nearest/insertion matching. */
    private static Classification classifyVisible(RouteGeometry geometry) {
        var typed=geometry.shape().evidence().webPoints();var raw=geometry.points();
        if(raw.size()>10000 || typed.isEmpty() || typed.stream().noneMatch(p->p.type()==2))return null;
        boolean down=false;int transition=-1;
        for(int i=0;i<typed.size();i++) {
            if(typed.get(i).direction().equals("DOWN")){if(!down)transition=i;down=true;}
            else if(down)return null;
        }
        if(transition<=0)return null;
        var visible=typed.stream().filter(p->p.type()!=2).toList();if(visible.size()<4)return null;
        int boundary=-1;
        for(int i=0;i<visible.size()-1;i++)if(visible.get(i).direction().equals("UP") && visible.get(i+1).direction().equals("DOWN")) {
            if(boundary>=0)return null;boundary=i;
        }
        if(boundary<1 || boundary+2>=visible.size())return null;
        boolean duplicate=visible.get(boundary).point().equals(visible.get(boundary+1).point()) && visible.get(boundary).type()==visible.get(boundary+1).type();
        boolean merge=raw.size()+1==visible.size();
        if(raw.size()!=visible.size() && !(merge && duplicate))return null;
        List<List<TypedPoint>> rows=new ArrayList<>();
        for(int i=0;i<visible.size();i++) {
            if(merge && i==boundary){rows.add(List.of(visible.get(i),visible.get(i+1)));i++;}
            else rows.add(List.of(visible.get(i)));
        }
        if(rows.size()!=raw.size())return null;
        Map<Point,List<TypedPoint>> result=new HashMap<>();
        for(int i=0;i<raw.size();i++)for(var value:rows.get(i)) {
            if(distance(raw.get(i),point(value))>4)return null;
            result.computeIfAbsent(raw.get(i),ignored->new ArrayList<>()).add(value);
        }
        List<java.util.Set<String>> visits=new ArrayList<>();
        for(int i=0;i<visible.size();i++)if(visible.get(i).type()==1) {
            if(duplicate && i==boundary && visible.get(i+1).type()==1 && geometry.busStops().size()==visible.stream().filter(p->p.type()==1).count()-1) {
                visits.add(java.util.Set.of("UP","DOWN"));i++;
            } else visits.add(java.util.Set.of(visible.get(i).direction()));
        }
        if(visits.size()!=geometry.busStops().size() || geometry.paths()==null)return null;
        int previous=0;
        for(int i=0;i<visits.size();i++) {
            var stop=geometry.busStops().get(i);
            if(stop.stopSequence()<=previous || !visits.get(i).contains(stop.direction()))return null;
            if(geometry.turnSequence()!=null && !stop.direction().equals(stop.stopSequence()<=geometry.turnSequence()?"UP":"DOWN"))return null;
            previous=stop.stopSequence();
        }
        Map<Point,List<TypedPoint>> immutable=new HashMap<>();result.forEach((p,values)->immutable.put(p,List.copyOf(values)));
        return new Classification(Map.copyOf(immutable),raw.size(),0,merge,"OFFICIAL_VISIBLE_SUBSEQUENCE_V1");
    }

    private static Point point(TypedPoint value) {
        return new Point(value.point().x(), value.point().y());
    }

    private static double distance(Point a, Point b) {
        return Math.hypot((a.x() - b.x()) * Math.cos(Math.toRadians(a.y())), a.y() - b.y())
                * 111320;
    }

    private static double segmentDistance(Point p, Point a, Point b) {
        double scale = Math.cos(Math.toRadians(p.y())),
                dx = (b.x() - a.x()) * scale,
                dy = b.y() - a.y(),
                length = dx * dx + dy * dy;
        if (length == 0) return distance(p, a);
        double f =
                Math.max(
                        0,
                        Math.min(
                                1, ((p.x() - a.x()) * scale * dx + (p.y() - a.y()) * dy) / length));
        return distance(p, new Point(a.x() + (b.x() - a.x()) * f, a.y() + (b.y() - a.y()) * f));
    }
}
