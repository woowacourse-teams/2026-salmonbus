package com.gustler.backend.routecatalog.infrastructure.jdbc;

import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource.Read;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Publication;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometryStore.Reference;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

/**
 * 현재 노선 판본의 형상을 읽고 새로 수신한 형상을 발행한다.
 * 다른 판본의 형상을 현재 자료로 연결하지 않는다.
 */
@Repository
public class JdbcRouteGeometryStore implements RouteGeometryStore {
    private final RouteSourceArchive archive;
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final java.time.Clock clock;

    public JdbcRouteGeometryStore(JdbcClient jdbc, ObjectMapper json) {
        this(jdbc,json,Optional.empty());
    }

    public JdbcRouteGeometryStore(JdbcClient jdbc,ObjectMapper json,Optional<RouteSourceArchive> archive) {
        this(jdbc,json,archive,Optional.empty());
    }

    @org.springframework.beans.factory.annotation.Autowired
    public JdbcRouteGeometryStore(JdbcClient jdbc,ObjectMapper json,Optional<RouteSourceArchive> archive,
            Optional<java.time.Clock> clock) {
        this.jdbc=jdbc;this.json=json;this.archive=archive.orElse(RouteSourceArchive.NONE);
        this.clock=clock.orElseGet(java.time.Clock::systemUTC);
    }

    @Override
    public Optional<Reference> current(String publicRouteId) {
        return jdbc.sql(
                        """
SELECT route.id,route.source_route_id,version.id FROM route
JOIN route_version version ON version.route_id=route.id AND version.valid_to IS NULL
WHERE route.public_route_id=? AND route.source_id='GBIS'
""")
                .param(publicRouteId)
                .query(
                        (row, index) ->
                                new Reference(row.getLong(1), row.getString(2), row.getLong(3)))
                .optional();
    }

    @Override
    @Transactional(readOnly = false)
    public Publication publish(Reference reference, Read read) {
        // 카탈로그 변경과 같은 노선 행을 잠근다. 수집 도중 바뀐 판본에는 형상을 붙이지 않는다.
        jdbc.sql("SELECT id FROM route WHERE id=? FOR UPDATE")
                .param(reference.routeId())
                .query(Long.class)
                .single();
        final boolean current =
                jdbc.sql(
                                "SELECT EXISTS(SELECT 1 FROM route_version WHERE id=? AND"
                                    + " route_id=? AND valid_to IS NULL)")
                        .params(reference.versionId(), reference.routeId())
                        .query(Boolean.class)
                        .single();
        if (!current) {
            return Publication.REFERENCE_CHANGED;
        }
        if(reference.recoveryLeaseToken()!=null && jdbc.sql("""
                SELECT route_version_id FROM route_geometry_recovery
                WHERE route_version_id=? AND route_id=? AND status='RUNNING' AND lease_token=?
                    AND lease_until>clock_timestamp() FOR UPDATE
                """).params(reference.versionId(),reference.routeId(),reference.recoveryLeaseToken())
                .query(Long.class).optional().isEmpty())return Publication.RECOVERY_LEASE_LOST;
        Optional<Previous> previous =
                jdbc.sql(
                                """
SELECT content_digest,received_at,evidence::text,source_manifest_id FROM route_geometry WHERE route_version_id=?
ORDER BY received_at DESC,id DESC LIMIT 1
""")
                        .param(reference.versionId())
                        .query(
                                (row, index) ->
                                        new Previous(
                                                row.getString(1),
                                                row.getObject(2, OffsetDateTime.class),row.getString(3),row.getString(4)))
                        .optional();
        String digest = read.shape().digest();
        if(previous.isPresent() && retainUsableEvidence(reference,previous.get(),read,digest))
            return Publication.UNCHANGED;
        if (previous.isPresent() && isUnchanged(previous.get(), read, digest)) {
            if (previous.get().digest().equals(digest) && !read.receivedAt().isBefore(previous.get().receivedAt())) {
                String sourceManifest=bindInput(reference,read);
                jdbc.sql(
                                "UPDATE route_geometry SET evidence=CAST(? AS"
                                    + " jsonb),evidence_observed_at=?,source_manifest_id=? WHERE route_version_id=? AND"
                                    + " content_digest=? AND evidence_observed_at<=?")
                        .params(
                                json.writeValueAsString(read.evidence()),
                                read.receivedAt(),
                                sourceManifest,
                                reference.versionId(),
                                digest,
                                read.receivedAt())
                        .update();
            }
            return Publication.UNCHANGED;
        }

        jdbc.sql(
                        """
INSERT INTO route_geometry(id,route_version_id,received_at,content_digest,points,evidence,evidence_observed_at,source_manifest_id)
VALUES (?,?,?,?,CAST(? AS jsonb),CAST(? AS jsonb),?,?)
""")
                .params(
                        UUID.randomUUID(),
                        reference.versionId(),
                        read.receivedAt(),
                        digest,
                        json.writeValueAsString(read.shape().points()),
                        json.writeValueAsString(read.evidence()),
                        read.receivedAt(),
                        bindInput(reference,read))
                .update();
        return Publication.UPDATED;
    }

