package com.gustler.backend.api.salmon.application;

import static com.gustler.backend.api.salmon.domain.ActionFixture.config;
import static com.gustler.backend.api.salmon.domain.ActionFixture.entry;
import static com.gustler.backend.api.salmon.domain.ActionFixture.marginal;
import static com.gustler.backend.api.salmon.domain.ActionFixture.queried;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.gustler.backend.api.boarding.application.BoardingOptionsQuery;
import com.gustler.backend.api.boarding.application.BoardingOptionsQueryService;
import com.gustler.backend.api.boarding.domain.AccessMode;
import com.gustler.backend.api.boarding.domain.BoardingOptions;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Access;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Leg;
import com.gustler.backend.api.boarding.domain.BoardingOptions.Option;
import com.gustler.backend.api.boarding.domain.StopRelation;
import com.gustler.backend.api.boarding.domain.StopVisit;
import com.gustler.backend.api.error.ErrorCode;
import com.gustler.backend.api.route.RouteNotFoundException;
import com.gustler.backend.api.salmon.domain.ActionDecider.Decision;
import com.gustler.backend.api.salmon.domain.Probabilities.ProbabilitySupply;
import com.gustler.backend.api.salmon.domain.StopLists.StopList;
import com.gustler.backend.api.salmon.domain.Verdicts.CandidateRecord;
import com.gustler.backend.api.salmon.domain.Verdicts.ReasonCode;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ActionDecisionTest {
    private static final String ROUTE = "204000057";
    private static final String ORIGIN = "100";
    private static final Instant NOW = Instant.parse("2026-11-08T09:00:00Z");

    private final BoardingOptionsQueryService boarding = mock(BoardingOptionsQueryService.class);
    private final SeatForecastProbabilitySource probabilities = mock(SeatForecastProbabilitySource.class);

    @Test
    void 대기_위험이_낮으면_탑승_후보를_조회하지_않는다() {
        when(probabilities.probabilitiesFor(eq(ROUTE), eq(ORIGIN), eq(5), any(), eq(NOW)))
            .thenReturn(new ProbabilitySupply(List.of(marginal("stay-1", 0.9))));
        ActionDecision decisions = decisions(station -> queried(entry("stay-1", 4)), (routeId, option) -> Optional.of(0.0));

        Decision decision = decisions.decide(ROUTE, ORIGIN, 5, NOW, config(15, 10), List.of());

        assertThat(decision.verdict().code()).isEqualTo(ReasonCode.STAY_SCAN_NOT_TRIGGERED);
        verify(boarding, never()).get(any());
    }

    @Test
    void 같은_방향_후보만_조회해_도보_후보로_판정하고_재탑승_구간이_없으면_재탑승_후보는_없다() {
        StopList upstream = queried(entry("walk-1", 9));
        when(probabilities.probabilitiesFor(eq(ROUTE), any(), anyInt(), any(), eq(NOW))).thenAnswer(invocation -> {
            StopList list = invocation.getArgument(3);
            double value = list.entries().get(0).id().equals("stay-1") ? 0.1 : 0.9;
            return new ProbabilitySupply(List.of(marginal(list.entries().get(0).id(), value)));
        });
        when(boarding.get(any())).thenReturn(boardingOf(
            new Option(visit("200", 4, "UP"), -1, StopRelation.BEFORE, List.of(walk(60)))));
        ActionDecision decisions = decisions(station -> station.equals(ORIGIN)
            ? queried(entry("stay-1", 4)) : upstream, (routeId, option) -> Optional.of(0.0));

        Decision decision = decisions.decide(ROUTE, ORIGIN, 5, NOW, config(15, 10), List.of());

        ArgumentCaptor<BoardingOptionsQuery> query = ArgumentCaptor.forClass(BoardingOptionsQuery.class);
        verify(boarding, times(1)).get(query.capture());
        assertThat(query.getValue().relations()).containsExactlyInAnyOrder(StopRelation.BEFORE, StopRelation.AFTER);
        assertThat(query.getValue().modes()).containsExactly(AccessMode.WALK);
        assertThat(query.getValue().maxMinutes()).isEqualTo(BoardingOptionsQuery.DEFAULT_MAX_MINUTES);
        assertThat(query.getValue().limit()).isEqualTo(BoardingOptionsQuery.DEFAULT_LIMIT);
        assertThat(query.getValue().referenceVersionId()).isNull();
        assertThat(query.getValue().boardingOnly()).isTrue();
        assertThat(query.getValue().stopSequence()).isEqualTo(5);
        assertThat(decision.verdict().code()).isEqualTo(ReasonCode.GO);
        assertThat(decision.verdict().bestCandidate()).contains("walk-200-4");
        assertThat(decision.verdict().candidates()).extracting(CandidateRecord::id).containsExactly("walk-200-4");
        verify(probabilities, times(2)).probabilitiesFor(eq(ROUTE), any(), anyInt(), any(), eq(NOW));
    }

    @Test
    void 호출자가_넘긴_하차_구간만_재탑승_후보로_조립한다() {
        AlightingLeg leg = new AlightingLeg("300", 2, 90, "400", 8, 300, "500", 6, 60);
        when(probabilities.probabilitiesFor(eq(ROUTE), any(), anyInt(), any(), eq(NOW))).thenAnswer(invocation -> {
            StopList list = invocation.getArgument(3);
            String id = list.entries().get(0).id();
            double value = id.equals("stay-1") ? 0.1 : 0.9;
            return new ProbabilitySupply(List.of(marginal(id, value)));
        });
        when(boarding.get(any())).thenReturn(boardingOf(
            new Option(visit("200", 4, "UP"), -1, StopRelation.BEFORE, List.of(walk(60)))));
        ActionDecision decisions = decisions(station -> switch (station) {
            case ORIGIN -> queried(entry("stay-1", 4));
            case "200" -> queried(entry("walk-1", 9));
            case "300" -> queried(entry("opp-1", 3));
            case "500" -> queried(entry("back-1", 12));
            default -> throw new AssertionError(station);
        }, (routeId, option) -> Optional.of(0.0));

        Decision decision = decisions.decide(ROUTE, ORIGIN, 5, NOW, config(15, 10), List.of(leg));

        assertThat(decision.verdict().candidates()).extracting(CandidateRecord::id)
            .containsExactly("walk-200-4", ReboardAssembly.idOf(leg));
        verify(boarding, times(1)).get(any());
        verify(probabilities, times(4)).probabilitiesFor(eq(ROUTE), any(), anyInt(), any(), eq(NOW));
    }

    @Test
    void 탑승_후보_조회_실패는_판정으로_바꾸지_않고_그대로_던진다() {
        when(probabilities.probabilitiesFor(eq(ROUTE), eq(ORIGIN), eq(5), any(), eq(NOW)))
            .thenReturn(new ProbabilitySupply(List.of(marginal("stay-1", 0.1))));
        when(boarding.get(any())).thenThrow(new RouteNotFoundException());
        ActionDecision decisions = decisions(station -> queried(entry("stay-1", 4)), (routeId, option) -> Optional.empty());

        assertThatThrownBy(() -> decisions.decide(ROUTE, ORIGIN, 5, NOW, config(15, 10), List.of()))
            .isInstanceOf(RouteNotFoundException.class)
            .extracting("code")
            .isEqualTo(ErrorCode.ROUTE_NOT_FOUND);
    }

    private ActionDecision decisions(Arrivals arrivals, DetourSource detours) {
        StopArrivalSource source = (routeId, stationId, stopSequence, decisionAt) -> arrivals.list(stationId);
        return new ActionDecision(boarding, source, detours, probabilities);
    }

    private static BoardingOptions boardingOf(Option option) {
        return new BoardingOptions(ROUTE, 9L, visit(ORIGIN, 5, "UP"), List.of(option));
    }

    private static StopVisit visit(String stationId, int stopSequence, String direction) {
        return new StopVisit(stationId, stopSequence, stationId, direction, true, null, null);
    }

    private static Access walk(int seconds) {
        return Access.of(List.of(new Leg(AccessMode.WALK, seconds, null, "test")));
    }

    @FunctionalInterface
    private interface Arrivals {
        StopList list(String stationId);
    }
}
