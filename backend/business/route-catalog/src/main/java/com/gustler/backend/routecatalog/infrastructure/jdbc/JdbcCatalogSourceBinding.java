package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.domain.CatalogSourceBinding;
import com.gustler.backend.routecatalog.domain.RouteStop;
import com.gustler.backend.routecatalog.domain.StopCoordinates;
import com.gustler.backend.routecatalog.domain.StopDirection;
import com.gustler.backend.routecatalog.domain.UpstreamRoute;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Objects;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation=Propagation.MANDATORY)
public class JdbcCatalogSourceBinding implements CatalogSourceBinding {
    private final JdbcClient jdbc;
    private final com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive archive;
    public JdbcCatalogSourceBinding(JdbcClient jdbc,com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive archive) {
        this.jdbc=jdbc;this.archive=archive;
    }
    @Override public boolean bind(long versionId,UpstreamRoute captured,String manifest,OffsetDateTime at) {
        var state=jdbc.sql("""
            SELECT v.turn_sequence,v.catalog_acquisition_observed_at
            FROM route_version v JOIN route r ON r.id=v.route_id
            WHERE v.id=? AND v.valid_to IS NULL AND r.source_id='GBIS' AND r.source_route_id=?
            FOR UPDATE OF r,v
            """).params(versionId,captured.sourceRouteId()).query((r,n) -> new State(
                r.getObject(1,Integer.class),r.getObject(2,OffsetDateTime.class))).optional();
        if(state.isEmpty() || state.get().at()!=null && at.isBefore(state.get().at())) return false;
        var actual=jdbc.sql("""
            SELECT s.stop_order,s.stop_id,s.name,s.direction,s.boarding_allowed,l.x,l.y
            FROM route_stop s LEFT JOIN LATERAL (
              SELECT x,y FROM route_stop_location_history h
              WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order
              ORDER BY h.observed_at DESC LIMIT 1
            ) l ON true WHERE s.route_version_id=? ORDER BY s.stop_order
            """).param(versionId).query((r,n) -> new RouteStop(r.getInt(1),r.getString(2),r.getString(3),
                StopDirection.valueOf(r.getString(4)),r.getBoolean(5),StopCoordinates.ofNullable(
                    r.getObject(6,Double.class),r.getObject(7,Double.class)))).list();
        var acquisition=archive.restore(manifest);
        boolean complete=acquisition.purpose().equals("ROUTE_CATALOG_ACQUISITION")
            && acquisition.reusedManifestIds().isEmpty() && acquisition.receipts().size()==2
            && acquisition.receipts().stream().map(r -> r.operation()).collect(java.util.stream.Collectors.toSet())
                .equals(java.util.Set.of("ROUTE_INFO","ROUTE_STATIONS"))
            && acquisition.receipts().stream().allMatch(r -> r.sourceId().equals("GBIS_API")
                && r.scope().equals(captured.sourceRouteId())
                && r.status()==com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status.SUCCESS);
        boolean matches=complete && Objects.equals(state.get().turn(),captured.stops().turnSequence())
            && actual.equals(captured.stops().stops().stream().sorted(Comparator.comparingInt(RouteStop::stopOrder)).toList());
        // A retained old coordinate mixed with new source rows has no single exact catalog acquisition.
        jdbc.sql("UPDATE route_version SET acquisition_manifest_id=?,catalog_acquisition_observed_at=? WHERE id=?")
            .params(matches?manifest:null,at,versionId).update();
        return matches;
    }
    private record State(Integer turn,OffsetDateTime at) {}
}
