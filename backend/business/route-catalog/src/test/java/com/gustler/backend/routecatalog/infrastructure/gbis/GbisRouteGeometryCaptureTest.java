package com.gustler.backend.routecatalog.infrastructure.gbis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.gustler.backend.gbis.api.GbisRouteLineSource;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class GbisRouteGeometryCaptureTest {
    private final GbisRouteLineSource lines=mock(GbisRouteLineSource.class);
    private static final Instant ACQUIRED=Instant.parse("2026-11-02T03:00:00Z");
    private final GbisRouteGeometrySource source=new GbisRouteGeometrySource(lines,Clock.fixed(ACQUIRED,ZoneOffset.UTC));
    private static final String ROUTE="204000057";
    private static final List<GbisRouteLineSource.Point> POINTS=List.of(new GbisRouteLineSource.Point(1,127,37),new GbisRouteLineSource.Point(2,127.1,37.1));

    @Test void accepted_transport_bytes_and_unknown_provider_epoch_are_preserved_in_receipt() {
        byte[] bytes=" { \"original\":true }\n".getBytes(StandardCharsets.UTF_8);
        when(lines.capture(ROUTE)).thenReturn(new GbisRouteLineSource.Capture(Optional.of(POINTS),200,GbisRouteLineSource.CaptureStatus.SUCCESS,bytes));
        var capture=source.capture(ROUTE);var receipt=capture.receipts().getFirst();
        assertThat(capture.read()).isPresent();assertThat(receipt.body()).containsExactly(bytes);
        assertThat(receipt.bodySha256()).isEqualTo(RouteSourceReceipt.sha256(bytes));
        assertThat(receipt.acquiredAt()).isEqualTo(ACQUIRED);assertThat(receipt.providerVersion()).isEqualTo("NOT_PROVIDED");
        assertThat(receipt.coordinateSystem()).isEqualTo(RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED);
        assertThat(receipt.toString()).doesNotContain("original");
    }
    @Test void domain_invalid_coordinates_cannot_retain_a_transport_success() {
        var invalid=List.of(new GbisRouteLineSource.Point(1,200,37),new GbisRouteLineSource.Point(2,127.1,37.1));
        when(lines.capture(ROUTE)).thenReturn(new GbisRouteLineSource.Capture(Optional.of(invalid),200,GbisRouteLineSource.CaptureStatus.SUCCESS,"{}".getBytes(StandardCharsets.UTF_8)));
        var captured=source.capture(ROUTE);
        assertThat(captured.read()).isEmpty();assertThat(captured.receipts().getFirst().status()).isEqualTo(RouteSourceReceipt.Status.INVALID_RESPONSE);
        assertThat(captured.receipts().getFirst().body()).isNull();assertThat(captured.receipts().getFirst().bodySha256()).isNull();
    }
    @Test void sensitive_success_has_usable_coordinates_but_no_retained_body_or_body_hash() {
        when(lines.capture(ROUTE)).thenReturn(new GbisRouteLineSource.Capture(Optional.of(POINTS),200,GbisRouteLineSource.CaptureStatus.SENSITIVE_RESPONSE,null));
        var captured=source.capture(ROUTE);
        assertThat(captured.read()).isPresent();assertThat(captured.receipts().getFirst().status()).isEqualTo(RouteSourceReceipt.Status.SENSITIVE_RESPONSE);
        assertThat(captured.receipts().getFirst().body()).isNull();assertThat(captured.receipts().getFirst().bodySha256()).isNull();
    }
}
