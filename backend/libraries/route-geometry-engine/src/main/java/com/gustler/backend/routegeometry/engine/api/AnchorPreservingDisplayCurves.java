package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.*;

/** Display-only cubics through exact source/visit anchors. No station GPS is a control point. */
public final class AnchorPreservingDisplayCurves {
    public static final String REVISION="anchor-preserving-piecewise-cubic-v1";
    private static final double LEASH=2,CONTROL_LEASH=1.99,NUMERICAL=1e-6;
    private AnchorPreservingDisplayCurves() {}
    public record AnchorProof(String direction,double sourceProgress,double supportStart,double supportEnd,
            int exactAnchorCount,double maximumCertifiedDeviationMetres,boolean commonTangentCertified,
            boolean strictlyForwardDerivativeHull,boolean continuousControlHullCertified,
            boolean serializedChordCorrespondenceCertified) {}
    public record Result(SourceBoundDisplayCurves.Plan plan,int anchoredCornerCount,List<AnchorProof> proofs,List<String> notes) {
        public Result {proofs=List.copyOf(proofs);notes=List.copyOf(notes);}
    }
    private record XY(double x,double y) {}
    private record Knot(double s,Point p) {}
    private record Piece(List<Point> points,List<Double> source,double bound) {}
    private record Patch(double start,double end,List<Point> points,List<Double> source,AnchorProof proof) {}
    private record Built(SourceBoundDisplayCurves.Correspondence mapping,List<AnchorProof> proofs) {}

