package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.*;

/**
 * Display-only source-preserving fillets. The reference is the validated whole
 * node-clean bundle, not a guessed lane/corridor. No source point is overwritten.
 * The affine metric is deliberately conservative for the existing111320/cos
 * distance convention; it makes serialized WGS84 chords linear in this frame.
 */
public final class SourceBoundDisplayCurves {
    public static final String REVISION = "source-bound-quadratic-display-v2";
    public static final double SOURCE_LEASH_METRES = 2;
    private static final double CERTIFIED_LEASH = SOURCE_LEASH_METRES - .01;
    private static final double STOP_SUPPORT_METRES = 8;
    private SourceBoundDisplayCurves() {}

    public record Frame(double originX, double originY, double xMetresPerDegree,
                        double yMetresPerDegree, double minimumLatitude, double maximumLatitude) {
        XY xy(Point p) { return new XY((p.x()-originX)*xMetresPerDegree,(p.y()-originY)*yMetresPerDegree); }
        Point point(XY p) { return new Point(originX+p.x/xMetresPerDegree,originY+p.y/yMetresPerDegree); }
    }
    public record Correspondence(List<Point> referencePoints, List<Point> displayPoints,
                                 List<Double> referenceProgressAtDisplayVertices,
                                 double referenceLengthMetres, Frame metricFrame) {
        public Correspondence {
            referencePoints=List.copyOf(referencePoints);displayPoints=List.copyOf(displayPoints);
            referenceProgressAtDisplayVertices=List.copyOf(referenceProgressAtDisplayVertices);
            if(displayPoints.size()!=referenceProgressAtDisplayVertices.size())throw new IllegalArgumentException("DISPLAY_SOURCE_MAPPING_COUNT_MISMATCH");
            for(int i=1;i<referenceProgressAtDisplayVertices.size();i++)
                if(referenceProgressAtDisplayVertices.get(i)<=referenceProgressAtDisplayVertices.get(i-1))
                    throw new IllegalArgumentException("DISPLAY_SOURCE_MAPPING_NOT_STRICTLY_MONOTONE");
        }
    }
    public record Curvature(int sameSourceUniformSamples,int beforeOver60,int afterOver60,
                            double beforeMaximumDegrees,double afterMaximumDegrees,
                            boolean noNewFixedFiveMetreRegression) {}
    public record Plan(String referenceDomain,RoutePaths paths, Map<String,Correspondence> correspondences,
                       Map<String,Curvature> curvature,int curveCount,double maximumCertifiedDeviationMetres,
                       boolean continuousControlHullCertified,boolean serializedChordCorrespondenceCertified,
                       List<String> notes) {
        public Plan {correspondences=Map.copyOf(correspondences);curvature=Map.copyOf(curvature);notes=List.copyOf(notes);}
    }
    private record XY(double x,double y) {}
    private record Corner(List<Point> points,List<Double> sourceProgress,double bound) {}
    private record Built(Correspondence mapping,int curves,double bound,List<String> notes) {}

    public static Plan build(RouteGeometry geometry,RoutePaths reference,Map<Integer,Connection> referenceConnections) {
        var classified=EvidenceRoutePaths.classify(geometry).classifications();
        Map<Point,Integer> occurrences=new HashMap<>();
        for(var path:List.of(reference.up(),reference.down()))for(Point point:path.points())occurrences.merge(point,1,Integer::sum);
        Set<Point> shared=new HashSet<>();occurrences.forEach((point,count)->{if(count>1)shared.add(point);});
        var crossings=RouteDisplayTopology.crossings(reference);var renderedCrossings=RouteDisplayTopology.rendererCrossings(reference);
        var contacts=RouteDisplayTopology.contacts(reference);var rendererContacts=RouteDisplayTopology.rendererContacts(reference);
        List<Point> protectedCrossings=new ArrayList<>(crossings.points());protectedCrossings.addAll(renderedCrossings.points());
        for(var contact:contacts.values()){protectedCrossings.add(contact.first());protectedCrossings.add(contact.last());}
        for(var contact:rendererContacts.values()){protectedCrossings.add(contact.first());protectedCrossings.add(contact.last());}
        boolean topologyAvailable=crossings.complete() && renderedCrossings.complete() && contacts.complete() && rendererContacts.complete();
        Map<String,Correspondence> mappings=new LinkedHashMap<>();Map<String,Curvature> metrics=new LinkedHashMap<>();
        List<String> notes=new ArrayList<>();int count=0;double bound=0;
        for(String direction:List.of("UP","DOWN")) {
            var path=direction.equals("UP")?reference.up():reference.down();
            Built built=buildPath(geometry,direction,path.points(),classified,referenceConnections,shared,protectedCrossings,topologyAvailable);
            mappings.put(direction,built.mapping());count+=built.curves();bound=Math.max(bound,built.bound());notes.addAll(built.notes());
            if(built.curves()>0)metrics.put(direction,curvature(built.mapping()));
        }
        var paths=new RoutePaths(new RoutePaths.Path(mappings.get("UP").displayPoints()),new RoutePaths.Path(mappings.get("DOWN").displayPoints()));
        return new Plan("VALIDATED_NODE_BASELINE_METRIC_ARCLENGTH_NOT_ORIGINAL_RAW_PROGRESS",paths,mappings,metrics,count,bound,true,true,notes);
    }

