package com.gustler.backend.routegeometry.engine.api.accuracy;

import static org.assertj.core.api.Assertions.*;
import static com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyCandidate.*;
import static com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyEvidence.*;
import static com.gustler.backend.routegeometry.engine.api.accuracy.RouteAccuracyVerifier.Grade.*;
import com.gustler.backend.routegeometry.engine.api.*;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.*;
import com.gustler.backend.routegeometry.api.ManualRouteDisplay.Connection;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Synthetic source-policy fixtures exercise verification mechanics, not real route/model adoption or accuracy. */
class RouteAccuracyVerifierTest {
    static final String MANIFEST="a".repeat(64),BUNDLE="b".repeat(64);
    static final ModelIdentity MODEL=new ModelIdentity("synthetic-record-test","c".repeat(64),"d".repeat(64));
    static final Basis BASIS=new Basis("SYNTHETIC_PUBLIC_RECORDS","e".repeat(64),"fixture-road-and-bus-records");
    static final OperatingContext CONTEXT=new OperatingContext("BUS",Instant.EPOCH);
    static Position p(int segment,double fraction) { return new Position(segment,fraction); }
    static RawSpan span(int start,int end) { return new RawSpan(p(start,0),p(end-1,1)); }
    static List<RouteGeometry.Point> project(List<NativePoint> p) { return p.stream().map(v -> NativeReferenceProjection.toWgs84(Crs.EPSG_5179,v)).toList(); }
    static Connection c(RouteGeometry.Point p,int index,double fraction) {
        return new Connection(new RouteDisplayInput.Point(p.x(),p.y()),index,fraction);
    }
    static class Fixture implements NativeCatalog,Authority {
        final List<NativePoint> upNative=List.of(new NativePoint(950000,1940000),new NativePoint(950010,1940000),new NativePoint(950010,1940010));
        final List<NativePoint> downNative=List.of(upNative.getLast(),upNative.getFirst());
        Map<String,NativeFeature> edges=new HashMap<>();
        boolean unknownOperation,unknownAssociation,unknownBoundary,unknownVisit,unknownLaneVisit,emptyBasis,crosswalk;
        Fixture() {
            edges.put("ref:up",edge("up","origin","turn",upNative,FeatureKind.LANE));
            edges.put("ref:down",edge("down","turn","origin",downNative,FeatureKind.LANE));
        }
        NativeFeature edge(String id,String from,String to,List<NativePoint> vertices,FeatureKind kind) {
            return new NativeFeature("ref:"+id,"ref","ref:"+from,"ref:"+to,kind,Crs.EPSG_5179,vertices,List.of(BASIS));
        }
        public String bundleDigest() { return BUNDLE; }
        public Optional<NativeFeature> find(String id) { return Optional.ofNullable(edges.get(id)); }
        public Optional<NativeConnection> connection(String from,String to) {
            return crosswalk?Optional.of(new NativeConnection(from,to,List.of(BASIS))):Optional.empty();
        }
        Check check(boolean unknown) { return unknown?Check.unknown("FIXTURE_FACT_UNRESOLVED"):new Check(Decision.SUPPORTED,emptyBasis?List.of():List.of(BASIS),"FIXTURE_RECORDS_SUPPORT_REQUEST"); }
        public Check association(RouteGeometry s,TracePart p,NativeFeature f) { return check(unknownAssociation || !edges.containsKey(p.edgeId())); }
        public Check operation(TracePart p,NativeFeature f,OperatingContext x) { return check(unknownOperation); }
        public Check entryExit(TracePart p,NativeFeature f,TracePart q,NativeFeature g,OperatingContext x) { return check(unknownBoundary); }
        public Check visit(RouteGeometry.Stop s,Connection c,RouteGeometry.Point d,TracePart p,NativeFeature f,OperatingContext x) { return check(unknownVisit); }
        public Check laneVisit(RouteGeometry.Stop s,Connection c,TracePart p,NativeFeature f,OperatingContext x) { return check(unknownLaneVisit); }
        RouteGeometry source() {
            var a=project(upNative);var d=project(downNative);var raw=new ArrayList<>(a);raw.add(d.getLast());
            return new RouteGeometry("204000057",1,"synthetic","A","B",2,
                List.of(new RouteGeometry.Stop("S1",1,"start","UP",true,a.getFirst()),
                    new RouteGeometry.Stop("S2",2,"turn","UP",true,a.getLast()),
                    new RouteGeometry.Stop("S3",3,"end","DOWN",true,d.getLast())),
                new RouteGeometry.Shape(UUID.fromString("00000000-0000-0000-0000-000000000001"),Instant.EPOCH,raw,RouteShapeEvidence.EMPTY,MANIFEST));
        }
        RouteDisplayGeometry output() {
            var a=project(upNative);var d=project(downNative);
            return new RouteDisplayGeometry(new RoutePaths(new RoutePaths.Path(a),new RoutePaths.Path(d)),
                Map.of(1,c(a.getFirst(),0,0),2,c(a.getLast(),1,1),3,c(d.getLast(),0,1)),Map.of(),
                "caller-text-is-not-an-accuracy-proof",RouteDisplayGeometry.Source.AUTOMATIC_FALLBACK,List.of(),
                new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.PASSED,List.of()));
        }
        AccuracyCandidate candidate() {
            return candidate(List.of(new TracePart("UP",0,2,"ref:up",0,List.of(p(0,0),p(0,1),p(1,1)),span(0,2),Origin.REFERENCE),
                new TracePart("DOWN",0,1,"ref:down",0,List.of(p(0,0),p(0,1)),span(2,3),Origin.REFERENCE)));
        }
        AccuracyCandidate candidate(List<TracePart> parts) { return new AccuracyCandidate(MODEL,RouteDisplayGeneration.input(source()).digest(),MANIFEST,BUNDLE,parts); }
        RouteAccuracyVerifier verifier() { return new RouteAccuracyVerifier(this,this,(m,r) -> MODEL.equals(m)&&BUNDLE.equals(r)); }
        RouteAccuracyVerifier.Report verify() { return verifier().verify(source(),output(),candidate(),CONTEXT); }
    }
    static RouteDisplayGeometry output(RouteDisplayGeometry d,RoutePaths paths,Map<Integer,Connection> connections) {
        return new RouteDisplayGeometry(paths,connections,d.stationPositions(),d.revision(),d.source(),d.evidence(),d.quality(),d.sourcePaths());
    }
    static TracePart part(TracePart p,List<Position> samples,RawSpan raw,Origin origin) {
        return new TracePart(p.direction(),p.startOutputIndex(),p.endOutputIndex(),p.edgeId(),p.occurrence(),samples,raw,origin);
    }
    @Test void complete_native_lane_and_source_facts_produce_a_bound_lane_report() {
        var f=new Fixture();var report=f.verify();
        assertThat(report.grade()).isEqualTo(LANE_VERIFIED);assertThat(report.snapshot().issues()).isEmpty();
        assertThat(report.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.VERIFIED);
        assertThat(report.referencePublishable()).isTrue();
        assertThat(report.snapshot().coveredOutputSegments()).isEqualTo(3);assertThat(report.snapshot().coveredVisits()).isEqualTo(3);
        assertThat(report.binds(f.source(),f.output(),MANIFEST)).isTrue();
    }
    @Test void default_server_authority_and_adoption_never_promote_model_labels() {
        var f=new Fixture();var report=new RouteAccuracyVerifier(f,AccuracyEvidence.unresolved(),AdoptionPolicy.none())
            .verify(f.source(),f.output(),f.candidate(),CONTEXT);
        assertThat(report.grade()).isEqualTo(UNEVALUATED);assertThat(report.snapshot().issues()).contains("MODEL_NOT_ADOPTED","ASSOCIATION_UNKNOWN","OPERATION_UNKNOWN");
        assertThat(report.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        assertThat(report.referencePublishable()).isFalse();
    }
    @Test void road_native_support_cannot_be_renamed_lane_by_candidate_identity() {
        var f=new Fixture();f.edges.put("ref:down",f.edge("down","turn","origin",f.downNative,FeatureKind.ROAD));
        assertThat(f.verify().grade()).isEqualTo(ROAD_VERIFIED);
    }
    @Test void unverified_lane_boarding_support_downgrades_to_verified_road_only() {
        var f=new Fixture();f.unknownLaneVisit=true;
        var original=f.source();var stops=new ArrayList<>(original.busStops());var stop=stops.get(1);
        // The emitted connection is arithmetically valid at the clipped endpoint, but the source visit is beyond it.
        stops.set(1,new RouteGeometry.Stop(stop.stationId(),stop.stopSequence(),stop.name(),stop.direction(),stop.boardingAllowed(),
            new RouteGeometry.Point(stop.point().x()+0.00001,stop.point().y()+0.00001)));
        var clippedSource=new RouteGeometry(original.routeId(),original.referenceVersionId(),original.name(),original.startPoint(),
            original.endPoint(),original.turnSequence(),stops,original.shape());
        var candidate=new AccuracyCandidate(MODEL,RouteDisplayGeneration.input(clippedSource).digest(),MANIFEST,BUNDLE,f.candidate().parts());
        var report=f.verifier().verify(clippedSource,f.output(),candidate,CONTEXT);
        assertThat(report.grade()).isEqualTo(ROAD_VERIFIED);assertThat(report.snapshot().laneIssues()).contains("LANE_VISIT_UNKNOWN");
        f.unknownVisit=true;
        assertThat(f.verifier().verify(clippedSource,f.output(),candidate,CONTEXT).grade()).isEqualTo(UNEVALUATED);
    }
    @Test void known_forbidden_lane_visit_cannot_escape_as_road_verified() {
        var f=new Fixture() {
            @Override public Check laneVisit(RouteGeometry.Stop stop,Connection connection,TracePart part,NativeFeature feature,OperatingContext context) {
                return new Check(Decision.FORBIDDEN,List.of(BASIS),"BOARDING_ON_FORBIDDEN_LANE");
            }
        };
        assertThat(f.verify().grade()).isEqualTo(UNEVALUATED);
        assertThat(f.verify().disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.REJECTED);
        assertThat(f.verify().referencePublishable()).isFalse();
        assertThat(f.verify().snapshot().issues()).contains("LANE_VISIT_FORBIDDEN","LANE_VISIT:BOARDING_ON_FORBIDDEN_LANE");
    }
    @Test void baseline_unassessed_report_has_no_fabricated_model_identity_or_coverage() {
        var f=new Fixture();var report=RouteAccuracyVerifier.unassessed(f.source(),f.output(),CONTEXT);
        assertThat(report.grade()).isEqualTo(UNEVALUATED);
        assertThat(report.snapshot().model()).isNull();assertThat(report.snapshot().referenceBundleDigest()).isNull();
        assertThat(report.snapshot().semanticFactsDigest()).isNull();
        assertThat(report.snapshot().requiredOutputSegments()).isEqualTo(3);assertThat(report.snapshot().coveredOutputSegments()).isZero();
        assertThat(report.snapshot().requiredVisits()).isEqualTo(3);assertThat(report.snapshot().coveredVisits()).isZero();
        assertThat(report.snapshot().issues()).containsExactly("NO_REFERENCE_MODEL_INPUT");
        assertThat(report.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        assertThat(report.referencePublishable()).isFalse();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,CONTEXT,AdoptionPolicy.none())).isTrue();
    }
    @Test void absent_operation_fact_is_not_no_known_prohibition_success() {
        var f=new Fixture();f.unknownOperation=true;assertThat(f.verify().grade()).isEqualTo(UNEVALUATED);
        var ban=new Basis("SYNTHETIC_OPERATING_RULE","f".repeat(64),"bus-prohibition");
        var forbidden=new Fixture() {
            @Override public Check operation(TracePart p,NativeFeature edge,OperatingContext context) {
                return new Check(Decision.FORBIDDEN,List.of(ban),"BUS_PROHIBITED");
            }
        };
        assertThat(forbidden.verify().snapshot().evidence()).contains(ban);
        assertThat(forbidden.verify().snapshot().issues()).contains("OPERATION:BUS_PROHIBITED");
    }
    @Test void missing_route_assignment_cannot_be_filled_by_close_geometry() {
        var f=new Fixture();f.unknownAssociation=true;assertThat(f.verify().snapshot().issues()).contains("ASSOCIATION_UNKNOWN");
    }
    @Test void matching_node_names_and_geometry_do_not_remove_boundary_evidence_requirement() {
        var f=new Fixture();f.unknownBoundary=true;var report=f.verify();
        assertThat(report.grade()).isEqualTo(UNEVALUATED);assertThat(report.snapshot().issues()).contains("ENTRY_BOUNDARY_UNKNOWN","EXIT_BOUNDARY_UNKNOWN");
    }
    @Test void authority_supported_label_without_original_record_bases_is_rejected() {
        var f=new Fixture();f.emptyBasis=true;assertThat(f.verify().snapshot().issues()).contains("OPERATION_UNBOUND_EVIDENCE");
    }
    @Test void missing_direction_cannot_promote_a_verified_fragment_to_whole_route() {
        var f=new Fixture();var c=f.candidate(List.of(f.candidate().parts().getFirst()));
        var r=f.verifier().verify(f.source(),f.output(),c,CONTEXT);
        assertThat(r.grade()).isEqualTo(UNEVALUATED);assertThat(r.snapshot().issues()).contains("INCOMPLETE_OUTPUT_COVERAGE","INCOMPLETE_WHOLE_SOURCE_SCOPE");
        assertThat(r.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
    }
    @Test void source_span_gaps_are_rejected_even_when_output_is_fully_covered() {
        var f=new Fixture();var parts=new ArrayList<>(f.candidate().parts());var p=parts.getFirst();
        parts.set(0,part(p,p.nativeSamples(),span(1,2),p.origin()));
        assertThat(f.verifier().verify(f.source(),f.output(),f.candidate(parts),CONTEXT).snapshot().issues()).contains("SOURCE_SCOPE_GAP_OVERLAP_OR_ORDER");
    }
    @Test void overlapping_output_and_duplicate_occurrences_are_rejected() {
        var f=new Fixture();var p=f.candidate().parts();
        var r=f.verifier().verify(f.source(),f.output(),f.candidate(List.of(p.get(0),p.get(0),p.get(1))),CONTEXT);
        assertThat(r.snapshot().issues()).contains("OUTPUT_COVERAGE_GAP_OVERLAP","OCCURRENCE_ORDER_INVALID");
        assertThat(r.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.REJECTED);
    }
    @Test void fallback_is_not_a_reference_proof_even_if_coordinates_happen_to_match() {
        var f=new Fixture();var parts=new ArrayList<>(f.candidate().parts());var p=parts.getFirst();
        parts.set(0,part(p,p.nativeSamples(),p.sourceSpan(),Origin.SOURCE_FALLBACK));
        assertThat(f.verifier().verify(f.source(),f.output(),f.candidate(parts),CONTEXT).grade()).isEqualTo(UNEVALUATED);
    }
    @Test void changed_model_or_reference_epoch_cannot_reuse_an_adoption() {
        var f=new Fixture();var c=f.candidate();
        var changed=new AccuracyCandidate(new ModelIdentity(MODEL.modelId(),"f".repeat(64),MODEL.configSha256()),c.inputDigest(),MANIFEST,"f".repeat(64),c.parts());
        assertThat(f.verifier().verify(f.source(),f.output(),changed,CONTEXT).snapshot().issues()).contains("MODEL_NOT_ADOPTED","REFERENCE_EPOCH_MISMATCH");
    }
    @Test void a_chord_across_an_original_native_vertex_is_rejected() {
        var f=new Fixture();var d=f.output();var up=List.of(d.paths().up().points().getFirst(),d.paths().up().points().getLast());
        var changed=output(d,new RoutePaths(new RoutePaths.Path(up),d.paths().down()),Map.of(1,c(up.getFirst(),0,0),2,c(up.getLast(),0,1),3,d.connections().get(3)));
        var parts=new ArrayList<>(f.candidate().parts());
        parts.set(0,new TracePart("UP",0,1,"ref:up",0,List.of(p(0,0),p(1,1)),span(0,2),Origin.REFERENCE));
        assertThat(f.verifier().verify(f.source(),changed,f.candidate(parts),CONTEXT).snapshot().issues()).contains("NATIVE_VERTEX_SKIPPED_CHORD");
    }
    @Test void changed_wgs_output_is_compared_with_projection_not_native_distance() {
        var f=new Fixture();var d=f.output();var points=new ArrayList<>(d.paths().up().points());
        var a=points.getFirst();points.set(0,new RouteGeometry.Point(a.x()+0.00002,a.y()));
        var changed=output(d,new RoutePaths(new RoutePaths.Path(points),d.paths().down()),d.connections());
        assertThat(f.verifier().verify(f.source(),changed,f.candidate(),CONTEXT).snapshot().issues()).contains("NATIVE_PROJECTION_OUTPUT_MISMATCH");
        assertThat(f.verify().binds(f.source(),changed,MANIFEST)).isFalse();
    }
    @Test void a_geographic_degree_axis_is_not_accepted_as_native_metres() {
        var f=new Fixture();var e=f.edges.get("ref:up");
        f.edges.put(e.id(),new NativeFeature(e.id(),e.namespace(),e.fromNode(),e.toNode(),e.kind(),Crs.EPSG_4326,e.vertices(),e.basis()));
        assertThat(f.verify().snapshot().issues()).contains("INVALID_REFERENCE_PART");
    }
    @Test void native_reverse_progress_is_rejected() {
        var f=new Fixture();var parts=new ArrayList<>(f.candidate().parts());var q=parts.getFirst();
        parts.set(0,part(q,List.of(p(1,1),p(0,1),p(0,0)),q.sourceSpan(),q.origin()));
        assertThat(f.verifier().verify(f.source(),f.output(),f.candidate(parts),CONTEXT).snapshot().issues()).contains("NATIVE_PROGRESS_NOT_FORWARD");
    }
    @Test void coincident_coordinates_with_distinct_native_nodes_are_not_connected() {
        var f=new Fixture();f.edges.put("ref:down",f.edge("down","other","origin",f.downNative,FeatureKind.LANE));
        assertThat(f.verify().snapshot().issues()).contains("NATIVE_TOPOLOGY_NOT_CONNECTED");
        f.crosswalk=true;assertThat(f.verify().grade()).isEqualTo(LANE_VERIFIED);
    }
    @Test void missing_unknown_and_off_segment_visit_connections_are_rejected() {
        var f=new Fixture();var d=f.output();var connections=new HashMap<>(d.connections());connections.remove(2);
        assertThat(f.verifier().verify(f.source(),output(d,d.paths(),connections),f.candidate(),CONTEXT).snapshot().issues()).contains("VISIT_CONNECTION_MISSING");
        connections.put(999,d.connections().get(1));
        assertThat(f.verifier().verify(f.source(),output(d,d.paths(),connections),f.candidate(),CONTEXT).snapshot().issues()).contains("UNKNOWN_OUTPUT_VISIT");
        connections=new HashMap<>(d.connections());var point=d.connections().get(1).point();
        connections.put(1,new Connection(new RouteDisplayInput.Point(point.x()+.001,point.y()),0,0));
        assertThat(f.verifier().verify(f.source(),output(d,d.paths(),connections),f.candidate(),CONTEXT).snapshot().issues()).contains("VISIT_CONNECTION_NOT_ON_OUTPUT");
    }
    @Test void final_visit_order_is_checked_independently_of_positive_source_policy() {
        var f=new Fixture();var d=f.output();var connections=new HashMap<>(d.connections());
        connections.put(1,d.connections().get(2));connections.put(2,d.connections().get(1));
        assertThat(f.verifier().verify(f.source(),output(d,d.paths(),connections),f.candidate(),CONTEXT).snapshot().issues()).contains("VISIT_ORDER_REVERSED");
    }
    @Test void immutable_report_does_not_bind_a_changed_input_receipt_or_withheld_output() {
        var f=new Fixture();var report=f.verify();assertThat(report.binds(f.source(),f.output(),"f".repeat(64))).isFalse();
        var source=f.source();var stops=new ArrayList<>(source.busStops());var v=stops.getFirst();
        stops.set(0,new RouteGeometry.Stop(v.stationId(),v.stopSequence(),v.name(),v.direction(),v.boardingAllowed(),new RouteGeometry.Point(v.point().x()+.001,v.point().y())));
        var changed=new RouteGeometry(source.routeId(),source.referenceVersionId(),source.name(),source.startPoint(),source.endPoint(),source.turnSequence(),stops,source.shape());
        assertThat(report.binds(changed,f.output(),MANIFEST)).isFalse();
        var d=f.output();var withheld=new RouteDisplayGeometry(d.paths(),d.connections(),d.stationPositions(),d.revision(),d.source(),d.evidence(),new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.WITHHELD,List.of()));
        assertThat(report.binds(source,withheld,MANIFEST)).isFalse();
        assertThat(report.binds(source,d,MANIFEST,AdoptionPolicy.none())).isFalse();
        assertThat(report.binds(source,d,MANIFEST,new OperatingContext("BUS",Instant.EPOCH.plusSeconds(1)),(m,r) -> true)).isFalse();
        assertThat(report.binds(source,d,MANIFEST,(m,r) -> { throw new IllegalStateException("registry unavailable"); })).isFalse();
        assertThat(report.binds(source,d,MANIFEST,new OperatingContext("BUS",Instant.EPOCH,450,20_000L),(m,r) -> true)).isFalse();
        assertThat(CONTEXT.heightCm()).isNull();assertThat(CONTEXT.weightKg()).isNull();
        assertThatThrownBy(() -> new OperatingContext("BUS",Instant.EPOCH,0,20_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatingContext("BUS",Instant.EPOCH,450,0L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatingContext("BUS",Instant.EPOCH,10_001,20_000L)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new OperatingContext("BUS",Instant.EPOCH,450,1_000_001L)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void repeated_native_edge_occurrences_are_not_deduplicated() {
        var f=new Fixture();var loop=new ArrayList<>(f.upNative);loop.add(f.upNative.getFirst());
        var xy=project(loop);var raw=new ArrayList<>(xy);raw.addAll(xy.subList(1,xy.size()));
        var source=new RouteGeometry("204000057",1,"synthetic","A","A",2,
            List.of(new RouteGeometry.Stop("S1",1,"start","UP",true,xy.getFirst()),new RouteGeometry.Stop("S2",2,"turn","UP",true,xy.getLast()),new RouteGeometry.Stop("S3",3,"end","DOWN",true,xy.getLast())),
            new RouteGeometry.Shape(UUID.randomUUID(),Instant.EPOCH,raw,RouteShapeEvidence.EMPTY,MANIFEST));
        var edge=f.edge("loop","origin","origin",loop,FeatureKind.LANE);f.edges=Map.of(edge.id(),edge);
        var display=new RouteDisplayGeometry(new RoutePaths(new RoutePaths.Path(xy),new RoutePaths.Path(xy)),Map.of(1,c(xy.getFirst(),0,0),2,c(xy.getLast(),2,1),3,c(xy.getLast(),2,1)),Map.of(),"synthetic",RouteDisplayGeometry.Source.AUTOMATIC_FALLBACK,List.of(),new RouteDisplayGeometry.Quality(RouteDisplayGeometry.QualityStatus.PASSED,List.of()));
        var samples=List.of(p(0,0),p(0,1),p(1,1),p(2,1));
        var candidate=new AccuracyCandidate(MODEL,RouteDisplayGeneration.input(source).digest(),MANIFEST,BUNDLE,List.of(
            new TracePart("UP",0,3,edge.id(),0,samples,span(0,3),Origin.REFERENCE),new TracePart("DOWN",0,3,edge.id(),0,samples,span(3,6),Origin.REFERENCE)));
        var report=f.verifier().verify(source,display,candidate,CONTEXT);
        assertThat(report.grade()).isEqualTo(LANE_VERIFIED);assertThat(report.snapshot().nativeOccurrences()).isEqualTo(2);
    }
    @Test void payload_seal_ignores_map_iteration_order_but_detects_pin_changes() {
        var f=new Fixture();var d=f.output();var reverse=new LinkedHashMap<Integer,Connection>();
        reverse.put(3,d.connections().get(3));reverse.put(2,d.connections().get(2));reverse.put(1,d.connections().get(1));
        assertThat(GeometryPayloadFingerprint.of(output(d,d.paths(),reverse))).isEqualTo(GeometryPayloadFingerprint.of(d));
        var changed=new RouteDisplayGeometry(d.paths(),d.connections(),Map.of(1,d.paths().up().points().getFirst()),d.revision(),d.source(),d.evidence(),d.quality());
        assertThat(GeometryPayloadFingerprint.of(changed)).isNotEqualTo(GeometryPayloadFingerprint.of(d));
    }
    @Test void changed_role_facts_cannot_reuse_positive_geometry_proof_or_legacy_binding_overloads() {
        var f=new Fixture();var base=f.candidate();String roleA="1".repeat(64),roleB="2".repeat(64);
        var candidate=new AccuracyCandidate(base.model(),base.inputDigest(),base.sourceManifestId(),base.referenceBundleDigest(),base.parts(),roleA);
        var report=f.verifier().verify(f.source(),f.output(),candidate,CONTEXT,roleA);
        assertThat(report.grade()).isEqualTo(LANE_VERIFIED);
        assertThat(report.snapshot().semanticFactsDigest()).isEqualTo(roleA);
        assertThat(report.binds(f.source(),f.output(),MANIFEST)).isFalse();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,(m,r) -> true)).isFalse();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,CONTEXT,(m,r) -> true)).isFalse();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,CONTEXT,(m,r) -> true,roleA)).isTrue();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,CONTEXT,(m,r) -> true,roleB)).isFalse();
        assertThat(report.binds(f.source(),f.output(),MANIFEST,CONTEXT,(m,r) -> true,null)).isFalse();
        var changed=f.verifier().verify(f.source(),f.output(),candidate,CONTEXT,roleB);
        assertThat(changed.grade()).isEqualTo(UNEVALUATED);
        assertThat(changed.snapshot().issues()).contains("ROLE_FACTS_UNBOUND_OR_CHANGED");
        assertThat(f.verifier().verify(f.source(),f.output(),candidate,CONTEXT).snapshot().issues()).contains("ROLE_FACTS_UNBOUND_OR_CHANGED");
        assertThat(f.verifier().verify(f.source(),f.output(),base,CONTEXT,roleA).grade()).isEqualTo(UNEVALUATED);
        var malformed=new AccuracyCandidate(base.model(),base.inputDigest(),base.sourceManifestId(),base.referenceBundleDigest(),base.parts(),"not-a-sha");
        assertThat(f.verifier().verify(f.source(),f.output(),malformed,CONTEXT,"not-a-sha").grade()).isEqualTo(UNEVALUATED);
    }
    @Test void new_receipt_identity_with_identical_semantic_role_facts_keeps_original_proof_binding() {
        var f=new Fixture();var base=f.candidate();String facts="1".repeat(64);
        var candidate=new AccuracyCandidate(base.model(),base.inputDigest(),base.sourceManifestId(),base.referenceBundleDigest(),base.parts(),facts);
        var report=f.verifier().verify(f.source(),f.output(),candidate,CONTEXT,facts);
        var source=f.source();var shape=source.shape();String refreshedReceipt="9".repeat(64);
        var refreshed=new RouteGeometry(source.routeId(),source.referenceVersionId(),source.name(),source.startPoint(),source.endPoint(),
            source.turnSequence(),source.busStops(),new RouteGeometry.Shape(shape.id(),shape.receivedAt().plusSeconds(30),shape.points(),shape.evidence(),refreshedReceipt));
        assertThat(RouteDisplayGeneration.input(refreshed).digest()).isEqualTo(RouteDisplayGeneration.input(source).digest());
        // The stored proof retains its actually consumed receipt; a newer equivalent receipt is not relabeled onto it.
        assertThat(report.binds(refreshed,f.output(),MANIFEST,CONTEXT,(m,r) -> true,facts)).isTrue();
        assertThat(report.binds(refreshed,f.output(),refreshedReceipt,CONTEXT,(m,r) -> true,facts)).isFalse();
        var refreshedCandidate=new AccuracyCandidate(base.model(),base.inputDigest(),refreshedReceipt,base.referenceBundleDigest(),base.parts(),facts);
        assertThat(f.verifier().verify(refreshed,f.output(),refreshedCandidate,CONTEXT,facts).grade()).isEqualTo(LANE_VERIFIED);
    }

    @Test void every_authoritative_forbidden_gate_rejects_even_an_unadopted_model() {
        for(String gate:List.of("ASSOCIATION","OPERATION","ENTRY_EXIT","VISIT","LANE_VISIT")) {
            var f=new Fixture() {
                Check decision(String current) { return current.equals(gate)?new Check(Decision.FORBIDDEN,List.of(BASIS),"EXPLICIT_PROHIBITION"):check(false); }
                @Override public Check association(RouteGeometry source,TracePart part,NativeFeature feature) { return decision("ASSOCIATION"); }
                @Override public Check operation(TracePart part,NativeFeature feature,OperatingContext context) { return decision("OPERATION"); }
                @Override public Check entryExit(TracePart p,NativeFeature f,TracePart q,NativeFeature g,OperatingContext c) { return decision("ENTRY_EXIT"); }
                @Override public Check visit(RouteGeometry.Stop s,Connection c,RouteGeometry.Point d,TracePart p,NativeFeature f,OperatingContext x) { return decision("VISIT"); }
                @Override public Check laneVisit(RouteGeometry.Stop s,Connection c,TracePart p,NativeFeature f,OperatingContext x) { return decision("LANE_VISIT"); }
            };
            var report=new RouteAccuracyVerifier(f,f,AdoptionPolicy.none()).verify(f.source(),f.output(),f.candidate(),CONTEXT);
            assertThat(report.grade()).as(gate).isEqualTo(UNEVALUATED);
            assertThat(report.disposition()).as(gate).isEqualTo(RouteAccuracyVerifier.Disposition.REJECTED);
            assertThat(report.referencePublishable()).isFalse();
            // Subject identity can remain valid while serving this reference candidate is explicitly prohibited.
            assertThat(report.binds(f.source(),f.output(),MANIFEST)).isTrue();
        }
    }
    @Test void missing_authorities_catalog_records_and_partial_scope_are_unverified_not_rejected() {
        var failedAuthority=new Fixture() {
            @Override public Check operation(TracePart p,NativeFeature f,OperatingContext c) { throw new IllegalStateException("source unavailable"); }
        };
        assertThat(failedAuthority.verify().disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        assertThat(failedAuthority.verify().snapshot().issues()).contains("OPERATION_UNAVAILABLE");
        var failedCatalog=new Fixture() {
            @Override public Optional<NativeFeature> find(String id) { throw new IllegalStateException("catalog unavailable"); }
        };
        assertThat(failedCatalog.verify().disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        var missing=new Fixture();missing.edges.remove("ref:down");
        assertThat(missing.verify().disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        var f=new Fixture();var parts=new ArrayList<>(f.candidate().parts());var part=parts.getFirst();
        parts.set(0,part(part,part.nativeSamples(),span(1,2),part.origin()));
        var gap=f.verifier().verify(f.source(),f.output(),f.candidate(parts),CONTEXT);
        assertThat(gap.snapshot().issues()).contains("SOURCE_SCOPE_GAP");
        assertThat(gap.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
    }
    @Test void structural_projection_and_chord_contradictions_are_rejected() {
        var f=new Fixture();var original=f.output();var up=new ArrayList<>(original.paths().up().points());
        var first=up.getFirst();up.set(0,new RouteGeometry.Point(first.x()+0.00002,first.y()));
        var moved=output(original,new RoutePaths(new RoutePaths.Path(up),original.paths().down()),original.connections());
        var projected=f.verifier().verify(f.source(),moved,f.candidate(),CONTEXT);
        assertThat(projected.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.REJECTED);
        assertThat(projected.grade()).isEqualTo(UNEVALUATED);assertThat(projected.referencePublishable()).isFalse();
        var chord=List.of(original.paths().up().points().getFirst(),original.paths().up().points().getLast());
        var chordOutput=output(original,new RoutePaths(new RoutePaths.Path(chord),original.paths().down()),
            Map.of(1,c(chord.getFirst(),0,0),2,c(chord.getLast(),0,1),3,original.connections().get(3)));
        var parts=new ArrayList<>(f.candidate().parts());
        parts.set(0,new TracePart("UP",0,1,"ref:up",0,List.of(p(0,0),p(1,1)),span(0,2),Origin.REFERENCE));
        var result=f.verifier().verify(f.source(),chordOutput,f.candidate(parts),CONTEXT);
        assertThat(result.snapshot().issues()).contains("NATIVE_VERTEX_SKIPPED_CHORD");
        assertThat(result.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.REJECTED);
    }
    @Test void snapshots_without_disposition_remain_unverified_without_changing_legacy_identity_fields() {
        var f=new Fixture();var s=RouteAccuracyVerifier.unassessed(f.source(),f.output(),CONTEXT).snapshot();
        var old=new RouteAccuracyVerifier.Snapshot(s.verifierRevision(),s.grade(),s.inputDigest(),s.geometryDigest(),
            s.sourceManifestId(),s.model(),s.referenceBundleDigest(),s.context(),s.requiredOutputSegments(),s.coveredOutputSegments(),
            s.requiredVisits(),s.coveredVisits(),s.nativeOccurrences(),s.issues(),s.laneIssues(),s.evidence());
        assertThat(old.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
        assertThat(old.inputDigest()).isEqualTo(s.inputDigest());assertThat(old.geometryDigest()).isEqualTo(s.geometryDigest());
        var absent=new RouteAccuracyVerifier.Snapshot(s.verifierRevision(),s.grade(),s.inputDigest(),s.geometryDigest(),
            s.sourceManifestId(),s.model(),s.referenceBundleDigest(),s.context(),s.requiredOutputSegments(),s.coveredOutputSegments(),
            s.requiredVisits(),s.coveredVisits(),s.nativeOccurrences(),s.issues(),s.laneIssues(),s.evidence(),null,null);
        assertThat(absent.disposition()).isEqualTo(RouteAccuracyVerifier.Disposition.UNVERIFIED);
    }

}
