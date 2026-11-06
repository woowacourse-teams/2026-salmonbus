package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import com.gustler.backend.routegeometry.engine.api.RoutePaths;

import java.util.*;

/** XY 교차 위치를 대조한다. 기존 입체교차·정상 교차를 교차 개수만으로 삭제하지 않는다. */
final class RouteDisplayTopology {
    private record Coordinate(double x,double y) {}
    private record Segment(int ordinal,Coordinate a,Coordinate b,double minX,double maxX,double minY,double maxY) {}

    record Crossings(List<Point> points, boolean complete) {}
    record Contact(Point first,Point last,boolean overlap,String firstPath,String secondPath) {}
    record Contacts(List<Contact> values,boolean complete) {}
    private record TaggedSegments(List<Segment> segments,List<String> directions,List<Integer> positions) {}

    private RouteDisplayTopology() {}

    static Crossings crossings(RoutePaths paths) {
        return crossings(paths,false);
    }
    static Crossings rendererCrossings(RoutePaths paths) {return crossings(paths,true);}
    static Contacts contacts(RoutePaths paths){return contacts(paths,false);}
    static Contacts rendererContacts(RoutePaths paths){return contacts(paths,true);}

    static boolean noNewContacts(RoutePaths reference,RoutePaths candidate) {
        if(reference.equals(candidate))return true;
        return noNewContacts(contacts(reference,false),contacts(reference,true),candidate);
    }
    static boolean noNewContacts(Contacts reference,Contacts rendererReference,RoutePaths candidate) {
        return contactsContained(reference,contacts(candidate,false)) && contactsContained(rendererReference,contacts(candidate,true));
    }
    private static boolean contactsContained(Contacts before,Contacts after) {
        if(!before.complete() || !after.complete())return false;
        for(var contact:after.values()) {
            boolean present=before.values().stream().anyMatch(old->containsContact(old,contact));
            if(!present)return false;
        }
        return true;
    }
    private static boolean containsContact(Contact old, Contact contact) {
        return sameParticipants(old, contact) && (contact.overlap()
                ? old.overlap() && segmentDistance(contact.first(), old.first(), old.last()) <= .05
                    && segmentDistance(contact.last(), old.first(), old.last()) <= .05
                : old.overlap() ? segmentDistance(contact.first(), old.first(), old.last()) <= .05
                    : RouteStopAlignment.distance(contact.first(), old.first()) <= .05);
    }
    private static boolean sameParticipants(Contact a,Contact b) {
        return a.firstPath().equals(b.firstPath()) && a.secondPath().equals(b.secondPath())
            ||a.firstPath().equals(b.secondPath()) && a.secondPath().equals(b.firstPath());
    }
    private static double segmentDistance(Point p,Point a,Point b) {
        double scale=Math.cos(Math.toRadians(p.y())),dx=(b.x()-a.x())*scale,dy=b.y()-a.y(),size=dx*dx+dy*dy;
        double t=size==0?0:Math.max(0,Math.min(1,((p.x()-a.x())*scale*dx+(p.y()-a.y())*dy)/size));
        return RouteStopAlignment.distance(p,new Point(a.x()+t*(b.x()-a.x()),a.y()+t*(b.y()-a.y())));
    }
    private static TaggedSegments tagged(RoutePaths paths,boolean renderer) {
        List<Segment> segments=new ArrayList<>();List<String> directions=new ArrayList<>();List<Integer> positions=new ArrayList<>();
        for(var named:List.of(Map.entry("UP",paths.up()),Map.entry("DOWN",paths.down()))) {
            int position=0;var points=named.getValue().points();
            for(int i=0;i<points.size()-1;i++) {
                if(renderer && (Math.abs(points.get(i).y())>=85.06 || Math.abs(points.get(i+1).y())>=85.06))return null;
                var a=coordinate(points.get(i),renderer);var b=coordinate(points.get(i+1),renderer);
                if(a.equals(b))continue;
                segments.add(new Segment(segments.size(),a,b,Math.min(a.x(),b.x()),Math.max(a.x(),b.x()),Math.min(a.y(),b.y()),Math.max(a.y(),b.y())));
                directions.add(named.getKey());positions.add(position++);
            }
        }
        return new TaggedSegments(segments,directions,positions);
    }
    private static Contacts contacts(RoutePaths paths,boolean renderer) {
        var tagged=tagged(paths,renderer);if(tagged==null)return new Contacts(List.of(),false);
        if(tagged.segments().isEmpty())return new Contacts(List.of(),true);
        var index=new SegmentTree(new ArrayList<>(tagged.segments()));var budget=new Budget();List<Contact> result=new ArrayList<>();
        for(var segment:tagged.segments()) {
            List<Segment> possible=new ArrayList<>();index.query(segment,possible,budget);
            if(budget.exhausted)return new Contacts(List.copyOf(result),false);
            for(var other:possible) {
                if(!budget.step())return new Contacts(List.copyOf(result),false);
                var hit=contact(segment.a(),segment.b(),other.a(),other.b());if(hit==null)continue;
                String a=tagged.directions().get(segment.ordinal()),b=tagged.directions().get(other.ordinal());
                boolean overlap=!hit[0].equals(hit[1]);
                if(!overlap && a.equals(b) && Math.abs(tagged.positions().get(segment.ordinal())-tagged.positions().get(other.ordinal()))==1)continue;
                result.add(new Contact(geographic(hit[0],renderer),geographic(hit[1],renderer),overlap,a,b));
            }
        }
        return new Contacts(List.copyOf(result),true);
    }
    private static Coordinate[] contact(Coordinate a,Coordinate b,Coordinate c,Coordinate d) {
        int ac=orientation(a,b,c),ad=orientation(a,b,d),ca=orientation(c,d,a),cb=orientation(c,d,b);
        if(ac==0 && ad==0) {
            boolean x=Math.abs(b.x()-a.x())>=Math.abs(b.y()-a.y());double denominator=x?b.x()-a.x():b.y()-a.y();
            double first=((x?c.x():c.y())-(x?a.x():a.y()))/denominator,last=((x?d.x():d.y())-(x?a.x():a.y()))/denominator;
            double lo=Math.max(0,Math.min(first,last)),hi=Math.min(1,Math.max(first,last));
            if(lo>hi)return null;return new Coordinate[]{at(a,b,lo),at(a,b,hi)};
        }
        if(ac==0 && inside(c,a,b))return new Coordinate[]{c,c};if(ad==0 && inside(d,a,b))return new Coordinate[]{d,d};
        if(ca==0 && inside(a,c,d))return new Coordinate[]{a,a};if(cb==0 && inside(b,c,d))return new Coordinate[]{b,b};
        if(ac*ad>=0 || ca*cb>=0)return null;
        var hit=intersection(a,b,c,d);
        if(hit==null) {
            var dx=decimal(b.x()).subtract(decimal(a.x()));var dy=decimal(b.y()).subtract(decimal(a.y()));
            var ex=decimal(d.x()).subtract(decimal(c.x()));var ey=decimal(d.y()).subtract(decimal(c.y()));
            var px=decimal(c.x()).subtract(decimal(a.x()));var py=decimal(c.y()).subtract(decimal(a.y()));
            double t=px.multiply(ey).subtract(py.multiply(ex)).divide(dx.multiply(ey).subtract(dy.multiply(ex)),java.math.MathContext.DECIMAL128).doubleValue();hit=at(a,b,t);
        }
        return new Coordinate[]{hit,hit};
    }
    private static Coordinate at(Coordinate a,Coordinate b,double t){return t==0?a:t==1?b:new Coordinate(a.x()+t*(b.x()-a.x()),a.y()+t*(b.y()-a.y()));}
    private static boolean inside(Coordinate p,Coordinate a,Coordinate b){return Math.min(a.x(),b.x())<=p.x() && p.x()<=Math.max(a.x(),b.x()) && Math.min(a.y(),b.y())<=p.y() && p.y()<=Math.max(a.y(),b.y());}
    private static java.math.BigDecimal decimal(double value){return new java.math.BigDecimal(value);}
    private static int orientation(Coordinate a,Coordinate b,Coordinate c) {
        if(c.equals(a)||c.equals(b))return 0;
        double left=(b.x()-a.x())*(c.y()-a.y()),right=(b.y()-a.y())*(c.x()-a.x()),difference=left-right;
        if(Double.isFinite(difference) && Math.abs(difference)>8*Math.ulp(Math.abs(left)+Math.abs(right)))return difference>0?1:-1;
        return decimal(b.x()).subtract(decimal(a.x())).multiply(decimal(c.y()).subtract(decimal(a.y())))
            .subtract(decimal(b.y()).subtract(decimal(a.y())).multiply(decimal(c.x()).subtract(decimal(a.x())))).signum();
    }

