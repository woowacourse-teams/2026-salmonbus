package com.gustler.backend.worker.routegeometry;

import com.gustler.backend.routecatalog.api.RouteGeometryInputAvailable;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Newly committed source input wakes generation without waiting for the recovery poll. */
@Component
@ConditionalOnProperty(name = "route-geometry.generation.enabled", havingValue = "true")
public class RouteDisplayGenerationWakeup {
    private static final Logger LOG = LoggerFactory.getLogger(RouteDisplayGenerationWakeup.class);
    private final RouteDisplayGenerationWorker worker;
    private final Executor executor;
    private final Set<String> routes = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean queued = new AtomicBoolean();

    public RouteDisplayGenerationWakeup(RouteDisplayGenerationWorker worker,
            @Qualifier("routeDisplayGenerationScheduler") Executor executor) {
        this.worker = worker;
        this.executor = executor;
    }

    @EventListener
    public void inputAvailable(RouteGeometryInputAvailable event) {
        routes.add(event.routeId());
        wake();
    }

    private void wake() {
        if (!queued.compareAndSet(false, true)) return;
        try {
            executor.execute(this::drain);
        } catch (RejectedExecutionException rejected) {
            // The existing generation poll rediscovers the durable input and jobs.
            queued.set(false);
            LOG.warn("event=route_display_generation_wakeup_deferred reason=QUEUE_FULL");
        }
    }

    private void drain() {
        try {
            do {
                for (String route : Set.copyOf(routes)) {
                    routes.remove(route);
                    try { worker.discover(route); }
                    catch (RuntimeException failure) {
                        LOG.warn("event=route_display_discovery_failed routeId={}", route);
                    }
                }
                for (int i = 0; i < 16; i++) if (!worker.runOne()) break;
            } while (!routes.isEmpty());
        } finally {
            queued.set(false);
            if (!routes.isEmpty()) wake();
        }
    }
}