    private boolean retainUsableEvidence(Reference reference,Previous previous,Read incoming,String digest) {
        if(previous.sourceManifest()==null || !previous.digest().equals(digest)
                || incoming.evidence().sources().stream().noneMatch(s->s.id().equals("GBIS_WEB") && s.status().equals("UNAVAILABLE")))return false;
        try {
            var evidence=json.readValue(previous.evidence(),com.gustler.backend.routegeometry.api.RouteShapeEvidence.class);
            if(evidence.webPoints().isEmpty() || evidence.sources().stream().noneMatch(s->s.id().equals("GBIS_WEB") && s.status().equals("AVAILABLE"))
                    || evidence.sources().stream().anyMatch(s->!s.id().equals("GG_BMS_NODES")
                        && s.validUntil()!=null && !clock.instant().isBefore(s.validUntil())))return false;
            var binding=inputBinding(reference,incoming);
            return jdbc.sql("""
                SELECT EXISTS(SELECT 1 FROM route_input_manifest WHERE manifest_id=? AND route_id=?
                    AND reference_version_id=? AND input_digest=? AND evidence_revision=?)
                """).params(previous.sourceManifest(),reference.routeId(),reference.versionId(),binding.input().digest(),evidence.revision())
                .query(Boolean.class).single();
        } catch(RuntimeException invalid) {return false;}
    }

    private InputBinding inputBinding(Reference reference,Read read) {
        var catalog=jdbc.sql("""
            SELECT r.public_route_id,v.turn_sequence,v.acquisition_manifest_id
            FROM route r JOIN route_version v ON v.route_id=r.id WHERE v.id=? AND r.id=?
            """).params(reference.versionId(),reference.routeId())
            .query((row,n) -> new Catalog(row.getString(1),row.getObject(2,Integer.class),row.getString(3))).single();
        var visits=jdbc.sql("""
            SELECT s.stop_id,s.stop_order,s.direction,s.boarding_allowed,l.x,l.y
            FROM route_stop s LEFT JOIN LATERAL (
                SELECT x,y FROM route_stop_location_history h
                WHERE h.route_version_id=s.route_version_id AND h.stop_order=s.stop_order
                ORDER BY observed_at DESC LIMIT 1
            ) l ON true WHERE s.route_version_id=? ORDER BY s.stop_order
            """).param(reference.versionId()).query((row,n) -> {
                Double x=row.getObject(5,Double.class),y=row.getObject(6,Double.class);
                RouteDisplayInput.Point point=x==null || y==null ? null : new RouteDisplayInput.Point(x,y);
                return new RouteDisplayInput.Visit(row.getString(1),row.getInt(2),row.getString(3),row.getBoolean(4),point);
            }).list();
        var input=new RouteDisplayInput(catalog.routeId(),catalog.turnSequence(),read.shape().points().stream()
            .map(p -> new RouteDisplayInput.Point(p.x(),p.y())).toList(),visits);
        return new InputBinding(catalog,input);
    }

    private record InputBinding(Catalog catalog,RouteDisplayInput input) {}

    private String bindInput(Reference reference,Read read) {
        if (read.acquisitionManifestId()==null || archive==RouteSourceArchive.NONE) return null;
        var binding=inputBinding(reference,read);
        var catalog=binding.catalog();
        var input=binding.input();
        var references=new java.util.ArrayList<String>();references.add(read.acquisitionManifestId());
        var receipts=new java.util.ArrayList<RouteSourceReceipt>();
        if(catalog.manifestId()!=null) references.add(catalog.manifestId());
        else receipts.add(new RouteSourceReceipt("GBIS_CATALOG","ROUTE_CATALOG",catalog.routeId(),
            RouteSourceReceipt.Status.UNRECORDED_LEGACY,null,read.receivedAt().toInstant(),"NOT_PROVIDED",
            RouteSourceReceipt.CoordinateSystem.UNKNOWN,null));
        String sources=archive.record("ROUTE_INPUT_SOURCES",receipts,references);
        return archive.bind(reference.routeId(),reference.versionId(),sources,input,read.evidence());
    }
    private record Catalog(String routeId,Integer turnSequence,String manifestId) {}

    private boolean isUnchanged(Previous previous, Read read, String digest) {
        if (previous.digest().equals(digest)) {
            return true;
        }
        return !read.receivedAt().isAfter(previous.receivedAt());
    }

    private record Previous(String digest, OffsetDateTime receivedAt, String evidence, String sourceManifest) {
    }
}