    static Plan withTurnSupport(RouteGeometry geometry,Plan current,Map<Integer,Connection> connections) {
        var classified=EvidenceRoutePaths.classify(geometry).classifications();
        var contacts=RouteDisplayTopology.contacts(current.paths());var rendered=RouteDisplayTopology.rendererContacts(current.paths());
        if(!contacts.complete()||!rendered.complete())return current;
        List<Point> topology=new ArrayList<>();
        for(var c:contacts.values()){topology.add(c.first());topology.add(c.last());}
        for(var c:rendered.values()){topology.add(c.first());topology.add(c.last());}
        Map<String,Correspondence> maps=new LinkedHashMap<>(current.correspondences());
        int added=0;double bound=current.maximumCertifiedDeviationMetres();
        for(String direction:List.of("UP","DOWN")) {
            var old=maps.get(direction);var reference=old.referencePoints();
            var terminal=terminalSubsegmentSupport(geometry,direction,reference,connections,classified);
            var witness=TurnSubsegmentSupport.certify(geometry,direction,reference,connections,classified,terminal);
            if(witness.isEmpty()||reference.size()<3)continue;
            int i=witness.get().referenceEndpointIndex()==0?1:reference.size()-2;
            Point vertex=reference.get(i);
            var labels=classified.getOrDefault(vertex,List.of()).stream().filter(p->direction.equals(p.direction())).toList();
            if(labels.isEmpty()||labels.stream().anyMatch(p->p.type()!=3))continue;
            long occurrences=current.correspondences().values().stream().flatMap(m->m.referencePoints().stream()).filter(vertex::equals).count();
            if(occurrences!=1)continue;
            Line source=new Line(reference,old.metricFrame());double at=source.chain[i];
            double turn=angle(sub(source.xy.get(i),source.xy.get(i-1)),sub(source.xy.get(i+1),source.xy.get(i)));
            if(turn<8||turn>160)continue;
            List<Double> protectedProgress=new ArrayList<>();
            for(int j=0;j<reference.size();j++)if(classified.getOrDefault(reference.get(j),List.of()).stream().anyMatch(p->p.type()==1))protectedProgress.add(source.chain[j]);
            for(var stop:geometry.busStops())if(direction.equals(stop.direction())) {
                var c=connections.get(stop.stopSequence());
                if(c!=null)protectedProgress.add(source.chain[c.segmentIndex()]+c.segmentFraction()*(source.chain[c.segmentIndex()+1]-source.chain[c.segmentIndex()]));
            }
            double limit=.35*Math.min(source.chain[i]-source.chain[i-1],source.chain[i+1]-source.chain[i]);
            for(double cap:new double[]{12,8,5,3,1}) {
                double trim=Math.min(limit,cap),lo=at-trim,hi=at+trim;
                if(trim<.5||protectedProgress.stream().anyMatch(s->lo-STOP_SUPPORT_METRES<=s&&s<=hi+STOP_SUPPORT_METRES))continue;
                if(geometry.busStops().stream().anyMatch(s->s.point()!=null&&distance(old.metricFrame().xy(s.point()),source.xy.get(i))<=STOP_SUPPORT_METRES))continue;
                if(topology.stream().anyMatch(p->distance(old.metricFrame().xy(p),source.xy.get(i))<=trim+.05))continue;
                if(!AnchorPreservingDisplayCurves.straightOnSource(old,lo,at)||!AnchorPreservingDisplayCurves.straightOnSource(old,at,hi))continue;
                var patch=corner(source,i,trim,old.metricFrame());if(patch==null)continue;
                TreeMap<Double,Point> points=new TreeMap<>();
                for(int j=0;j<old.displayPoints().size();j++)points.put(old.referenceProgressAtDisplayVertices().get(j),old.displayPoints().get(j));
                points.subMap(lo,true,hi,true).clear();
                for(int j=0;j<patch.points().size();j++)points.put(patch.sourceProgress().get(j),patch.points().get(j));
                var candidate=new Correspondence(reference,new ArrayList<>(points.values()),new ArrayList<>(points.keySet()),old.referenceLengthMetres(),old.metricFrame());
                if(!AnchorPreservingDisplayCurves.noNewCurvature(old,candidate))continue;
                maps.put(direction,candidate);added++;bound=Math.max(bound,patch.bound());break;
            }
        }
        if(added==0)return current;
        var paths=new RoutePaths(new RoutePaths.Path(maps.get("UP").displayPoints()),new RoutePaths.Path(maps.get("DOWN").displayPoints()));
        if(!endpointsPreserved(current.paths(),paths)||!RouteDisplayTopology.noNewCrossings(current.paths(),paths)
                ||!RouteDisplayTopology.noNewRendererCrossings(current.paths(),paths)||!RouteDisplayTopology.noNewContacts(current.paths(),paths)
                ||!RouteDisplayTopology.noNewSpike(current.paths(),paths))return current;
        Map<String,Curvature> metrics=new LinkedHashMap<>();for(var entry:maps.entrySet())metrics.put(entry.getKey(),curvature(entry.getValue()));
        var notes=new ArrayList<>(current.notes());notes.add("TURN_SUBSEGMENT_FILLET:EXACT_ORDERED_SOURCE_OCCURRENCE");
        return new Plan(current.referenceDomain(),paths,maps,metrics,current.curveCount()+added,bound,
                current.continuousControlHullCertified(),current.serializedChordCorrespondenceCertified(),notes);
    }

