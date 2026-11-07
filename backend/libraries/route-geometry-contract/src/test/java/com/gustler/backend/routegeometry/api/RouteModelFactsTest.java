package com.gustler.backend.routegeometry.api;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteModelFacts.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class RouteModelFactsTest {
    private RouteModelFacts facts(String center,String routeCode,String ref) {
        return new RouteModelFacts("a".repeat(64),Set.of(Role.CENTER_YN,Role.ROUTE_TYPE_CD),
            Map.of(Role.ROUTE_TYPE_CD,new Fact(FieldState.VALUE,routeCode)),
            List.of(new VisitFacts("206000001",1,"UP",new Fact(FieldState.VALUE,center))),
            Availability.RESOLVED,Reason.AVAILABLE,
            List.of(new BasisRef(ref.repeat(64),ref.repeat(64),"ROUTE_INFO",ref.repeat(64),Instant.EPOCH)));
    }
    @Test void consumed_center_and_route_code_change_semantics_without_changing_source_input() {
        var original=facts("N","11","b");
        assertThat(original.inputDigest()).isEqualTo(facts("Y","11","b").inputDigest());
        assertThat(original.factsDigest()).isNotEqualTo(facts("Y","11","b").factsDigest())
            .isNotEqualTo(facts("N","12","b").factsDigest());
    }
    @Test void acquisition_identity_and_timestamp_do_not_change_semantic_digest() {
        var a=facts("N","11","b");var b=facts("N","11","c");
        var refreshed=new RouteModelFacts(b.inputDigest(),b.consumedRoles(),b.routeValues(),b.visits(),b.availability(),b.reason(),
            List.of(new BasisRef("c".repeat(64),"c".repeat(64),"ROUTE_INFO","c".repeat(64),Instant.EPOCH.plusSeconds(10))));
        assertThat(a.basisRefs()).isNotEqualTo(refreshed.basisRefs());assertThat(a.factsDigest()).isEqualTo(refreshed.factsDigest());
    }
    @Test void missing_null_and_unknown_are_distinct_and_never_default_to_N() {
        Set<String> digests=new HashSet<>();
        for(var state:List.of(FieldState.MISSING,FieldState.NULL,FieldState.UNKNOWN)) {
            var facts=new RouteModelFacts("a".repeat(64),Set.of(Role.CENTER_YN),Map.of(),
                List.of(new VisitFacts("206000001",1,"UP",new Fact(state,null))),Availability.UNRESOLVED,
                Reason.CONSUMED_FIELD_UNKNOWN,List.of());
            assertThat(facts.visits().getFirst().centerYn().value()).isNull();digests.add(facts.factsDigest());
        }
        assertThat(digests).hasSize(3);assertThat(digests).doesNotContain(facts("N","11","b").factsDigest());
    }
    @Test void roles_and_visit_occurrences_are_part_of_the_contract_and_extra_roles_are_rejected() {
        var a=facts("N","11","b");
        var occurrence=new RouteModelFacts(a.inputDigest(),a.consumedRoles(),a.routeValues(),
            List.of(new VisitFacts("206000001",2,"UP",new Fact(FieldState.VALUE,"N"))),a.availability(),a.reason(),a.basisRefs());
        assertThat(a.factsDigest()).isNotEqualTo(occurrence.factsDigest());
        assertThatThrownBy(()->new RouteModelFacts(a.inputDigest(),Set.of(Role.CENTER_YN),a.routeValues(),a.visits(),
            a.availability(),a.reason(),a.basisRefs())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new VisitFacts("x",1,"UP",new Fact(FieldState.VALUE,"UNKNOWN")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
