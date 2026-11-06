package com.gustler.backend.routegeometry.engine.api;

import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence.TypedPoint;
import com.gustler.backend.routegeometry.engine.api.RouteGeometry.Point;
import java.util.*;

/** Evidence for representative-path rendering, never for a physical boarding
 * position. Raw indexed role events, not coordinate dictionaries, identify laps. */
final class SourceStopOccurrenceWitness {
    private SourceStopOccurrenceWitness() {}
    record Witness(String stationId,int sequence,String direction,int rawIndex,
                   double lowerSourceAxis,double upperSourceAxis,double footSourceAxis,boolean lowerInclusive,boolean upperInclusive,String kind) {}
    record Assessment(Map<Integer,Witness> visits,List<String> reasons) {
        Assessment {visits=Map.copyOf(visits);reasons=List.copyOf(reasons);}
        boolean supports(RouteGeometry.Stop stop) {
            var w=visits.get(stop.stopSequence());
            return w!=null && w.stationId().equals(stop.stationId()) && w.direction().equals(stop.direction());
        }
    }
    private record Row(int rawIndex,List<TypedPoint> roles) {}
    private record Event(int first,int last,Set<String> directions,boolean shared) {}
    private record Leg(List<Point> original,List<Point> candidate,int[] sourceIndices,
                       double[] chain,double[] rawProgress,SourceBoundDisplayCurves.TerminalSubsegmentSupport cut,int literalCut) {}
    private static Assessment failed(String reason) {return new Assessment(Map.of(),List.of(reason));}