    private static Built buildPath(RouteGeometry geometry,String direction,List<Point> reference,
            Map<Point,List<com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint>> classified,
            Map<Integer,Connection> connections,Set<Point> shared,List<Point> protectedCrossings,boolean topologyAvailable) {
        var terminalSupport=terminalSubsegmentSupport(geometry,direction,reference,connections,classified);
        Frame frame=frame(reference);Line source=new Line(reference,frame);List<Double> protectedProgress=new ArrayList<>();
        for(int i=0;i<reference.size();i++) {
            var labels=classified.getOrDefault(reference.get(i),List.of());
            if(labels.stream().anyMatch(p->p.type()==1))protectedProgress.add(source.chain[i]);
        }
        for(var stop:geometry.busStops())if(direction.equals(stop.direction())) {
            var connection=connections.get(stop.stopSequence());
            if(connection!=null && connection.segmentIndex()>=0 && connection.segmentIndex()+1<reference.size()) {
                int i=connection.segmentIndex();protectedProgress.add(source.chain[i]+connection.segmentFraction()*(source.chain[i+1]-source.chain[i]));
            }
        }
        List<Point> output=new ArrayList<>();List<Double> mapping=new ArrayList<>();List<String> notes=new ArrayList<>();
        append(output,mapping,reference.getFirst(),0);int curves=0;double bound=0;
        for(int i=1;i<reference.size()-1;i++) {
            var labels=classified.getOrDefault(reference.get(i),List.of()).stream().filter(p->direction.equals(p.direction())).toList();
            boolean boundShape=topologyAvailable && !shared.contains(reference.get(i)) && !labels.isEmpty() && labels.stream().allMatch(p->p.type()==3)
                    && (classified.containsKey(reference.get(i-1)) || terminalSupport.isPresent() && terminalSupport.get().referenceEndpointIndex()==i-1)
                    && (classified.containsKey(reference.get(i+1)) || terminalSupport.isPresent() && terminalSupport.get().referenceEndpointIndex()==i+1);
            Corner corner=null;
            if(boundShape) {
                double turn=angle(sub(source.xy.get(i),source.xy.get(i-1)),sub(source.xy.get(i+1),source.xy.get(i)));
                if(turn>=8 && turn<=160) {
                    double limit=.35*Math.min(source.chain[i]-source.chain[i-1],source.chain[i+1]-source.chain[i]);
                    TreeSet<Double> trims=new TreeSet<>(Comparator.reverseOrder());
                    for(double value:new double[]{12,8,5,3,1})trims.add(Math.min(limit,value));
                    for(double trim:trims) {
                        if(trim<.5)continue;
                        double start=source.chain[i]-trim,end=source.chain[i]+trim;
                        if(protectedProgress.stream().anyMatch(s->start-STOP_SUPPORT_METRES<=s && s<=end+STOP_SUPPORT_METRES))continue;
                        XY vertex=source.xy.get(i);
                        if(geometry.busStops().stream().anyMatch(s->s.point()!=null && distance(frame.xy(s.point()),vertex)<=STOP_SUPPORT_METRES))continue;
                        if(protectedCrossings.stream().anyMatch(p->distance(frame.xy(p),vertex)<=trim+.05))continue;
                        corner=corner(source,i,trim,frame);
                        if(corner!=null && localCurvatureSafe(source,corner,frame))break;
                        corner=null;
                    }
                }
            }
            if(corner==null)append(output,mapping,reference.get(i),source.chain[i]);
            else {
                for(int j=0;j<corner.points().size();j++)append(output,mapping,corner.points().get(j),corner.sourceProgress().get(j));
                curves++;bound=Math.max(bound,corner.bound());
            }
        }
        append(output,mapping,reference.getLast(),source.length());
        terminalSupport.ifPresent(proof->notes.add(direction+":TERMINAL_SUBSEGMENT_SUPPORT_CERTIFIED:sourceSegment="+proof.sourceSegmentIndex()+":fraction="+proof.sourceFraction()));
        if(curves==0)notes.add(direction+":NO_ELIGIBLE_SOURCE_BOUND_CURVE");
        if(!topologyAvailable)notes.add(direction+":BASELINE_CROSSING_PROTECTION_UNAVAILABLE");
        return new Built(new Correspondence(reference,output,mapping,source.length(),frame),curves,bound,notes);
    }

