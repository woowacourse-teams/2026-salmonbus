package com.gustler.backend.worker.routegeometry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import com.gustler.backend.routecatalog.api.RouteGeometryInputAvailable;
import java.util.ArrayDeque;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RouteDisplayGenerationWakeupTest {
    private final RouteDisplayGenerationWorker worker=mock(RouteDisplayGenerationWorker.class);
    private final ArrayDeque<Runnable> tasks=new ArrayDeque<>();

    @Test void source_events_merge_and_wake_generation_without_a_poll_or_inline_computation() {
        var wakeup=new RouteDisplayGenerationWakeup(worker,tasks::add);
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",7));
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",7));
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000081",8));
        assertThat(tasks).hasSize(1);verifyNoInteractions(worker);
        when(worker.runOne()).thenReturn(true,true,false);tasks.remove().run();
        verify(worker,times(1)).discover("204000057");verify(worker,times(1)).discover("204000081");
        verify(worker,times(3)).runOne();assertThat(tasks).isEmpty();
    }

    @Test void another_source_commit_during_discovery_is_not_lost() {
        var wakeup=new RouteDisplayGenerationWakeup(worker,tasks::add);var once=new AtomicBoolean();
        doAnswer(call->{if(once.compareAndSet(false,true))wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",8));return null;}).when(worker).discover("204000057");
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",7));tasks.remove().run();
        verify(worker,times(2)).discover("204000057");assertThat(tasks).isEmpty();
    }

    @Test void rejected_wakeup_can_be_scheduled_again_and_the_input_stays_discoverable() {
        var attempts=new AtomicInteger();
        var wakeup=new RouteDisplayGenerationWakeup(worker,task->{if(attempts.getAndIncrement()==0)throw new RejectedExecutionException();tasks.add(task);});
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",7));assertThat(tasks).isEmpty();
        wakeup.inputAvailable(new RouteGeometryInputAvailable("204000057",8));tasks.remove().run();
        verify(worker,times(1)).discover("204000057");
    }
}
