package com.gustler.backend.worker.scheduling;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.observations.api.CollectObservations;
import com.gustler.backend.routeoperations.api.RouteOperation;
import com.gustler.backend.routeoperations.api.RouteOperations;
import com.gustler.backend.worker.configuration.CollectionProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class DynamicCollectionRoutesTest {
    private RouteOperation row(final boolean on) {
        return new RouteOperation(
            "228000184",
            "READY",
            on,
            false,
            false,
            false,
            "MANUAL",
            "",
            "",
            1,
            0,
            Instant.EPOCH,
            "",
            Instant.EPOCH,
            null);
    }

    @Test
    void 같은_스케줄러가_변경된_대상을_읽고_호출_직전에_다시_확인한다() {
        RouteOperations operations = mock(RouteOperations.class);
        CollectObservations collector = mock(CollectObservations.class);
        CollectionScheduler scheduler =
            new CollectionScheduler(
                new CollectionProperties(true, List.of("204000057")), collector);
        scheduler.setOperations(operations);
        when(operations.list()).thenReturn(List.of(row(true)));
        when(operations.required("228000184")).thenReturn(row(true), row(false));
        scheduler.collectAllRoutes();
        scheduler.collectAllRoutes();
        verify(collector, times(1)).collectOnce("228000184");
        verify(collector, never()).collectOnce("204000057");
    }

    @Test
    void DB_조회가_실패하면_이전_ON_대상을_재사용하지_않는다() {
        RouteOperations operations = mock(RouteOperations.class);
        CollectObservations collector = mock(CollectObservations.class);
        CollectionScheduler scheduler =
            new CollectionScheduler(
                new CollectionProperties(true, List.of("204000057")), collector);
        scheduler.setOperations(operations);
        when(operations.list()).thenThrow(new IllegalStateException("offline"));
        assertThatThrownBy(scheduler::collectAllRoutes)
            .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(collector);
    }
}