    private static Crossings crossings(RoutePaths paths,boolean mercator) {
        List<Segment> segments = new ArrayList<>();
        for (var path : List.of(paths.up(), paths.down()))
            for (int i = 0; i < path.points().size() - 1; i++) {
                Point originalA = path.points().get(i), originalB = path.points().get(i + 1);
                if(mercator && (Math.abs(originalA.y())>=85.06 || Math.abs(originalB.y())>=85.06))return new Crossings(List.of(),false);
                Coordinate a=coordinate(originalA,mercator),b=coordinate(originalB,mercator);
                if (!a.equals(b))
                    segments.add(
                            new Segment(
                                    segments.size(),a,
                                    b,
                                    Math.min(a.x(), b.x()),
                                    Math.max(a.x(), b.x()),
                                    Math.min(a.y(), b.y()),
                                    Math.max(a.y(), b.y())));
            }
        if(segments.isEmpty())return new Crossings(List.of(),true);
        SegmentTree index=new SegmentTree(new ArrayList<>(segments));
        List<Point> result = new ArrayList<>();
        Budget budget=new Budget();
        for (var segment : segments) {
            List<Segment> possible=new ArrayList<>();
            index.query(segment,possible,budget);
            if(budget.exhausted)return new Crossings(List.copyOf(result),false);
            for (var other : possible) {
                if(!budget.step())return new Crossings(List.copyOf(result),false);
                Coordinate hit=intersection(segment.a(),segment.b(),other.a(),other.b());
                Point point=hit==null?null:geographic(hit,mercator);
                if (point != null
                        && result.stream()
                                .noneMatch(old -> RouteStopAlignment.distance(old, point) <= 0.05))
                    result.add(point);
            }
        }
        return new Crossings(List.copyOf(result), true);
    }

