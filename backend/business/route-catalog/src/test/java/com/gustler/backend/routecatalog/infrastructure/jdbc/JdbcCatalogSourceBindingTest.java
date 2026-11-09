package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.*;
import com.gustler.backend.routecatalog.domain.*;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.support.PostgresTestContainer;
import java.time.*;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes=JdbcCatalogSourceBindingTest.Config.class)
class JdbcCatalogSourceBindingTest {
    @Configuration(proxyBeanMethods=false) @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteSourceArchive.class,JdbcCatalogSourceBinding.class})
    static class Config { @Bean ObjectMapper json() { return new ObjectMapper(); } }
    @Autowired JdbcClient jdbc;
    @Autowired RouteSourceArchive archive;
    @Autowired CatalogSourceBinding binding;
    @Autowired PlatformTransactionManager transactions;
    private static final OffsetDateTime AT=OffsetDateTime.parse("2026-11-02T00:00:00Z");
    private long version;
    @BeforeEach void seed() {
        jdbc.sql("TRUNCATE route,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
        long route=jdbc.sql("INSERT INTO route(public_route_id,source_id,source_route_id,display_name,start_stop_name,end_stop_name) VALUES ('204000057','GBIS','204000057','3330','A','B') RETURNING id").query(Long.class).single();
        version=jdbc.sql("INSERT INTO route_version(route_id,content_digest,valid_from) VALUES (?,?,'2026-11-01T00:00:00Z') RETURNING id").params(route,"a".repeat(64)).query(Long.class).single();
        jdbc.sql("INSERT INTO route_stop(route_version_id,stop_order,stop_id,name,direction,boarding_allowed) VALUES (?,1,'206000001','A','UP',true)").param(version).update();
        jdbc.sql("INSERT INTO route_stop_location_history(route_version_id,stop_order,stop_id,observed_at,x,y) VALUES (?,1,'206000001','2026-11-01T00:00:00Z',127,37)").param(version).update();
    }
    private UpstreamRoute captured(StopCoordinates coordinates) {
        return new UpstreamRoute("204000057","3330","A","B",new RouteStops(null,
            List.of(new RouteStop(1,"206000001","A",StopDirection.UP,true,coordinates))),null);
    }
    private String manifest(OffsetDateTime at) {
        var receipts=List.of("ROUTE_INFO","ROUTE_STATIONS").stream().map(operation -> new RouteSourceReceipt(
            "GBIS_API",operation,"204000057",RouteSourceReceipt.Status.SUCCESS,200,at.toInstant(),"NOT_PROVIDED",
            RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED,"{}".getBytes(java.nio.charset.StandardCharsets.UTF_8))).toList();
        return archive.record("ROUTE_CATALOG_ACQUISITION",receipts,List.of());
    }
    private boolean bind(UpstreamRoute route,String manifest,OffsetDateTime at) {
        return Boolean.TRUE.equals(new TransactionTemplate(transactions).execute(tx -> binding.bind(version,route,manifest,at)));
    }
    private String pointer() { return jdbc.sql("SELECT acquisition_manifest_id FROM route_version WHERE id=?").param(version).query(String.class).optional().orElse(null); }
    @Test void exact_catalog_connects_manifest_and_late_receipt_cannot_replace_it() {
        var route=captured(new StopCoordinates(127,37));var current=manifest(AT);var old=manifest(AT.minusMinutes(1));
        assertThat(bind(route,current,AT)).isTrue();assertThat(pointer()).isEqualTo(current);
        assertThat(bind(route,old,AT.minusMinutes(1))).isFalse();assertThat(pointer()).isEqualTo(current);
        assertThat(archive.restore(current).receipts()).hasSize(2);
    }
    @Test void missing_new_coordinates_do_not_claim_the_retained_previous_coordinates_as_captured() {
        String first=manifest(AT),second=manifest(AT.plusMinutes(1));
        assertThat(bind(captured(new StopCoordinates(127,37)),first,AT)).isTrue();
        assertThat(bind(captured(null),second,AT.plusMinutes(1))).isFalse();
        assertThat(pointer()).isNull();assertThat(archive.restore(first).receipts()).hasSize(2);
        assertThat(archive.restore(second).receipts()).hasSize(2);
    }
    @Test void differing_visit_identity_or_closed_version_cannot_receive_the_binding() {
        String source=manifest(AT);
        var mismatched=new UpstreamRoute("204000057","3330","A","B",new RouteStops(null,
            List.of(new RouteStop(1,"206000099","A",StopDirection.UP,true,new StopCoordinates(127,37)))),null);
        assertThat(bind(mismatched,source,AT)).isFalse();assertThat(pointer()).isNull();
        jdbc.sql("UPDATE route_version SET valid_to='2026-11-02T00:00:00Z' WHERE id=?").param(version).update();
        assertThat(bind(captured(new StopCoordinates(127,37)),source,AT)).isFalse();
    }
    @Test void another_routes_or_legacy_receipts_cannot_claim_complete_catalog_provenance() {
        String other=archive.record("ROUTE_CATALOG_ACQUISITION",List.of("ROUTE_INFO","ROUTE_STATIONS").stream()
            .map(operation -> new RouteSourceReceipt("GBIS_API",operation,"999999999",RouteSourceReceipt.Status.SUCCESS,
                200,AT.toInstant(),"NOT_PROVIDED",RouteSourceReceipt.CoordinateSystem.UNKNOWN,"{}".getBytes())).toList(),List.of());
        assertThat(bind(captured(new StopCoordinates(127,37)),other,AT)).isFalse();assertThat(pointer()).isNull();
        String legacy=archive.record("ROUTE_CATALOG_ACQUISITION",List.of(new RouteSourceReceipt("GBIS_API","ROUTE_CATALOG",
            "204000057",RouteSourceReceipt.Status.UNRECORDED_LEGACY,null,AT.toInstant(),"NOT_PROVIDED",
            RouteSourceReceipt.CoordinateSystem.UNKNOWN,null)),List.of());
        assertThat(bind(captured(new StopCoordinates(127,37)),legacy,AT)).isFalse();assertThat(pointer()).isNull();
    }
    @Test void binding_requires_the_catalog_publication_transaction() {
        assertThatThrownBy(() -> binding.bind(version,captured(new StopCoordinates(127,37)),manifest(AT),AT))
            .isInstanceOf(IllegalTransactionStateException.class);
    }
}