    static Assessment assess(RouteGeometry geometry,RoutePaths paths,Map<Integer,Connection> connections) {
        if(geometry.shape()==null || paths==null || geometry.paths()==null)return failed("SOURCE_PATH_UNAVAILABLE");
        var shape=geometry.shape();
        if(shape.sourceManifestId()==null || !shape.sourceManifestId().matches("[a-fA-F0-9]{64}")
                || shape.unboundSourceManifestId()!=null)return failed("SOURCE_PROVENANCE_UNBOUND");
        // Finite WEB deadlines are not yet revisioned by the source lifecycle.
        // Do not publish a witness which could outlive that evidence in cache.
        if(shape.evidence().sources().stream().noneMatch(s->s.id().equals("GBIS_WEB") && s.status().equals("AVAILABLE")
                && s.digest()!=null && !s.digest().isBlank() && s.validUntil()==null))
            return failed("CURRENT_SOURCE_ROLE_EVIDENCE_UNAVAILABLE");
        var classified=EvidenceRoutePaths.classify(geometry);
        if(classified.unmatchedPoints()!=0 || classified.classifications().isEmpty()
                || !(classified.normalization().equals("FULL_POINT_ORDER_V1") || classified.normalization().equals("OFFICIAL_VISIBLE_SUBSEQUENCE_V1")))
            return failed("WHOLE_SOURCE_ROLE_ORDER_UNPROVEN");
        var rows=rows(geometry,classified.normalization());
        if(rows==null)return failed("INDEXED_SOURCE_ROLE_ORDER_UNPROVEN");
        var events=new ArrayList<Event>();
        for(int i=0;i<rows.size();i++) {
            var row=rows.get(i);if(row.roles().stream().noneMatch(p->p.type()==1))continue;
            if(row.roles().stream().anyMatch(p->p.type()!=1))return failed("MIXED_SOURCE_ROLE_EVENT");
            var directions=new HashSet<String>();row.roles().forEach(p->directions.add(p.direction()));int last=i;
            // Only the explicit adjacent UP/DOWN duplicate is one shared turn
            // event. Repeated stations elsewhere remain separate occurrences.
            if(directions.equals(Set.of("UP")) && i+1<rows.size()) {
                var next=rows.get(i+1);
                if(next.roles().stream().allMatch(p->p.type()==1 && p.direction().equals("DOWN"))
                        && row.roles().getFirst().point().equals(next.roles().getFirst().point())
                        && geometry.points().get(i).equals(geometry.points().get(i+1))) {
                    directions.add("DOWN");last=++i;
                }
            }
            events.add(new Event(row.rawIndex(),last,Set.copyOf(directions),directions.equals(Set.of("UP","DOWN"))));
        }
        if(events.size()!=geometry.busStops().size() || geometry.turnSequence()==null)return failed("SOURCE_VISIT_COUNT_MISMATCH");
        int previous=0;boolean down=false;Event shared=null;
        for(int i=0;i<events.size();i++) {
            var stop=geometry.busStops().get(i);var e=events.get(i);
            if(stop.stationId()==null || stop.stationId().isBlank() || stop.stopSequence()<=previous
                    || !e.directions().contains(stop.direction()) || !stop.direction().equals(stop.stopSequence()<=geometry.turnSequence()?"UP":"DOWN"))
                return failed("SOURCE_VISIT_DIRECTION_OR_SEQUENCE_MISMATCH");
            if(stop.direction().equals("DOWN"))down=true;else if(down)return failed("SOURCE_VISIT_DIRECTION_OR_SEQUENCE_MISMATCH");
            if(e.shared()) {if(shared!=null || stop.stopSequence()!=geometry.turnSequence())return failed("SHARED_TURN_EVENT_MISMATCH");shared=e;}
            previous=stop.stopSequence();
        }
        var original=geometry.paths();var raw=geometry.points();int prefix=0,suffix=0;
        while(prefix<original.up().points().size() && prefix<raw.size() && original.up().points().get(prefix).equals(raw.get(prefix)))prefix++;
        while(suffix<original.down().points().size() && suffix<raw.size()
                && original.down().points().get(original.down().points().size()-1-suffix).equals(raw.get(raw.size()-1-suffix)))suffix++;
        if(prefix==0 || suffix==0 || original.up().points().size()-prefix>1 || original.down().points().size()-suffix>1
                || !original.up().points().getLast().equals(original.down().points().getFirst()))return failed("SOURCE_TURN_LINEAGE_UNPROVEN");
        int left=prefix-1,right=raw.size()-suffix;
        if(right<left || right>left+1)return failed("SOURCE_TURN_BRACKET_UNPROVEN");
        double[] rawChain=chain(raw);double seam=rawChain[left];
        if(right>left){Point a=raw.get(left),b=raw.get(right),boundary=original.up().points().getLast();
            if(a.equals(b)){if(!a.equals(boundary))return failed("SOURCE_TURN_BRACKET_UNPROVEN");}
            else {double dx=b.x()-a.x(),dy=b.y()-a.y();double fraction=Math.abs(dx)>=Math.abs(dy)?(boundary.x()-a.x())/dx:(boundary.y()-a.y())/dy;
                if(!Double.isFinite(fraction) || fraction<0 || fraction>1)return failed("SOURCE_TURN_BRACKET_UNPROVEN");
                seam+=fraction*(rawChain[right]-rawChain[left]);}}
        if(shared!=null && (shared.last()<left || shared.first()>right))return failed("SHARED_TURN_NOT_INCIDENT_TO_BOUNDARY");
        Map<String,Leg> legs=new HashMap<>();
        for(String direction:List.of("UP","DOWN")) {
            var o=path(original,direction);var p=path(paths,direction);
            var cut=SourceBoundDisplayCurves.terminalSubsegmentSupport(geometry,direction,p,connections,classified.classifications()).orElse(null);
            int literalCut=literalTerminalCut(geometry,direction,o,p,connections);
            var mapping=uniqueEmbedding(o,p,direction,cut,literalCut);
            if(mapping==null)return failed("CANDIDATE_SOURCE_OCCURRENCE_AMBIGUOUS_OR_INSERTED");
            double[] originalRaw=new double[o.size()];
            for(int i=0;i<o.size();i++)originalRaw[i]=direction.equals("UP")?(i<prefix?rawChain[i]:seam):
                    (i<o.size()-suffix?seam:rawChain[right+i-(o.size()-suffix)]);
            double[] candidateRaw=new double[p.size()];
            for(int i=0;i<p.size();i++)candidateRaw[i]=mapping[i]>=0?originalRaw[mapping[i]]:
                    originalRaw[cut.sourceSegmentIndex()]+cut.sourceFraction()*(originalRaw[cut.sourceSegmentIndex()+1]-originalRaw[cut.sourceSegmentIndex()]);
            legs.put(direction,new Leg(o,p,mapping,chain(p),candidateRaw,cut,literalCut));
        }
        Map<Integer,Witness> result=new HashMap<>();
        for(String direction:List.of("UP","DOWN")) {
            var leg=legs.get(direction);var anchors=new ArrayList<Double>();var supported=new ArrayList<Integer>();
            for(int i=0;i<events.size();i++) {
                var e=events.get(i);if(!e.directions().contains(direction))continue;
                int originalIndex;
                if(e.shared())originalIndex=direction.equals("UP")?leg.original().size()-1:0;
                else originalIndex=direction.equals("UP")?e.first():e.first()-right+(original.down().points().size()-suffix);
                int found=-1;
                for(int j=0;j<leg.sourceIndices().length;j++)if(sameZeroLengthOccurrence(leg.original(),leg.sourceIndices()[j],originalIndex)) {found=j;break;}
                if(found<0 && (leg.cut()!=null || leg.literalCut()>=0)) {
                    boolean start=direction.equals("UP");var visits=geometry.busStops().stream().filter(s->s.direction().equals(direction)).toList();
                    int terminal=start?visits.getFirst().stopSequence():visits.getLast().stopSequence();
                    if(geometry.busStops().get(i).stopSequence()==terminal
                            && (leg.literalCut()>=0?(start?originalIndex<=leg.literalCut():originalIndex>=leg.literalCut()):
                            start?originalIndex<=leg.cut().sourceSegmentIndex():originalIndex>=leg.cut().sourceSegmentIndex()+1))
                        found=start?0:leg.candidate().size()-1;
                }
                if(found<0 || originalIndex<0 || originalIndex>=leg.original().size())return failed("SOURCE_TYPE1_ANCHOR_NOT_PRESERVED");
                // An ordinary role must name this exact raw source occurrence.
                if(!e.shared() && !leg.original().get(originalIndex).equals(raw.get(e.first())))return failed("SOURCE_TYPE1_DIRECTION_WINDOW_MISMATCH");
                anchors.add(leg.chain()[found]);supported.add(i);
            }
            for(int j=1;j<anchors.size();j++)if(anchors.get(j)<=anchors.get(j-1))return failed("SOURCE_ANCHOR_ORDER_NOT_STRICT");
            for(int j=0;j<supported.size();j++) {
                int ordinal=supported.get(j);var stop=geometry.busStops().get(ordinal);if(!stop.direction().equals(direction))continue;
                var connection=connections.get(stop.stopSequence());if(!valid(leg.candidate(),connection))return failed("ACTUAL_CONNECTION_INVALID");
                double s=progress(leg.chain(),connection),lo=j==0?0:(anchors.get(j-1)+anchors.get(j))/2,
                        hi=j+1==anchors.size()?leg.chain()[leg.chain().length-1]:(anchors.get(j)+anchors.get(j+1))/2;
                // The raw event window is checked BEFORE a split seam or
                // terminal cut can redefine its candidate anchor. In particular
                // an incident long edge cannot lend a remote shared-turn role.
                double rawLo=ordinal==0?rawChain[0]:(rawChain[events.get(ordinal-1).first()]+rawChain[events.get(ordinal).first()])/2,
                        rawHi=ordinal+1==events.size()?rawChain[rawChain.length-1]:(rawChain[events.get(ordinal).first()]+rawChain[events.get(ordinal+1).first()])/2;
                double rawS=progress(leg.rawProgress(),connection);
                if(rawS<rawLo || rawS>rawHi || ordinal>0 && rawS==rawLo || ordinal+1<events.size() && rawS==rawHi)
                    return failed("CONNECTION_OUTSIDE_RAW_TYPE1_WINDOW");
                // Midpoint equality cannot identify either adjacent visit.
                if(s<lo || s>hi || j>0 && s==lo || j+1<anchors.size() && s==hi)return failed("CONNECTION_OUTSIDE_OWN_SOURCE_WINDOW");
                double candidateLo=axis(leg.chain(),lo),candidateHi=axis(leg.chain(),hi),
                        rawLoAxis=axis(leg.rawProgress(),Math.max(leg.rawProgress()[0],rawLo)),
                        rawHiAxis=axis(leg.rawProgress(),Math.min(leg.rawProgress()[leg.rawProgress().length-1],rawHi));
                double lowerAxis=Math.max(candidateLo,rawLoAxis),upperAxis=Math.min(candidateHi,rawHiAxis);
                boolean lowerInclusive=(lowerAxis!=candidateLo || j==0) && (lowerAxis!=rawLoAxis || rawLo<leg.rawProgress()[0] || ordinal==0),
                        upperInclusive=(upperAxis!=candidateHi || j+1==anchors.size()) && (upperAxis!=rawHiAxis || rawHi>leg.rawProgress()[leg.rawProgress().length-1] || ordinal+1==events.size());
                var event=events.get(ordinal);boolean endpoint=s==0 || s==leg.chain()[leg.chain().length-1];
                if(endpoint && !event.shared())continue;
                result.put(stop.stopSequence(),new Witness(stop.stationId(),stop.stopSequence(),direction,event.first(),
                        lowerAxis,upperAxis,connection.segmentIndex()+connection.segmentFraction(),lowerInclusive,upperInclusive,event.shared()?"SHARED_TURN_EVENT":"ORDERED_INTERIOR_EVENT"));
            }
        }
        return new Assessment(result,List.of());
    }