    static boolean noNewCrossings(RoutePaths original, RoutePaths candidate) {
        return noNewCrossings(original,candidate,false);
    }
    static boolean noNewRendererCrossings(RoutePaths original,RoutePaths candidate) {
        return noNewCrossings(original,candidate,true);
    }
    private static boolean noNewCrossings(RoutePaths original,RoutePaths candidate,boolean mercator) {
        if (original.equals(candidate)) return true;
        Crossings before = crossings(original,mercator), after = crossings(candidate,mercator);
        if (!before.complete() || !after.complete()) return false;
        return after.points().stream()
                .allMatch(
                        point ->
                                before.points().stream()
                                        .anyMatch(
                                                old ->
                                                        RouteStopAlignment.distance(old, point)
                                                                <= 0.05));
    }

    static Point intersection(Point a, Point b, Point c, Point d) {
        Coordinate hit=intersection(coordinate(a,false),coordinate(b,false),coordinate(c,false),coordinate(d,false));
        return hit==null?null:geographic(hit,false);
    }
    private static Coordinate intersection(Coordinate a,Coordinate b,Coordinate c,Coordinate d) {
        // A literal common endpoint is a touch, never a proper intersection.
        // Check before division: cancellation can otherwise give t=u just
        // below1 even for the exact same endpoint in the renderer frame.
        // Nonadjacent touches and overlaps remain checked by contact().
        if(a.equals(c) || a.equals(d) || b.equals(c) || b.equals(d))return null;
        double dx = b.x() - a.x(),
                dy = b.y() - a.y(),
                ex = d.x() - c.x(),
                ey = d.y() - c.y(),
                denominator = dx * ey - dy * ex;
        if (denominator == 0) return null;
        double px = c.x() - a.x(), py = c.y() - a.y();
        double t = (px * ey - py * ex) / denominator, u = (px * dy - py * dx) / denominator;
        if (t <= 0 || t >= 1 || u <= 0 || u >= 1) return null;
        return new Coordinate(a.x()+dx*t,a.y()+dy*t);
    }