    /** A derived endpoint is not assigned a TYPE role. This witness supports
     * only the directed remainder of one actual original-source segment. */
    record TerminalSubsegmentSupport(int referenceEndpointIndex,int sourceSegmentIndex,double sourceFraction) {}

    static Optional<TerminalSubsegmentSupport> terminalSubsegmentSupport(RouteGeometry geometry,String direction,
            List<Point> reference,Map<Integer,Connection> referenceConnections,
            Map<Point,List<com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint>> classified) {
        if(geometry.paths()==null || reference.size()<2 || !(direction.equals("UP") || direction.equals("DOWN")))return Optional.empty();
        boolean start=direction.equals("UP");
        var original=start?geometry.paths().up().points():geometry.paths().down().points();
        int endpoint=start?0:reference.size()-1;
        Point boundary=reference.get(endpoint);
        // Ordinary source endpoints already use their own classification.
        if(boundary.equals(start?original.getFirst():original.getLast()))return Optional.empty();
        var visits=geometry.busStops().stream().filter(s->direction.equals(s.direction())).toList();
        if(visits.isEmpty())return Optional.empty();
        var terminal=start?visits.getFirst():visits.getLast();
        if(terminal.point()==null)return Optional.empty();
        Connection atBoundary=referenceConnections.get(terminal.stopSequence());
        int finalEdge=start?0:reference.size()-2;
        if(atBoundary==null || atBoundary.segmentIndex()!=finalEdge)return Optional.empty();
        // Reprojection onto the retained subsegment can produce a distinct
        // literal position even a nanometre inside its endpoint. Preserve that
        // occurrence: verify its actual all-visits result, never snap it to0/1.
        var finalAligned=RouteStopAlignment.align(reference,visits,start);
        if(!finalAligned.usable() || !atBoundary.equals(finalAligned.connections().get(terminal.stopSequence()))
                || !point(atBoundary).equals(RouteStopAlignment.interpolate(reference.get(finalEdge),reference.get(finalEdge+1),atBoundary.segmentFraction())))return Optional.empty();
        // Re-run the existing all-visits source alignment: no nearest-segment
        // search, coordinate dictionary or caller-selected source occurrence.
        var aligned=RouteStopAlignment.align(original,visits,start);
        if(!aligned.usable())return Optional.empty();
        Connection actual=aligned.connections().get(terminal.stopSequence());
        if(actual==null || actual.segmentIndex()<0 || actual.segmentIndex()+1>=original.size()
                || !(actual.segmentFraction()>0 && actual.segmentFraction()<1))return Optional.empty();
        int edge=actual.segmentIndex();Point a=original.get(edge),b=original.get(edge+1);
        if(!boundary.equals(point(actual)) || !boundary.equals(RouteStopAlignment.interpolate(a,b,actual.segmentFraction())))return Optional.empty();
        if(RouteStopAlignment.distance(terminal.point(),boundary)>15)return Optional.empty();
        double overhang=0;
        if(start) {
            for(int i=1;i<=edge;i++)overhang+=RouteStopAlignment.distance(original.get(i-1),original.get(i));
            overhang+=RouteStopAlignment.distance(a,b)*actual.segmentFraction();
        } else {
            overhang+=RouteStopAlignment.distance(a,b)*(1-actual.segmentFraction());
            for(int i=edge+2;i<original.size();i++)overhang+=RouteStopAlignment.distance(original.get(i-1),original.get(i));
        }
        if(!(overhang>.01 && overhang<=100))return Optional.empty();
        // Both original endpoints need real direction-bound evidence. Repeated
        // endpoints are intentionally unsupported without richer lineage; equal
        // coordinates cannot stand in for a different route occurrence.
        for(Point p:List.of(a,b)) {
            var roles=classified.getOrDefault(p,List.of()).stream().filter(t->direction.equals(t.direction())).toList();
            if(roles.isEmpty() || original.stream().filter(p::equals).count()!=1)return Optional.empty();
        }
        if(!reference.get(start?1:reference.size()-2).equals(start?b:a))return Optional.empty();
        // The whole retained side must remain an ordered deletion of that
        // directed source occurrence. Reversal, insertion and other branches
        // cannot borrow this endpoint witness.
        if(start) {
            int source=edge+1;
            for(int i=1;i<reference.size();i++) {
                while(source<original.size() && !original.get(source).equals(reference.get(i)))source++;
                if(source==original.size())return Optional.empty();source++;
            }
            if(!reference.getLast().equals(original.getLast()))return Optional.empty();
        } else {
            int source=0;
            for(int i=0;i<reference.size()-1;i++) {
                while(source<=edge && !original.get(source).equals(reference.get(i)))source++;
                if(source>edge)return Optional.empty();source++;
            }
            if(!reference.getFirst().equals(original.getFirst()))return Optional.empty();
        }
        return Optional.of(new TerminalSubsegmentSupport(endpoint,edge,actual.segmentFraction()));
    }
    private static Point point(Connection c){return new Point(c.point().x(),c.point().y());}