    public static Result build(RouteGeometry geometry,SourceBoundDisplayCurves.Plan current,Map<Integer,Connection> baselineConnections) {
        return build(geometry,current,baselineConnections,false);
    }
    static Result buildWithTurnSupport(RouteGeometry geometry,SourceBoundDisplayCurves.Plan current,Map<Integer,Connection> baselineConnections) {
        return build(geometry,current,baselineConnections,true);
    }
    private static Result build(RouteGeometry geometry,SourceBoundDisplayCurves.Plan current,Map<Integer,Connection> baselineConnections,boolean supportTurn) {
        try {return checkedBuild(geometry,current,baselineConnections,supportTurn);}
        catch(IllegalArgumentException|ArithmeticException e) {return new Result(current,0,List.of(),List.of("ANCHOR_CURVE_FALLBACK:"+e.getMessage()));}
    }
    private static Result checkedBuild(RouteGeometry geometry,SourceBoundDisplayCurves.Plan current,Map<Integer,Connection> connections,boolean supportTurn) {
        require(current.referenceDomain().contains("NODE_BASELINE"),"UNSUPPORTED_REFERENCE_DOMAIN");
        var classified=EvidenceRoutePaths.classify(geometry).classifications();
        Map<Point,Integer> counts=new HashMap<>();for(var m:current.correspondences().values())for(var p:m.referencePoints())counts.merge(p,1,Integer::sum);
        Set<Point> shared=new HashSet<>();counts.forEach((p,n)->{if(n>1)shared.add(p);});
        var crossings=RouteDisplayTopology.crossings(current.paths());var rendererCrossings=RouteDisplayTopology.rendererCrossings(current.paths());
        var contacts=RouteDisplayTopology.contacts(current.paths());var rendererContacts=RouteDisplayTopology.rendererContacts(current.paths());
        if(!crossings.complete()||!rendererCrossings.complete()||!contacts.complete()||!rendererContacts.complete())return new Result(current,0,List.of(),List.of("ANCHOR_TOPOLOGY_UNAVAILABLE"));
        List<Point> protectedTopology=new ArrayList<>(crossings.points());protectedTopology.addAll(rendererCrossings.points());
        for(var c:contacts.values()){protectedTopology.add(c.first());protectedTopology.add(c.last());}
        for(var c:rendererContacts.values()){protectedTopology.add(c.first());protectedTopology.add(c.last());}
        Map<String,SourceBoundDisplayCurves.Correspondence> mappings=new LinkedHashMap<>();List<AnchorProof> proofs=new ArrayList<>();
        for(String direction:List.of("UP","DOWN")) {
            var old=current.correspondences().get(direction);require(old!=null,"MISSING_DIRECTION_MAPPING");
            var built=direction(geometry,direction,old,connections,classified,shared,protectedTopology,supportTurn);
            mappings.put(direction,built.mapping());proofs.addAll(built.proofs());
        }
        if(proofs.isEmpty())return new Result(current,0,List.of(),List.of("NO_CERTIFIED_ANCHOR_CURVE"));
        var paths=new RoutePaths(new RoutePaths.Path(mappings.get("UP").displayPoints()),new RoutePaths.Path(mappings.get("DOWN").displayPoints()));
        require(SourceBoundDisplayCurves.endpointsPreserved(current.paths(),paths),"ANCHOR_ENDPOINT_CHANGED");
        require(RouteDisplayTopology.noNewCrossings(current.paths(),paths),"ANCHOR_NEW_CROSSING");
        require(RouteDisplayTopology.noNewRendererCrossings(current.paths(),paths),"ANCHOR_NEW_RENDERER_CROSSING");
        require(RouteDisplayTopology.noNewContacts(current.paths(),paths),"ANCHOR_NEW_TOUCH_OR_OVERLAP");
        require(RouteDisplayTopology.noNewSpike(current.paths(),paths),"ANCHOR_NEW_SPIKE");
        Map<String,SourceBoundDisplayCurves.Curvature> metrics=new LinkedHashMap<>();double finalSerializedBound=0;
        for(String direction:List.of("UP","DOWN")) {
            var next=mappings.get(direction);finalSerializedBound=Math.max(finalSerializedBound,serializedBound(next));var metric=SourceBoundDisplayCurves.curvature(next);
            require(metric.noNewFixedFiveMetreRegression(),"ANCHOR_SOURCE_FIXED_FIVE_METRE_REGRESSION");
            require(noNewCurvature(current.correspondences().get(direction),next),"ANCHOR_CURRENT_FIXED_FIVE_METRE_REGRESSION");metrics.put(direction,metric);
        }
        double bound=Math.max(finalSerializedBound,Math.max(current.maximumCertifiedDeviationMetres(),proofs.stream().mapToDouble(AnchorProof::maximumCertifiedDeviationMetres).max().orElse(0)));
        List<String> notes=new ArrayList<>(current.notes());notes.add(REVISION+":EXACT_SOURCE_AND_VISIT_KNOTS");
        if(supportTurn)notes.add("TURN_SUBSEGMENT_CURVE:EXACT_ORDERED_SOURCE_OCCURRENCE");
        var plan=new SourceBoundDisplayCurves.Plan(current.referenceDomain(),paths,mappings,metrics,current.curveCount()+proofs.size(),bound,
                current.continuousControlHullCertified(),current.serializedChordCorrespondenceCertified(),notes);
        return new Result(plan,proofs.size(),proofs,notes);
    }
    private static Built direction(RouteGeometry geometry,String direction,SourceBoundDisplayCurves.Correspondence old,
            Map<Integer,Connection> connections,Map<Point,List<com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint>> classified,
            Set<Point> shared,List<Point> topology,boolean supportTurn) {
        var frame=old.metricFrame();var source=new Line(old.referencePoints(),frame);TreeMap<Double,Point> anchors=new TreeMap<>();
        for(int i=0;i<source.points.size();i++)if(classified.getOrDefault(source.points.get(i),List.of()).stream().anyMatch(p->direction.equals(p.direction())&&p.type()==1))anchor(anchors,source.chain[i],source.points.get(i));
        for(var stop:geometry.busStops())if(direction.equals(stop.direction())&&stop.point()!=null) {
            var c=connections.get(stop.stopSequence());require(c!=null,"MISSING_BASELINE_VISIT_CONNECTION");int i=c.segmentIndex();double f=c.segmentFraction();
            require(i>=0&&i+1<source.points.size()&&Double.isFinite(f)&&f>=0&&f<=1,"INVALID_BASELINE_VISIT_CONNECTION");
            var a=source.points.get(i);var b=source.points.get(i+1);var p=new Point(c.point().x(),c.point().y());
            Point expected=new Point(a.x()+(b.x()-a.x())*f,a.y()+(b.y()-a.y())*f);
            require(p.equals(expected),"BASELINE_VISIT_POINT_NOT_EXACT_SOURCE_INTERPOLATION");
            double s=p.equals(a)?source.chain[i]:p.equals(b)?source.chain[i+1]:source.chain[i]+f*(source.chain[i+1]-source.chain[i]);
            anchor(anchors,s,p);
        }
        var terminalSupport=SourceBoundDisplayCurves.terminalSubsegmentSupport(geometry,direction,source.points,connections,classified);
        var turnSupport=supportTurn?TurnSubsegmentSupport.certify(geometry,direction,source.points,connections,classified,terminalSupport):Optional.<TurnSubsegmentSupport.Witness>empty();
        List<Patch> patches=new ArrayList<>();
        for(int i=1;i<source.points.size()-1;i++) {
            Point b=source.points.get(i);double at=source.chain[i];
            var labels=classified.getOrDefault(b,List.of()).stream().filter(p->direction.equals(p.direction())).toList();
            if(labels.isEmpty()||labels.stream().anyMatch(p->p.type()!=1&&p.type()!=3)||shared.contains(b))continue;
            boolean previousBound=classified.getOrDefault(source.points.get(i-1),List.of()).stream().anyMatch(p->direction.equals(p.direction()))||supportedEndpoint(i-1,terminalSupport,turnSupport);
            boolean nextBound=classified.getOrDefault(source.points.get(i+1),List.of()).stream().anyMatch(p->direction.equals(p.direction()))||supportedEndpoint(i+1,terminalSupport,turnSupport);
            if(!previousBound||!nextBound)continue;
            if(supportTurn && (turnSupport.isEmpty() || Math.abs(turnSupport.get().referenceEndpointIndex()-i)!=1))continue;
            int existing=Collections.binarySearch(old.referenceProgressAtDisplayVertices(),at);
            if(existing<0||!old.displayPoints().get(existing).equals(b))continue; // Already replaced by a certified v3 fillet.
            XY u=unit(sub(source.xy.get(i),source.xy.get(i-1))),v=unit(sub(source.xy.get(i+1),source.xy.get(i)));double turn=angle(u,v);
            if(turn<8||turn>=160)continue;
            XY tangent=unit(add(u,v));double speed=Math.min(dot(u,tangent),dot(v,tangent));if(speed<=0)continue;
            Patch accepted=null;
            for(double cap:new double[]{12,8,5,3,1,.5,.25,.1}) {
                double lo=at-Math.min(cap,.35*(at-source.chain[i-1])),hi=at+Math.min(cap,.35*(source.chain[i+1]-at));
                var before=anchors.lowerEntry(at);var after=anchors.higherEntry(at);
                if(before!=null&&before.getKey()>lo)lo=before.getKey();if(after!=null&&after.getKey()<hi)hi=after.getKey();
                if(at-lo<NUMERICAL||hi-at<NUMERICAL)continue;
                if(!straight(old,source,lo,at)||!straight(old,source,at,hi))continue;
                double radius=Math.max(at-lo,hi-at)+.05;
                if(topology.stream().anyMatch(p->distance(xy(p,frame),xy(b,frame))<=radius))continue;
                Point a=anchors.getOrDefault(lo,source.pointAt(lo)),c=anchors.getOrDefault(hi,source.pointAt(hi));
                var left=piece(source,lo,at,a,b,u,1,tangent,speed,tangent);var right=piece(source,at,hi,b,c,tangent,speed,v,1,tangent);
                if(left==null||right==null)continue;
                List<Point> points=new ArrayList<>(left.points());points.addAll(right.points().subList(1,right.points().size()));
                List<Double> positions=new ArrayList<>(left.source());positions.addAll(right.source().subList(1,right.source().size()));
                int preserved=anchors.subMap(lo,true,hi,true).size();double bound=Math.max(left.bound(),right.bound());
                var trial=new Patch(lo,hi,points,positions,new AnchorProof(direction,at,lo,hi,preserved,bound,true,true,true,true));
                if(!localCurvatureSafe(old,source,patches,trial))continue;
                accepted=trial;break;
            }
            if(accepted!=null&&(patches.isEmpty()||patches.getLast().end()<accepted.start()))patches.add(accepted);
        }
        if(patches.isEmpty())return new Built(old,List.of());
        TreeMap<Double,Point> output=new TreeMap<>();for(int i=0;i<old.displayPoints().size();i++)output.put(old.referenceProgressAtDisplayVertices().get(i),old.displayPoints().get(i));
        for(var patch:patches) {output.subMap(patch.start(),true,patch.end(),true).clear();for(int i=0;i<patch.points().size();i++)output.put(patch.source().get(i),patch.points().get(i));}
        // Insert the actual baseline anchor point, never its GPS marker or a nearest road foot.
        for(var a:anchors.entrySet()) {
            require(distance(xy(at(old,a.getKey()),frame),xy(a.getValue(),frame))<=NUMERICAL,"EXISTING_CURVE_MOVES_PROTECTED_ANCHOR");
            var present=output.get(a.getKey());if(present!=null)require(distance(xy(present,frame),xy(a.getValue(),frame))<=NUMERICAL,"CURVE_MISSES_EXACT_ANCHOR");
            output.put(a.getKey(),a.getValue());
        }
        var mapping=new SourceBoundDisplayCurves.Correspondence(old.referencePoints(),new ArrayList<>(output.values()),new ArrayList<>(output.keySet()),old.referenceLengthMetres(),frame);
        return new Built(mapping,patches.stream().map(Patch::proof).toList());
    }
    private static boolean supportedEndpoint(int index,Optional<SourceBoundDisplayCurves.TerminalSubsegmentSupport> terminal,Optional<TurnSubsegmentSupport.Witness> turn) {
        return terminal.isPresent() && terminal.get().referenceEndpointIndex()==index
                || turn.isPresent() && turn.get().referenceEndpointIndex()==index;
    }
    private static void anchor(TreeMap<Double,Point> anchors,double s,Point p) {
        var old=anchors.putIfAbsent(s,p);require(old==null||old.equals(p),"CONFLICTING_EXACT_ANCHOR_AT_SAME_SOURCE_PROGRESS");
    }
    private static boolean straight(SourceBoundDisplayCurves.Correspondence old,Line source,double lo,double hi) {
        if(distance(xy(at(old,lo),source.frame),source.at(lo))>NUMERICAL||distance(xy(at(old,hi),source.frame),source.at(hi))>NUMERICAL)return false;
        for(int i=0;i<old.displayPoints().size();i++) {double s=old.referenceProgressAtDisplayVertices().get(i);if(lo<s&&s<hi&&distance(xy(old.displayPoints().get(i),source.frame),source.at(s))>NUMERICAL)return false;}
        return true;
    }
    private static Piece piece(Line source,double lo,double hi,Point first,Point last,XY from,double fromSpeed,XY to,double toSpeed,XY common) {
        double span=hi-lo;XY p=xy(first,source.frame),q=xy(last,source.frame),a=source.at(lo),b=source.at(hi),axis=unit(sub(b,a));
        XY[] controls={p,add(p,mul(from,span*fromSpeed/3)),sub(q,mul(to,span*toSpeed/3)),q};
        XY[] reference={a,add(a,mul(sub(b,a),1.0/3)),add(a,mul(sub(b,a),2.0/3)),b};double bound=0;
        for(int i=0;i<4;i++)bound=Math.max(bound,distance(controls[i],reference[i]));
        if(!Double.isFinite(bound)||bound>CONTROL_LEASH)return null;
        for(int i=0;i<3;i++)if(dot(sub(controls[i+1],controls[i]),axis)<=0||dot(sub(controls[i+1],controls[i]),common)<=0)return null;
        int count=Math.max(24,(int)Math.ceil(span/.15));List<Point> points;List<Double> positions;double serialized;
        while(true) {
            if(count>512)return null;
            points=new ArrayList<>();positions=new ArrayList<>();serialized=0;
            for(int i=0;i<=count;i++) {double t=(double)i/count,one=1-t;XY value=add(add(mul(controls[0],one*one*one),mul(controls[1],3*one*one*t)),add(mul(controls[2],3*one*t*t),mul(controls[3],t*t*t)));
                Point point=i==0?first:i==count?last:point(value,source.frame);double sourcePosition=i==0?lo:i==count?hi:lo+span*t;
                serialized=Math.max(serialized,distance(xy(point,source.frame),source.at(sourcePosition)));points.add(point);positions.add(sourcePosition);
            }
            if(serializedTangents(points,from,to,source.frame))break;
            if(count==512)return null;count=Math.min(512,count*2);
        }
        double renderer=0;for(int i=1;i<points.size();i++)renderer=Math.max(renderer,mercatorError(points.get(i-1),points.get(i),source.frame));
        int sourceSegment=source.segment(lo);double sourceLength=source.chain[sourceSegment+1]-source.chain[sourceSegment];
        double referenceRenderer=mercatorError(source.points.get(sourceSegment),source.points.get(sourceSegment+1),(lo-source.chain[sourceSegment])/sourceLength,(hi-source.chain[sourceSegment])/sourceLength,source.frame);
        double maximum=Math.max(bound,serialized)+referenceRenderer+renderer;
        if(!Double.isFinite(maximum)||maximum>LEASH)return null;
        return new Piece(points,positions,maximum);
    }
    /** Certify actual finite map chords against the intended analytic tangent, not just the cubic. */
    private static boolean serializedTangents(List<Point> points,XY from,XY to,SourceBoundDisplayCurves.Frame frame) {
        for(boolean renderer:new boolean[]{false,true}) {
            List<XY> edges=new ArrayList<>();
            for(int i=1;i<points.size();i++) {var a=points.get(i-1);var b=points.get(i);
                XY edge=renderer?new XY(Math.toRadians(b.x()-a.x()),mercatorLatitude(b.y())-mercatorLatitude(a.y())):sub(xy(b,frame),xy(a,frame));
                if(!(Math.hypot(edge.x(),edge.y())>0))return false;edges.add(edge);
            }
            XY first=renderer?rendererTangent(from,points.getFirst(),frame):from,last=renderer?rendererTangent(to,points.getLast(),frame):to;
            if(rawAngle(first,edges.getFirst())>2||rawAngle(last,edges.getLast())>2)return false;
            for(int i=1;i<edges.size();i++)if(rawAngle(edges.get(i-1),edges.get(i))>4)return false;
        }
        return true;
    }
    private static XY rendererTangent(XY tangent,Point at,SourceBoundDisplayCurves.Frame frame) {
        return new XY(tangent.x()/frame.xMetresPerDegree(),tangent.y()/(frame.yMetresPerDegree()*Math.cos(Math.toRadians(at.y()))));
    }
    private static double mercatorLatitude(double latitude){return Math.log(Math.tan(Math.PI/4+Math.toRadians(latitude)/2));}
    private static double rawAngle(XY a,XY b){double denominator=Math.hypot(a.x(),a.y())*Math.hypot(b.x(),b.y());return denominator>0?Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,dot(a,b)/denominator)))):Double.POSITIVE_INFINITY;}
    /** A smaller certified support is retried rather than relaxing the fixed5m guard. */
    private static boolean localCurvatureSafe(SourceBoundDisplayCurves.Correspondence old,Line reference,List<Patch> accepted,Patch trial) {
        TreeMap<Double,Point> values=new TreeMap<>();for(int i=0;i<old.displayPoints().size();i++)values.put(old.referenceProgressAtDisplayVertices().get(i),old.displayPoints().get(i));
        List<Patch> all=new ArrayList<>(accepted);all.add(trial);
        for(var patch:all) {values.subMap(patch.start(),true,patch.end(),true).clear();for(int i=0;i<patch.points().size();i++)values.put(patch.source().get(i),patch.points().get(i));}
        var proposed=new SourceBoundDisplayCurves.Correspondence(old.referencePoints(),new ArrayList<>(values.values()),new ArrayList<>(values.keySet()),old.referenceLengthMetres(),old.metricFrame());
        Line before=new Line(old.displayPoints(),old.metricFrame()),after=new Line(proposed.displayPoints(),old.metricFrame());
        double context=trial.end()-trial.start()+10,lo=Math.max(0,trial.start()-context),hi=Math.min(reference.length(),trial.end()+context);
        for(double s=Math.max(5,Math.ceil(lo));s<Math.min(reference.length()-5,hi);s+=1) {
            double actual=after.angleAt(transport(proposed,after,s));
            if(regresses(reference.angleAt(s),actual)||regresses(before.angleAt(transport(old,before,s)),actual))return false;
        }
        for(double s:proposed.referenceProgressAtDisplayVertices())if(lo<=s&&s<=hi) {
            double actual=after.angleAt(transport(proposed,after,s));
            if(regresses(reference.angleAt(s),actual)||regresses(before.angleAt(transport(old,before,s)),actual))return false;
        }
        return true;
    }
    /** Re-certify every final WGS/renderer chord, including literal anchors inserted outside patches. */
    private static double serializedBound(SourceBoundDisplayCurves.Correspondence map) {
        var source=new Line(map.referencePoints(),map.metricFrame());double maximum=0;
        for(int n=1;n<map.displayPoints().size();n++) {
            double lo=map.referenceProgressAtDisplayVertices().get(n-1),hi=map.referenceProgressAtDisplayVertices().get(n);
            int i=source.segment(lo);require(hi<=source.chain[i+1],"SERIALIZED_CHORD_SPANS_SOURCE_KINK");
            Point a=map.displayPoints().get(n-1),b=map.displayPoints().get(n);
            double endpoint=Math.max(distance(xy(a,source.frame),source.at(lo)),distance(xy(b,source.frame),source.at(hi)));
            double certified;
            if(lo==source.chain[i]&&hi==source.chain[i+1]&&a.equals(source.points.get(i))&&b.equals(source.points.get(i+1)))certified=endpoint;
            else {double size=source.chain[i+1]-source.chain[i],first=(lo-source.chain[i])/size,last=(hi-source.chain[i])/size;
                certified=endpoint+mercatorError(source.points.get(i),source.points.get(i+1),first,last,source.frame)+mercatorError(a,b,source.frame);}
            require(Double.isFinite(certified)&&certified<=LEASH,"FINAL_SERIALIZED_SOURCE_LEASH_EXCEEDED");maximum=Math.max(maximum,certified);
        }
        return maximum;
    }
    static boolean noNewCurvature(SourceBoundDisplayCurves.Correspondence before,SourceBoundDisplayCurves.Correspondence after) {
        Line a=new Line(before.displayPoints(),before.metricFrame()),b=new Line(after.displayPoints(),after.metricFrame());
        for(double s=5;s<after.referenceLengthMetres()-5;s+=1)if(regresses(a.angleAt(transport(before,a,s)),b.angleAt(transport(after,b,s))))return false;
        for(double s:after.referenceProgressAtDisplayVertices())if(regresses(a.angleAt(transport(before,a,s)),b.angleAt(transport(after,b,s))))return false;
        return true;
    }
    static boolean straightOnSource(SourceBoundDisplayCurves.Correspondence mapping,double lo,double hi) {
        return straight(mapping,new Line(mapping.referencePoints(),mapping.metricFrame()),lo,hi);
    }
    private static boolean regresses(double before,double after){return after>60&&after>before+5;}
    private static double transport(SourceBoundDisplayCurves.Correspondence map,Line display,double s) {
        var axis=map.referenceProgressAtDisplayVertices();int i=Collections.binarySearch(axis,s);if(i>=0)return display.chain[i];i=-i-2;if(i<0)return 0;if(i+1>=axis.size())return display.length();
        return display.chain[i]+(s-axis.get(i))/(axis.get(i+1)-axis.get(i))*(display.chain[i+1]-display.chain[i]);
    }
    private static Point at(SourceBoundDisplayCurves.Correspondence map,double s) {
        var axis=map.referenceProgressAtDisplayVertices();int i=Collections.binarySearch(axis,s);if(i>=0)return map.displayPoints().get(i);i=-i-2;require(i>=0&&i+1<axis.size(),"SOURCE_MAPPING_OUTSIDE_DOMAIN");
        double f=(s-axis.get(i))/(axis.get(i+1)-axis.get(i));Point a=map.displayPoints().get(i),b=map.displayPoints().get(i+1);return new Point(a.x()+f*(b.x()-a.x()),a.y()+f*(b.y()-a.y()));
    }
    private static double mercatorError(Point a,Point b,SourceBoundDisplayCurves.Frame frame) {return mercatorError(a,b,0,1,frame);}
    private static double mercatorError(Point a,Point b,double first,double last,SourceBoundDisplayCurves.Frame frame) {
        double x=Math.toRadians(a.y()),y=Math.toRadians(b.y()),max=Math.max(Math.abs(x),Math.abs(y));if(max>=Math.toRadians(85.06))return Double.POSITIVE_INFINITY;
        double min=x<=0&&y>=0||y<=0&&x>=0?0:Math.min(Math.abs(x),Math.abs(y));double product=Math.max(first*(1-first),last*(1-last));if(first<=.5&&last>=.5)product=.25;return .5*product*Math.cos(min)*Math.tan(max)/Math.cos(max)*(y-x)*(y-x)*frame.yMetresPerDegree()*180/Math.PI;
    }
    private static XY xy(Point p,SourceBoundDisplayCurves.Frame f){return new XY((p.x()-f.originX())*f.xMetresPerDegree(),(p.y()-f.originY())*f.yMetresPerDegree());}
    private static Point point(XY p,SourceBoundDisplayCurves.Frame f){return new Point(f.originX()+p.x()/f.xMetresPerDegree(),f.originY()+p.y()/f.yMetresPerDegree());}
    private static XY add(XY a,XY b){return new XY(a.x+b.x,a.y+b.y);}private static XY sub(XY a,XY b){return new XY(a.x-b.x,a.y-b.y);}private static XY mul(XY a,double t){return new XY(a.x*t,a.y*t);}
    private static double dot(XY a,XY b){return a.x*b.x+a.y*b.y;}private static double distance(XY a,XY b){return Math.hypot(a.x-b.x,a.y-b.y);}private static XY unit(XY v){double n=Math.hypot(v.x,v.y);require(n>NUMERICAL,"DEGENERATE_ANCHOR_SUPPORT");return mul(v,1/n);}
    private static double angle(XY a,XY b){return Math.toDegrees(Math.acos(Math.max(-1,Math.min(1,dot(unit(a),unit(b))))));}
    private static void require(boolean ok,String message){if(!ok)throw new IllegalArgumentException(message);}
    private static final class Line {
        final List<Point> points;final List<XY> xy;final double[] chain;final SourceBoundDisplayCurves.Frame frame;
        Line(List<Point> p,SourceBoundDisplayCurves.Frame f){points=p;frame=f;xy=p.stream().map(v->xy(v,f)).toList();chain=new double[p.size()];for(int i=1;i<p.size();i++)chain[i]=chain[i-1]+distance(xy.get(i-1),xy.get(i));}
        double length(){return chain[chain.length-1];}
        int segment(double s){if(s<=0)return 0;if(s>=length())return points.size()-2;int i=Arrays.binarySearch(chain,s);return i>=0?Math.min(i,points.size()-2):-i-2;}
        XY at(double s){int i=segment(s);double f=(s-chain[i])/(chain[i+1]-chain[i]);return add(xy.get(i),mul(sub(xy.get(i+1),xy.get(i)),f));}
        Point pointAt(double s){int i=segment(s);if(s==chain[i])return points.get(i);if(s==chain[i+1])return points.get(i+1);double f=(s-chain[i])/(chain[i+1]-chain[i]);var a=points.get(i);var b=points.get(i+1);return new Point(a.x()+f*(b.x()-a.x()),a.y()+f*(b.y()-a.y()));}
        double angleAt(double s){var a=sub(at(s),at(Math.max(0,s-5)));var b=sub(at(Math.min(length(),s+5)),at(s));return Math.hypot(a.x,a.y)<=NUMERICAL||Math.hypot(b.x,b.y)<=NUMERICAL?0:angle(a,b);}
    }
}
