package com.gustler.backend.routegeometry.engine.api.accuracy;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routegeometry.api.RouteModelFacts;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class ReferenceModelInputTest {
    private static final String H="a".repeat(64),OTHER="b".repeat(64);
    private static RouteModelFacts facts(String center,String receipt) {
        return new RouteModelFacts(H,Set.of(RouteModelFacts.Role.CENTER_YN),Map.of(),List.of(new RouteModelFacts.VisitFacts("station",1,"DOWN",
            new RouteModelFacts.Fact(RouteModelFacts.FieldState.VALUE,center))),RouteModelFacts.Availability.RESOLVED,RouteModelFacts.Reason.AVAILABLE,
            List.of(new RouteModelFacts.BasisRef(H,receipt,"ROUTE_STATIONS",H,Instant.EPOCH)));
    }
    private static ReferenceModelInput input(RouteModelFacts facts,String manifest,String reference,String policy,String config) {
        return new ReferenceModelInput(H,H,manifest,facts,new AccuracyCandidate.ModelIdentity("candidate-v1",H,config),reference,policy);
    }
    @Test void consumed_role_change_invalidates_generation_while_reacquisition_does_not() {
        var original=input(facts("Y",H),H,H,H,H);
        assertThat(original.semanticMatch(input(facts("N",H),H,H,H,H))).isFalse();
        assertThat(original.semanticMatch(input(facts("Y",OTHER),OTHER,H,H,H))).isTrue();
    }
    @Test void reference_policy_configuration_and_legacy_to_captured_changes_invalidate() {
        var original=input(facts("Y",H),H,H,H,H);
        assertThat(original.semanticMatch(input(facts("Y",H),H,OTHER,H,H))).isFalse();
        assertThat(original.semanticMatch(input(facts("Y",H),H,H,OTHER,H))).isFalse();
        assertThat(original.semanticMatch(input(facts("Y",H),H,H,H,OTHER))).isFalse();
        assertThat(original.semanticMatch(input(facts("Y",H),null,H,H,H))).isFalse();
    }
    @Test void unresolved_roles_are_identified_without_fabricated_resolved_inputs() {
        var visit=new RouteModelFacts.VisitFacts("station",1,"DOWN",RouteModelFacts.Fact.unknown());
        var unresolved=new RouteModelFacts(H,Set.of(RouteModelFacts.Role.CENTER_YN),Map.of(),List.of(visit),RouteModelFacts.Availability.UNRESOLVED,RouteModelFacts.Reason.SOURCE_UNAVAILABLE,List.of());
        assertThat(input(unresolved,H,H,H,H).additionalRolesResolved()).isFalse();
        assertThatThrownBy(()->new ReferenceModelInput(OTHER,H,H,facts("Y",H),new AccuracyCandidate.ModelIdentity("candidate-v1",H,H),H,H)).hasMessageContaining("another input");
    }
}
