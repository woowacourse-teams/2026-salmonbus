package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RouteGeometryRefresherTest {
    private final RouteGeometryStore store = mock(RouteGeometryStore.class);
    private final RouteGeometrySource source = mock(RouteGeometrySource.class);
    private final ApiCallQuota quota = mock(ApiCallQuota.class);
    private final RouteGeometryRefresher refresher = new RouteGeometryRefresher(store, source, quota);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-14T12:00:00Z");
    private final RouteGeometryStore.Reference reference = new RouteGeometryStore.Reference(1, "204000057", 2);

    @Test
    void 판본이나_노선서비스_예산이_없으면_호출하지_않는다() {
        when(store.current("204000057")).thenReturn(Optional.empty());
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.NO_REFERENCE);
        verifyNoInteractions(source, quota);
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.NO_QUOTA);
        verify(quota).reserveRouteCatalog(now, 1);
        verifyNoInteractions(source);
    }

    @Test
    void 읽기실패는_기존_형상에_쓰지_않고_예약을_환급하지_않는다() {
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read("204000057")).thenReturn(Optional.empty());
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.SOURCE_UNAVAILABLE);
        verify(store, never()).publish(any(), any());
        verify(quota).reserveRouteCatalog(now, 1);
        verifyNoMoreInteractions(quota);
    }

    @Test
    void 정상값도_수집중_판본이_바뀌면_게시하지_않는다() {
        var read = new RouteGeometrySource.Read(new RouteShape(List.of(
            new RouteShape.Point(127, 37), new RouteShape.Point(127.1, 37.1))), now);
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read("204000057")).thenReturn(Optional.of(read));
        when(store.publish(reference, read)).thenReturn(RouteGeometryStore.Publication.REFERENCE_CHANGED);
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.REFERENCE_CHANGED);
    }
}
