package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.domain.*;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

class RouteCatalogCaptureTest {
    private static final String ROUTE="204000057";
    private static final OffsetDateTime AT=OffsetDateTime.parse("2026-11-02T00:00:00Z");
    private final CurrentRouteVersionQuery query=mock(CurrentRouteVersionQuery.class);
    private final ApiCallQuota quota=mock(ApiCallQuota.class);
    private final RouteSource source=mock(RouteSource.class);
    private final RouteRegistry registry=mock(RouteRegistry.class);
    private final RouteVersionLoader versions=mock(RouteVersionLoader.class);
    private final TransactionTemplate transaction=mock(TransactionTemplate.class);
    private final RouteSourceArchive archive=mock(RouteSourceArchive.class);
    private final CatalogSourceBinding binding=mock(CatalogSourceBinding.class);
    private RouteCatalogLoader loader;
    @BeforeEach void setup() {
        loader=new RouteCatalogLoader(query,quota,source,registry,versions,transaction,Optional.of(archive),Optional.of(binding));
        when(source.requiredCallsPerRead()).thenReturn(2);
    }
    private RouteSourceReceipt failedReceipt(String operation,RouteSourceReceipt.Status status) {
        return new RouteSourceReceipt("GBIS_API",operation,ROUTE,status,null,AT.toInstant(),"NOT_PROVIDED",
            RouteSourceReceipt.CoordinateSystem.UNKNOWN,null);
    }
    private UpstreamRoute route() {
        return new UpstreamRoute(ROUTE,"3330","A","B",new RouteStops(null,List.of(
            new RouteStop(1,"206000001","A",StopDirection.UP,true,new StopCoordinates(127,37)))),null);
    }
    @Test void quota_rejection_is_archived_without_an_upstream_call() {
        when(quota.reserveRouteCatalog(AT,2)).thenReturn(false);
        assertThat(loader.refresh(ROUTE,AT)).isEmpty();
        @SuppressWarnings("unchecked") ArgumentCaptor<List<RouteSourceReceipt>> receipts=ArgumentCaptor.forClass(List.class);
        verify(archive).record(eq("ROUTE_CATALOG_ACQUISITION"),receipts.capture(),eq(List.of()));
        assertThat(receipts.getValue()).extracting(RouteSourceReceipt::status).containsExactly(RouteSourceReceipt.Status.NO_QUOTA);
        verify(source,never()).capture(anyString());verify(source,never()).read(anyString());
        verifyNoInteractions(registry,versions,binding);
    }
    @Test void failed_partial_acquisition_is_preserved_without_opening_a_version() {
        when(quota.reserveRouteCatalog(AT,2)).thenReturn(true);
        var receipts=List.of(failedReceipt("ROUTE_INFO",RouteSourceReceipt.Status.UNRECORDED_LEGACY),
            failedReceipt("ROUTE_STATIONS",RouteSourceReceipt.Status.HTTP_ERROR));
        when(source.capture(ROUTE)).thenReturn(new RouteSource.Capture(new RouteSourceResult.Failed("DO_NOT_LOG_PROVIDER_ERROR"),receipts));
        assertThat(loader.refresh(ROUTE,AT)).isEmpty();
        verify(archive).record("ROUTE_CATALOG_ACQUISITION",receipts,List.of());
        verifyNoInteractions(registry,versions,binding);
    }
    @Test void accepted_catalog_is_bound_to_its_loaded_version_inside_the_publication_transaction() {
        when(quota.reserveRouteCatalog(AT,2)).thenReturn(true);
        var route=route();var receipts=List.of(failedReceipt("ROUTE_INFO",RouteSourceReceipt.Status.UNRECORDED_LEGACY));
        when(source.capture(ROUTE)).thenReturn(new RouteSource.Capture(new RouteSourceResult.Success(route),receipts));
        when(archive.record(anyString(),anyList(),anyList())).thenReturn("a".repeat(64));
        when(registry.register(route)).thenReturn(7L);
        when(versions.load(7L,route.stops(),route.timetable(),AT)).thenReturn(9L);
        when(transaction.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback=invocation.getArgument(0);
            return callback.doInTransaction(new SimpleTransactionStatus());
        });
        assertThat(loader.refresh(ROUTE,AT)).isPresent();
        var ordered=inOrder(archive,transaction,registry,versions,binding);
        ordered.verify(archive).record("ROUTE_CATALOG_ACQUISITION",receipts,List.of());
        ordered.verify(transaction).execute(any());ordered.verify(registry).register(route);
        ordered.verify(versions).load(7L,route.stops(),route.timetable(),AT);
        ordered.verify(binding).bind(9L,route,"a".repeat(64),AT);
    }
    @Test void archive_failure_prevents_an_unrecorded_new_catalog_publication() {
        when(quota.reserveRouteCatalog(AT,2)).thenReturn(true);
        when(source.capture(ROUTE)).thenReturn(new RouteSource.Capture(new RouteSourceResult.Success(route()),List.of()));
        when(archive.record(anyString(),anyList(),anyList())).thenThrow(new IllegalStateException("ARCHIVE_UNAVAILABLE"));
        assertThatThrownBy(() -> loader.refresh(ROUTE,AT)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(registry,versions,binding,transaction);
    }
}