    private static Coordinate coordinate(Point p,boolean mercator) {
        return mercator?new Coordinate(Math.toRadians(p.x())*6378137,6378137*Math.log(Math.tan(Math.PI/4+Math.toRadians(p.y())/2))):new Coordinate(p.x(),p.y());
    }
    private static Point geographic(Coordinate p,boolean mercator) {
        return mercator?new Point(Math.toDegrees(p.x()/6378137),Math.toDegrees(2*Math.atan(Math.exp(p.y()/6378137))-Math.PI/2)):new Point(p.x(),p.y());
    }
    private static boolean overlaps(Segment a,Segment b) {
        return a.minX()<=b.maxX() && b.minX()<=a.maxX() && a.minY()<=b.maxY() && b.minY()<=a.maxY();
    }
    private record EdgeKey(Point a,Point b) {}
    /** Atomic source-node removals introduce only a few new chords. Compare
     * those chords against the AFTER bundle, never an intermediate UP-only edit. */
    static boolean noNewShortcutCrossings(RoutePaths before,RoutePaths after,Crossings baseline,Crossings renderedBaseline) {
        if(!baseline.complete() || !renderedBaseline.complete())return false;
        Set<EdgeKey> existing=new HashSet<>();List<EdgeKey> candidate=new ArrayList<>();
        for(var path:List.of(before.up(),before.down()))for(int i=0;i<path.points().size()-1;i++)existing.add(new EdgeKey(path.points().get(i),path.points().get(i+1)));
        for(var path:List.of(after.up(),after.down()))for(int i=0;i<path.points().size()-1;i++)candidate.add(new EdgeKey(path.points().get(i),path.points().get(i+1)));
        long work=0;
        for(var added:candidate)if(!existing.contains(added))for(var other:candidate) {
            if(++work>2_000_000)return false;
            for(boolean mercator:List.of(false,true)) {
                if(mercator && (Math.abs(added.a().y())>=85.06 || Math.abs(added.b().y())>=85.06 || Math.abs(other.a().y())>=85.06 || Math.abs(other.b().y())>=85.06))return false;
                var p=intersection(coordinate(added.a(),mercator),coordinate(added.b(),mercator),coordinate(other.a(),mercator),coordinate(other.b(),mercator));
                if(p==null)continue;Point hit=geographic(p,mercator);var allowed=mercator?renderedBaseline:baseline;
                if(allowed.points().stream().noneMatch(old->RouteStopAlignment.distance(old,hit)<=.05))return false;
            }
        }
        return true;
    }
    private static final class Budget {
        long work;boolean exhausted;
        boolean step(){if(++work>2_000_000)exhausted=true;return !exhausted;}
    }
    /** Exact two-dimensional bounding boxes; vertical routes no longer spend
     * the entire budget comparing segments kilometres apart in latitude. */
    private static final class SegmentTree {
        final double minX,maxX,minY,maxY;final int minimumOrdinal;final List<Segment> leaf;final SegmentTree left,right;
        SegmentTree(List<Segment> segments) {
            minX=segments.stream().mapToDouble(Segment::minX).min().orElseThrow();maxX=segments.stream().mapToDouble(Segment::maxX).max().orElseThrow();
            minY=segments.stream().mapToDouble(Segment::minY).min().orElseThrow();maxY=segments.stream().mapToDouble(Segment::maxY).max().orElseThrow();
            minimumOrdinal=segments.stream().mapToInt(Segment::ordinal).min().orElseThrow();
            if(segments.size()<=16){leaf=List.copyOf(segments);left=null;right=null;}
            else {
                boolean x=maxX-minX>=maxY-minY;
                segments.sort(Comparator.comparingDouble((Segment s)->x?s.minX()+s.maxX():s.minY()+s.maxY()).thenComparingInt(Segment::ordinal));
                int half=segments.size()/2;leaf=List.of();left=new SegmentTree(new ArrayList<>(segments.subList(0,half)));right=new SegmentTree(new ArrayList<>(segments.subList(half,segments.size())));
            }
        }
        void query(Segment segment,List<Segment> found,Budget budget) {
            if(budget.exhausted || !budget.step() || minimumOrdinal>=segment.ordinal() || maxX<segment.minX() || minX>segment.maxX() || maxY<segment.minY() || minY>segment.maxY())return;
            if(left!=null){left.query(segment,found,budget);right.query(segment,found,budget);}
            else for(Segment candidate:leaf)if(candidate.ordinal()<segment.ordinal() && overlaps(segment,candidate))found.add(candidate);
        }
    }

    static Map<Point, Double> angles(RoutePaths paths) {
        Map<Point, Double> result = new HashMap<>();
        for (var path : List.of(paths.up(), paths.down()))
            for (int i = 1; i < path.points().size() - 1; i++)
                result.merge(
                        path.points().get(i),
                        angle(
                                path.points().get(i - 1),
                                path.points().get(i),
                                path.points().get(i + 1)),
                        Math::max);
        return result;
    }

