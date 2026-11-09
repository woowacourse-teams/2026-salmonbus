package com.gustler.backend.routecatalog.infrastructure.supplementary;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import tools.jackson.databind.ObjectMapper;

/** Exercises the aggregate limit without allocating a 32 MiB fixture or starting PostgreSQL. */
class BmsCaptureBudgetTest {
    @Test void the_32_MiB_boundary_is_exact_and_rejected_lengths_cannot_overflow_or_consume_budget() {
        var budget = new GgBmsNodeGeometrySource.CaptureBudget(GgBmsNodeGeometrySource.MAX_CAPTURE_BYTES);
        for (int i=0;i<4;i++) assertThat(budget.tryCapture(8 * 1024 * 1024)).isTrue();
        assertThat(budget.capturedBytes()).isEqualTo(32 * 1024 * 1024);
        assertThat(budget.tryCapture(1)).isFalse();
        assertThat(budget.tryCapture(Integer.MAX_VALUE)).isFalse();
        assertThat(budget.capturedBytes()).isEqualTo(32 * 1024 * 1024);
        assertThatThrownBy(() -> budget.tryCapture(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void limits_must_be_positive_and_cannot_exceed_the_fixed_production_ceiling() {
        assertThatThrownBy(() -> new GgBmsNodeGeometrySource.CaptureBudget(0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GgBmsNodeGeometrySource.CaptureBudget(Integer.MAX_VALUE)).isInstanceOf(IllegalArgumentException.class);
        var budget = new GgBmsNodeGeometrySource.CaptureBudget(10);
        assertThat(budget.tryCapture(11)).isFalse();
        assertThat(budget.tryCapture(10)).isTrue();
        assertThat(budget.capturedBytes()).isEqualTo(10);
    }

    @Test void a_page_over_budget_is_not_retained_and_prior_cache_and_safe_page_receipts_survive() {
        byte[] old = page(2,0,2);
        byte[] first = page(1001,0,1000);
        byte[] second = page(1001,1000,1);
        int limit = first.length + 1;
        var calls = new AtomicInteger();
        var archive = mock(RouteSourceArchive.class);
        List<List<RouteSourceReceipt>> captured = new ArrayList<>();
        when(archive.record(anyString(), anyList(), anyList())).thenAnswer(invocation -> {
            captured.add(List.copyOf(invocation.getArgument(1)));
            return (captured.size()==1 ? "a" : "b").repeat(64);
        });
        var jdbc = mock(JdbcClient.class, RETURNS_DEEP_STUBS);
        when(jdbc.sql(anyString()).params(any(Object[].class)).update()).thenReturn(1);
        var source = new GgBmsNodeGeometrySource(new ObjectMapper(), jdbc,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), "test-key",
                ignored -> new BoundedJsonHttp.Result(200, switch(calls.getAndIncrement()) {
                    case 0 -> old;
                    case 1 -> first;
                    default -> second;
                }), archive, limit);
        source.refresh();
        var prior = source.read("204000057");
        assertThat(prior.nodes()).hasSize(2);
        source.refresh();
        var kept = source.read("204000057");
        assertThat(kept.nodes()).containsExactlyElementsOf(prior.nodes());
        assertThat(kept.source().status()).isEqualTo("AVAILABLE");
        assertThat(kept.reusedManifestIds()).containsExactly("a".repeat(64), "b".repeat(64));
        assertThat(captured).hasSize(2);
        var partial = captured.get(1);
        assertThat(partial).extracting(RouteSourceReceipt::status)
                .containsExactly(RouteSourceReceipt.Status.SUCCESS, RouteSourceReceipt.Status.INVALID_RESPONSE);
        assertThat(partial.getFirst().body()).containsExactly(first);
        assertThat(partial.getLast().operation()).isEqualTo("NODE_PAGE_RETENTION_LIMIT");
        assertThat(partial.getLast().scope()).isEqualTo("PAGE:2");
        assertThat(partial.getLast().body()).isNull();
        assertThat(partial.getLast().bodySha256()).isNull();
        assertThat(partial.stream().filter(r -> r.body()!=null).mapToInt(r -> r.body().length).sum()).isLessThanOrEqualTo(limit);
    }

    private static byte[] page(int total,int start,int count) {
        StringBuilder text = new StringBuilder("{\"TBBMSNODEM\":[{\"head\":[{\"list_total_count\":")
                .append(total).append("},{\"RESULT\":{\"CODE\":\"INFO-000\"}}]},{\"row\":[");
        for (int i=0;i<count;i++) {
            if (i>0) text.append(',');
            text.append("{\"NODE_ID\":\"").append(start+i).append("\",\"LAT\":3745.674,\"LOGT\":12702.382}");
        }
        return text.append("]}]}").toString().getBytes(StandardCharsets.UTF_8);
    }
}