    private static Corner corner(Line source,int vertex,double trim,Frame frame) {
        XY b=source.xy.get(vertex),p=source.at(source.chain[vertex]-trim),q=source.at(source.chain[vertex]+trim);
        XY midpoint=new XY((p.x+2*b.x+q.x)/4,(p.y+2*b.y+q.y)/4);
        double bound=distance(midpoint,b);
        // One exact de Casteljau split: left controls are P,(P+B)/2,M;
        // right controls M,(B+Q)/2,Q. Their difference from the corresponding
        // linear source halves has controls0,0,M-B or M-B,0,0. A convex ball
        // bounds the entire continuous curve at the SAME source progression.
        // Each half's control hull also lies in its own finite-source-segment tube.
        if(bound>CERTIFIED_LEASH)return null;
        int samples=Math.max(12,(int)Math.ceil(trim*2/.2));TreeSet<Double> parameters=new TreeSet<>();
        for(int i=0;i<samples;i++)parameters.add((double)i/(samples-1));
        parameters.add(.5); // Serialized chords never straddle the source kink.
        List<Point> points=new ArrayList<>();List<Double> progress=new ArrayList<>();double maximum=bound;
        for(double t:parameters) {
            double one=1-t;XY c=new XY(one*one*p.x+2*one*t*b.x+t*t*q.x,one*one*p.y+2*one*t*b.y+t*t*q.y);
            double s=t==.5?source.chain[vertex]:source.chain[vertex]-trim+2*trim*t;
            Point serialized=frame.point(c);double actual=distance(frame.xy(serialized),source.at(s));
            if(actual>CERTIFIED_LEASH)return null;
            points.add(serialized);progress.add(s);maximum=Math.max(maximum,actual);
        }
        double originalRendererError=Math.max(
            mercatorChordError(source.points.get(vertex-1),source.points.get(vertex),1-trim/(source.chain[vertex]-source.chain[vertex-1]),1,frame),
            mercatorChordError(source.points.get(vertex),source.points.get(vertex+1),0,trim/(source.chain[vertex+1]-source.chain[vertex]),frame));
        double candidateRendererError=0;
        for(int i=1;i<points.size();i++)candidateRendererError=Math.max(candidateRendererError,mercatorChordError(points.get(i-1),points.get(i),0,1,frame));
        maximum+=originalRendererError+candidateRendererError;
        if(!Double.isFinite(maximum) || maximum>SOURCE_LEASH_METRES)return null;
        // Between each serialized endpoint pair the matching source half is
        // linear. The endpoint-difference vectors lie in the same convex ball,
        // proving every emitted chord, not just the sampled analytic points.
        return new Corner(points,progress,maximum);
    }