    /** Only the replacement edge can create a new contact. The final bundle is checked separately. */
    static boolean safeShortcutContacts(List<Point> points, int target, List<Point> other, String direction,
            Contacts baseline, Contacts rendererBaseline) {
        if (target <= 0 || target >= points.size() - 1
                || !Set.of("UP", "DOWN").contains(direction)
                || !baseline.complete() || !rendererBaseline.complete()) return false;
        var budget = new Budget();
        for (boolean renderer : List.of(false, true)) {
            if (renderer && java.util.stream.Stream.concat(points.stream(), other.stream())
                    .anyMatch(p -> Math.abs(p.y()) >= 85.06)) return false;
            var a = coordinate(points.get(target - 1), renderer);
            var b = coordinate(points.get(target + 1), renderer);
            if (a.equals(b)) return false;
            // Match the full checker: zero-length edges do not separate adjacent segments.
            int previous = target - 2, next = target + 1;
            while (previous >= 0 && coordinate(points.get(previous), renderer)
                    .equals(coordinate(points.get(previous + 1), renderer))) previous--;
            while (next < points.size() - 1 && coordinate(points.get(next), renderer)
                    .equals(coordinate(points.get(next + 1), renderer))) next++;
            for (int path = 0; path < 2; path++) {
                var route = path == 0 ? points : other;
                String participant = path == 0 ? direction : direction.equals("UP") ? "DOWN" : "UP";
                for (int edge = 0; edge < route.size() - 1; edge++) {
                    if (!budget.step()) return false;
                    if (path == 0 && (edge == target - 1 || edge == target)) continue;
                    var c = coordinate(route.get(edge), renderer);
                    var d = coordinate(route.get(edge + 1), renderer);
                    if (c.equals(d) || Math.max(a.x(), b.x()) < Math.min(c.x(), d.x())
                            || Math.min(a.x(), b.x()) > Math.max(c.x(), d.x())
                            || Math.max(a.y(), b.y()) < Math.min(c.y(), d.y())
                            || Math.min(a.y(), b.y()) > Math.max(c.y(), d.y())) continue;
                    var hit = contact(a, b, c, d);
                    if (hit == null) continue;
                    boolean overlap = !hit[0].equals(hit[1]);
                    if (!overlap && path == 0 && (edge == previous || edge == next)) continue;
                    var value = new Contact(geographic(hit[0], renderer), geographic(hit[1], renderer),
                            overlap, direction, participant);
                    boolean allowed = false;
                    for (var old : (renderer ? rendererBaseline : baseline).values()) {
                        if (!budget.step()) return false;
                        if (containsContact(old, value)) { allowed = true; break; }
                    }
                    if (!allowed) return false;
                }
            }
        }
        return true;
    }

    static boolean safeShortcut(
            List<Point> points,
            int target,
            List<Point> other,
            Crossings baseline,
            Map<Point, Double> originalAngles) {
        Point a = points.get(target - 1), b = points.get(target + 1);
        if (target >= 2
                && angle(points.get(target - 2), a, b) > 150
                && originalAngles.getOrDefault(a, 0d) < 150) return false;
        if (target + 2 < points.size()
                && angle(a, b, points.get(target + 2)) > 150
                && originalAngles.getOrDefault(b, 0d) < 150) return false;
        for (int path = 0; path < 2; path++) {
            List<Point> route = path == 0 ? points : other;
            for (int edge = 0; edge < route.size() - 1; edge++) {
                if (path == 0 && (edge == target - 1 || edge == target)) continue;
                Point crossing = intersection(a, b, route.get(edge), route.get(edge + 1));
                if (crossing != null
                        && baseline.points().stream()
                                .noneMatch(
                                        old -> RouteStopAlignment.distance(old, crossing) <= 0.05))
                    return false;
            }
        }
        return true;
    }

    static boolean noNewSpike(RoutePaths original, RoutePaths candidate) {
        Map<Point, Double> previous = new HashMap<>();
        for (var path : List.of(original.up(), original.down()))
            for (int i = 1; i < path.points().size() - 1; i++)
                previous.merge(
                        path.points().get(i),
                        angle(
                                path.points().get(i - 1),
                                path.points().get(i),
                                path.points().get(i + 1)),
                        Math::max);
        for (var path : List.of(candidate.up(), candidate.down()))
            for (int i = 1; i < path.points().size() - 1; i++) {
                double turn =
                        angle(
                                path.points().get(i - 1),
                                path.points().get(i),
                                path.points().get(i + 1));
                if (turn > 150 && previous.getOrDefault(path.points().get(i), 0d) < 150)
                    return false;
            }
        return true;
    }

    static double angle(Point a, Point b, Point c) {
        double scale = Math.cos(Math.toRadians(b.y())),
                x = (b.x() - a.x()) * scale,
                y = b.y() - a.y(),
                u = (c.x() - b.x()) * scale,
                v = c.y() - b.y();
        double length = Math.hypot(x, y) * Math.hypot(u, v);
        return length == 0
                ? 0
                : Math.toDegrees(Math.acos(Math.max(-1, Math.min(1, (x * u + y * v) / length))));
    }
}
