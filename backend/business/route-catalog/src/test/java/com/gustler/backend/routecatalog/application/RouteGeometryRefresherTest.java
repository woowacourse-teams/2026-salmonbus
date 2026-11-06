package com.gustler.backend.routecatalog.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Publication;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Reference;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape.Point;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

class RouteGeometryRefresherTest {
    private final RouteGeometryStore store = mock(RouteGeometryStore.class);
    private final RouteGeometrySource source = mock(RouteGeometrySource.class);
    private final ApiCallQuota quota = mock(ApiCallQuota.class);
    private final RouteGeometryRefresher refresher = new RouteGeometryRefresher(store, source, quota);
    private final OffsetDateTime now = OffsetDateTime.parse("2026-10-14T12:00:00Z");
    private final Reference reference = new Reference(1, "204000057", 2);

    @Test
    void 판본이나_노선서비스_예산이_없으면_호출하지_않는다() {
        // when
        when(store.current("204000057")).thenReturn(Optional.empty());

        // then
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.NO_REFERENCE);
        verifyNoInteractions(source, quota);
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.NO_QUOTA);
        verify(quota).reserveRouteCatalog(now, 1);
        verifyNoInteractions(source);
    }

    @Test
    void 읽기실패는_기존_형상에_쓰지_않고_예약을_환급하지_않는다() {
        // when
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read("204000057")).thenReturn(Optional.empty());

        // then
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.SOURCE_UNAVAILABLE);
        verify(store, never()).publish(any(), any());
        verify(quota).reserveRouteCatalog(now, 1);
        verifyNoMoreInteractions(quota);
    }

    @Test
    void 정상값도_수집중_판본이_바뀌면_게시하지_않는다() {
        // when
        Read read = new Read(new RouteShape(List.of(
            new Point(127, 37), new Point(127.1, 37.1))), now);
        when(store.current("204000057")).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read("204000057")).thenReturn(Optional.of(read));
        when(store.publish(reference, read)).thenReturn(Publication.REFERENCE_CHANGED);

        // then
        assertThat(refresher.refresh("204000057", now)).isEqualTo(Outcome.REFERENCE_CHANGED);
    }

    @Test
    void 인증API가_실패해도_웹_형상과_다른_가용근거로_발행한다() {
        var web =
                mock(
                        com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.class);
        var bms =
                mock(
                        com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.class);
        when(web.id()).thenReturn("GBIS_WEB");
        when(bms.id()).thenReturn("GG_BMS_NODES");
        Read shape =
                new Read(new RouteShape(List.of(new Point(127, 37), new Point(127.1, 37.1))), now);
        when(web.read(any()))
                .thenReturn(
                        new com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.Contribution(
                                Optional.of(shape),
                                new com.gustler.backend.routegeometry.api.RouteShapeEvidence.Source(
                                        "GBIS_WEB", "AVAILABLE", "a".repeat(64)),
                                List.of(),
                                List.of()));
        when(bms.read(any())).thenThrow(new IllegalStateException("serviceKey=SECRET"));
        when(store.current(any())).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read(any())).thenThrow(new IllegalStateException("HTTP body"));
        when(store.publish(any(), any())).thenReturn(Publication.UPDATED);
        var multi = new RouteGeometryRefresher(store, source, quota, List.of(web, bms));
        assertThat(multi.refresh("204000057", now)).isEqualTo(Outcome.UPDATED);
        var captured = org.mockito.ArgumentCaptor.forClass(Read.class);
        verify(store).publish(org.mockito.ArgumentMatchers.eq(reference), captured.capture());
        assertThat(captured.getValue().evidence().selectedSource()).isEqualTo("GBIS_WEB");
        assertThat(captured.getValue().evidence().sources())
                .extracting(value -> value.status())
                .containsExactly("UNAVAILABLE", "AVAILABLE", "UNAVAILABLE");
    }

    @Test
    void 예산이없어도_별도웹조회는_검토하고_모두실패하면_저장본을_보존한다() {
        var web =
                mock(
                        com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.class);
        when(web.id()).thenReturn("GBIS_WEB");
        when(store.current(any())).thenReturn(Optional.of(reference));
        Read shape =
                new Read(new RouteShape(List.of(new Point(127, 37), new Point(127.1, 37.1))), now);
        when(web.read(any()))
                .thenReturn(
                        new com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.Contribution(
                                Optional.of(shape),
                                new com.gustler.backend.routegeometry.api.RouteShapeEvidence.Source(
                                        "GBIS_WEB", "AVAILABLE", "b".repeat(64)),
                                List.of(),
                                List.of()));
        when(store.publish(any(), any())).thenReturn(Publication.UNCHANGED);
        var multi = new RouteGeometryRefresher(store, source, quota, List.of(web));
        assertThat(multi.refresh("204000057", now)).isEqualTo(Outcome.UNCHANGED);
        verifyNoInteractions(source);
        org.mockito.Mockito.clearInvocations(store);
        when(web.read(any())).thenThrow(new IllegalArgumentException("timeout"));
        assertThat(multi.refresh("204000057", now)).isEqualTo(Outcome.NO_QUOTA);
        verify(store, never()).publish(any(), any());
    }

    @Test
    void 한_보조자료의_실패가_다른_자료의_조회와_기본형상_발행을_막지않는다() {
        var failing =
                mock(
                        com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.class);
        var available =
                mock(
                        com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.class);
        when(failing.id()).thenReturn("GBIS_WEB");
        when(failing.read(any())).thenThrow(new IllegalStateException());
        when(available.read(any()))
                .thenReturn(
                        new com.gustler.backend.routecatalog.domain.geometry
                                .SupplementaryRouteGeometrySource.Contribution(
                                Optional.empty(),
                                new com.gustler.backend.routegeometry.api.RouteShapeEvidence.Source(
                                        "GG_BMS_NODES", "AVAILABLE", "b".repeat(64)),
                                List.of(),
                                List.of()));
        Read read =
                new Read(new RouteShape(List.of(new Point(127, 37), new Point(127.1, 37.1))), now);
        when(store.current(any())).thenReturn(Optional.of(reference));
        when(quota.reserveRouteCatalog(now, 1)).thenReturn(true);
        when(source.read(any())).thenReturn(Optional.of(read));
        when(store.publish(any(), any())).thenReturn(Publication.UPDATED);
        assertThat(
                        new RouteGeometryRefresher(
                                        store, source, quota, List.of(failing, available))
                                .refresh("204000057", now))
                .isEqualTo(Outcome.UPDATED);
        verify(available).read("204000057");
    }
    @Test
    void 원본을_발행해도_웹_근거_실패는_복구_대상으로_남긴다() {
        var web=mock(com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource.class);
        when(web.read(any())).thenReturn(com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource.Contribution.unavailable("GBIS_WEB"));
        when(store.current("204000057")).thenReturn(Optional.of(reference));when(quota.reserveRouteCatalog(now,1)).thenReturn(true);
        when(source.read("204000057")).thenReturn(Optional.of(new Read(new RouteShape(List.of(new Point(127,37),new Point(127.1,37.1))),now)));
        when(store.publish(any(),any())).thenReturn(Publication.UPDATED);
        var result=new RouteGeometryRefresher(store,source,quota,List.of(web)).refreshWithDetails("204000057",now);
        assertThat(result.outcome()).isEqualTo(Outcome.UPDATED);assertThat(result.supplementaryRecoveryNeeded()).isTrue();
        verify(store).publish(any(),any());
    }

}