    /** Try the existing smaller trim candidates when a geometrically bounded
     * corner would fail the unchanged fixed5m curvature guard. Testing the local
     * source context avoids rejecting every good corner on the whole route. */
    private static boolean localCurvatureSafe(Line source,Corner corner,Frame frame) {
        double first=corner.sourceProgress().getFirst(),last=corner.sourceProgress().getLast();
        double context=last-first+10,start=Math.max(0,first-context),end=Math.min(source.length(),last+context);
        List<Point> points=new ArrayList<>();List<Double> positions=new ArrayList<>();
        append(points,positions,frame.point(source.at(start)),start);
        for(int i=0;i<source.points.size();i++)if(start<source.chain[i] && source.chain[i]<first)append(points,positions,source.points.get(i),source.chain[i]);
        for(int i=0;i<corner.points().size();i++)append(points,positions,corner.points().get(i),corner.sourceProgress().get(i));
        for(int i=0;i<source.points.size();i++)if(last<source.chain[i] && source.chain[i]<end)append(points,positions,source.points.get(i),source.chain[i]);
        append(points,positions,frame.point(source.at(end)),end);
        Line candidate=new Line(points,frame);double[] mapping=positions.stream().mapToDouble(Double::doubleValue).toArray();
        for(double s=Math.ceil(start+5);s<end-5;s+=1) {
            double before=source.angleAt(s),after=candidate.angleAt(interpolate(s,mapping,candidate.chain));
            if(after>60 && after>before+5)return false;
        }
        for(int i=1;i<points.size()-1;i++) {
            double before=source.angleAt(mapping[i]),after=candidate.angleAt(candidate.chain[i]);
            if(after>60 && after>before+5)return false;
        }
        return true;
    }

    /** Inverse-Mercator chord versus linear-latitude chord. Linear interpolation
     * remainder <= max|f''|/2 * deltaPhi^2*u(1-u), with f'=sec(phi),
     * f''=sec(phi)tan(phi); divide by min f' for the inverse error. This
     * analytic bound, not dense samples, also covers the actual map chords. */
    private static double mercatorChordError(Point a,Point b,double first,double last,Frame frame) {
        double x=Math.toRadians(a.y()),y=Math.toRadians(b.y()),maximum=Math.max(Math.abs(x),Math.abs(y));
        if(maximum>=Math.toRadians(85.06))return Double.POSITIVE_INFINITY;
        double minimum=x<=0 && y>=0 || y<=0 && x>=0?0:Math.min(Math.abs(x),Math.abs(y));
        double derivativeRatio=Math.cos(minimum)*Math.tan(maximum)/Math.cos(maximum);
        double product=Math.max(first*(1-first),last*(1-last));if(first<=.5 && last>=.5)product=.25;
        return .5*derivativeRatio*(y-x)*(y-x)*product*frame.yMetresPerDegree()*180/Math.PI;
    }