    /** A curve must map its ACTUAL serialized connection back inside the same
     * baseline visit window. Same XY on another lap is insufficient. */
    static Assessment throughCurve(RouteGeometry geometry,RoutePaths reference,Map<Integer,Connection> before,
            SourceBoundDisplayCurves.Plan plan,Map<Integer,Connection> after) {
        var base=assess(geometry,reference,before);if(!base.reasons().isEmpty())return base;
        if(!plan.continuousControlHullCertified() || !plan.serializedChordCorrespondenceCertified()
                || !RouteStopIntervals.preservedCurveOccurrences(geometry.busStops(),reference,before,plan.paths(),after,plan).usable())
            return failed("CURVE_SOURCE_OCCURRENCE_UNPROVEN");
        Map<Integer,Witness> result=new HashMap<>();
        for(var stop:geometry.busStops()) {
            var witness=base.visits().get(stop.stopSequence());if(witness==null)continue;
            var m=plan.correspondences().get(stop.direction());var c=after.get(stop.stopSequence());
            if(m==null || !valid(m.displayPoints(),c))return failed("CURVE_CONNECTION_INVALID");
            var values=m.referenceProgressAtDisplayVertices();double s=values.get(c.segmentIndex())+c.segmentFraction()*(values.get(c.segmentIndex()+1)-values.get(c.segmentIndex()));
            double[] chain=metricChain(m);double lo=atAxis(chain,witness.lowerSourceAxis()),hi=atAxis(chain,witness.upperSourceAxis());
            boolean atOuterLow=witness.lowerInclusive(),atOuterHigh=witness.upperInclusive();
            if(s<lo || s>hi || !atOuterLow && s==lo || !atOuterHigh && s==hi)return failed("CURVE_OUTSIDE_OWN_SOURCE_WINDOW");
            result.put(stop.stopSequence(),witness);
        }
        return new Assessment(result,List.of());
    }
    private static List<Row> rows(RouteGeometry geometry,String normalization) {
        var typed=geometry.shape().evidence().webPoints();
        if(normalization.equals("OFFICIAL_VISIBLE_SUBSEQUENCE_V1"))typed=typed.stream().filter(p->p.type()!=2).toList();
        boolean down=false;int boundary=-1;
        for(int i=0;i<typed.size();i++) {String d=typed.get(i).direction();if(d.equals("DOWN")){if(!down)boundary=i-1;down=true;}else if(!d.equals("UP") || down)return null;}
        boolean merge=typed.size()==geometry.points().size()+1;
        if(merge && (boundary<0 || boundary+1>=typed.size() || typed.get(boundary).type()!=typed.get(boundary+1).type()
                || !typed.get(boundary).point().equals(typed.get(boundary+1).point())))return null;
        if(!merge && typed.size()!=geometry.points().size())return null;
        List<Row> result=new ArrayList<>();
        for(int i=0;i<typed.size();i++) {var role=typed.get(i);var roles=merge && i==boundary?List.of(role,typed.get(++i)):List.of(role);int index=result.size();
            for(var p:roles)if(RouteStopAlignment.distance(geometry.points().get(index),new Point(p.point().x(),p.point().y()))>4)return null;
            result.add(new Row(index,roles));}
        return result;
    }
    private static int[] uniqueEmbedding(List<Point> source,List<Point> candidate,String direction,SourceBoundDisplayCurves.TerminalSubsegmentSupport cut,int literalCut) {
        int lo=cut!=null && direction.equals("UP")?cut.sourceSegmentIndex()+1:literalCut>=0 && direction.equals("UP")?literalCut:0;
        int hi=cut!=null && direction.equals("DOWN")?cut.sourceSegmentIndex():literalCut>=0 && direction.equals("DOWN")?literalCut:source.size()-1;
        int start=cut!=null && direction.equals("UP")?1:0,end=candidate.size()-(cut!=null && direction.equals("DOWN")?1:0);
        if(cut==null && literalCut<0 && (!source.getFirst().equals(candidate.getFirst()) || !source.getLast().equals(candidate.getLast())))return null;
        if(direction.equals("UP") && !source.getLast().equals(candidate.getLast()) || direction.equals("DOWN") && !source.getFirst().equals(candidate.getFirst()))return null;
        int[] forward=new int[candidate.size()],backward=new int[candidate.size()];Arrays.fill(forward,-1);Arrays.fill(backward,-1);
        int k=lo;for(int i=start;i<end;i++){while(k<=hi && !source.get(k).equals(candidate.get(i)))k++;if(k>hi)return null;forward[i]=k++;}
        k=hi;for(int i=end-1;i>=start;i--){while(k>=lo && !source.get(k).equals(candidate.get(i)))k--;if(k<lo)return null;backward[i]=k--;}
        for(int i=start;i<end;i++)if(!sameZeroLengthOccurrence(source,forward[i],backward[i]))return null;
        return forward;
    }
    private static boolean sameZeroLengthOccurrence(List<Point> source,int a,int b) {
        if(a<0 || b<0 || a>=source.size() || b>=source.size())return false;
        for(int i=Math.min(a,b);i<=Math.max(a,b);i++)if(!source.get(i).equals(source.get(a)))return false;
        return true;
    }
    /** Literal source-vertex terminal cuts need no interpolated support point.
     * Recompute both ordered alignments and the original100m/15m trim policy. */
    private static int literalTerminalCut(RouteGeometry geometry,String direction,List<Point> original,List<Point> candidate,Map<Integer,Connection> connections) {
        boolean start=direction.equals("UP");Point boundary=start?candidate.getFirst():candidate.getLast();
        if(boundary.equals(start?original.getFirst():original.getLast()))return -1;
        int index=original.indexOf(boundary);if(index<0 || original.lastIndexOf(boundary)!=index)return -1;
        var visits=geometry.busStops().stream().filter(s->s.direction().equals(direction)).toList();if(visits.isEmpty())return -1;
        var terminal=start?visits.getFirst():visits.getLast();if(terminal.point()==null)return -1;
        var before=RouteStopAlignment.align(original,visits,start);var after=RouteStopAlignment.align(candidate,visits,start);
        if(!before.usable() || !after.usable() || !Objects.equals(after.connections().get(terminal.stopSequence()),connections.get(terminal.stopSequence())))return -1;
        var old=before.connections().get(terminal.stopSequence());
        if(old==null || !boundary.equals(new Point(old.point().x(),old.point().y())) || RouteStopAlignment.distance(terminal.point(),boundary)>15)return -1;
        var chain=chain(original);double tail=start?chain[index]:chain[chain.length-1]-chain[index];
        return tail>.01 && tail<=100?index:-1;
    }
    private static List<Point> path(RoutePaths paths,String d){return d.equals("UP")?paths.up().points():paths.down().points();}
    private static double[] chain(List<Point> p){double[] c=new double[p.size()];for(int i=1;i<p.size();i++)c[i]=c[i-1]+RouteStopAlignment.distance(p.get(i-1),p.get(i));return c;}
    private static double[] metricChain(SourceBoundDisplayCurves.Correspondence m){double[] c=new double[m.referencePoints().size()];var f=m.metricFrame();for(int i=1;i<c.length;i++){var a=m.referencePoints().get(i-1);var b=m.referencePoints().get(i);c[i]=c[i-1]+Math.hypot((b.x()-a.x())*f.xMetresPerDegree(),(b.y()-a.y())*f.yMetresPerDegree());}return c;}
    private static double progress(double[] c,Connection x){return c[x.segmentIndex()]+x.segmentFraction()*(c[x.segmentIndex()+1]-c[x.segmentIndex()]);}
    private static double atAxis(double[] c,double axis){int i=Math.min((int)axis,c.length-2);return c[i]+(axis-i)*(c[i+1]-c[i]);}
    private static double axis(double[] c,double value){if(value==c[0])return 0;if(value==c[c.length-1])return c.length-1;int i=Arrays.binarySearch(c,value);if(i>=0)return i;i=-i-2;return i+(value-c[i])/(c[i+1]-c[i]);}
    private static boolean valid(List<Point> points,Connection c){return c!=null && c.segmentIndex()>=0 && c.segmentIndex()+1<points.size() && Double.isFinite(c.segmentFraction()) && c.segmentFraction()>=0 && c.segmentFraction()<=1 && new Point(c.point().x(),c.point().y()).equals(RouteStopAlignment.interpolate(points.get(c.segmentIndex()),points.get(c.segmentIndex()+1),c.segmentFraction()));}
}