    public static Curvature curvature(Correspondence mapping) {
        Line reference=new Line(mapping.referencePoints(),mapping.metricFrame()),display=new Line(mapping.displayPoints(),mapping.metricFrame());
        double[] sourceAtVertex=mapping.referenceProgressAtDisplayVertices().stream().mapToDouble(Double::doubleValue).toArray();
        int samples=0,beforeCount=0,afterCount=0;double beforeMax=0,afterMax=0;boolean noRegression=true;
        // The count uses one immutable SOURCE lattice. More Bezier vertices
        // cannot manufacture extra independent curvature defects/successes.
        for(double s=5;s<reference.length()-5;s+=1) {
            double at=interpolate(s,sourceAtVertex,display.chain);double before=reference.angleAt(s),after=display.angleAt(at);
            samples++;if(before>60)beforeCount++;if(after>60)afterCount++;beforeMax=Math.max(beforeMax,before);afterMax=Math.max(afterMax,after);
            if(after>60 && after>before+5)noRegression=false;
        }
        // Native vertices are additional guard locations, never extra count
        // denominator entries. This catches a narrow new join between1m samples.
        for(int i=1;i<display.xy.size()-1;i++) {
            double before=reference.angleAt(sourceAtVertex[i]),after=display.angleAt(display.chain[i]);
            if(after>60 && after>before+5)noRegression=false;
        }
        return new Curvature(samples,beforeCount,afterCount,beforeMax,afterMax,noRegression);
    }

    public static boolean endpointsPreserved(RoutePaths reference,RoutePaths candidate) {
        return reference.up().points().getFirst().equals(candidate.up().points().getFirst())
            &&reference.up().points().getLast().equals(candidate.up().points().getLast())
            &&reference.down().points().getFirst().equals(candidate.down().points().getFirst())
            &&reference.down().points().getLast().equals(candidate.down().points().getLast());
    }
    private static Frame frame(List<Point> points) {
        double minimum=points.stream().mapToDouble(Point::y).min().orElseThrow(),maximum=points.stream().mapToDouble(Point::y).max().orElseThrow();
        double cosine=minimum<=0 && maximum>=0?1:Math.max(Math.cos(Math.toRadians(minimum)),Math.cos(Math.toRadians(maximum)));
        return new Frame(points.getFirst().x(),points.getFirst().y(),111320*cosine,111320,minimum,maximum);
    }
    private static void append(List<Point> points,List<Double> progress,Point point,double source) {
        if(!points.isEmpty() && points.getLast().equals(point)) {progress.set(progress.size()-1,source);return;}
        points.add(point);progress.add(source);
    }
    private static double interpolate(double position,double[] domain,double[] values) {
        if(position<=domain[0])return values[0];if(position>=domain[domain.length-1])return values[values.length-1];
        int index=Arrays.binarySearch(domain,position);if(index>=0)return values[index];index=-index-2;
        return values[index]+(position-domain[index])/(domain[index+1]-domain[index])*(values[index+1]-values[index]);
    }
    private static XY sub(XY a,XY b){return new XY(a.x-b.x,a.y-b.y);}
    private static double distance(XY a,XY b){return Math.hypot(a.x-b.x,a.y-b.y);}
    private static double angle(XY a,XY b){double n=Math.hypot(a.x,a.y)*Math.hypot(b.x,b.y);return n==0?0:Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,(a.x*b.x+a.y*b.y)/n))));}
    private static final class Line {
        final List<Point> points;final List<XY> xy;final double[] chain;
        Line(List<Point> points,Frame frame) {
            this.points=points;
            xy=points.stream().map(frame::xy).toList();chain=new double[xy.size()];
            for(int i=1;i<xy.size();i++)chain[i]=chain[i-1]+distance(xy.get(i-1),xy.get(i));
        }
        double length(){return chain[chain.length-1];}
        XY at(double position) {
            if(position<=0)return xy.getFirst();if(position>=length())return xy.getLast();
            int i=Arrays.binarySearch(chain,position);if(i>=0)return xy.get(i);i=-i-2;
            double fraction=(position-chain[i])/(chain[i+1]-chain[i]);XY a=xy.get(i),b=xy.get(i+1);
            return new XY(a.x+fraction*(b.x-a.x),a.y+fraction*(b.y-a.y));
        }
        double angleAt(double position){XY p=at(position);return angle(sub(p,at(Math.max(0,position-5))),sub(at(Math.min(length(),position+5)),p));}
    }
}
