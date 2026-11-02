package com.gustler.backend.routecatalog.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import com.gustler.backend.gbis.api.GbisApiCaller;
import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisRouteLineSource;
import com.gustler.backend.quota.api.ApiCallQuota;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometry.Outcome;
import com.gustler.backend.routecatalog.application.RouteGeometryRefresher;
import com.gustler.backend.routecatalog.domain.geometry.RouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.RouteShape;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource.Contribution;
import com.gustler.backend.routecatalog.infrastructure.gbis.GbisRouteGeometrySource;
import com.gustler.backend.routegeometry.api.RouteDisplayDbSamples;
import com.gustler.backend.routegeometry.api.RouteDisplayInput;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.support.PostgresTestContainer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

/** Real HTTP decoding, receipt screening, publication and PostgreSQL restoration in one acquisition flow. */
@SpringBootTest(classes=RouteGeometryAcquisitionArchiveTest.Config.class)
class RouteGeometryAcquisitionArchiveTest {
    @Configuration(proxyBeanMethods=false)
    @EnableAutoConfiguration
    @Import({PostgresTestContainer.class,JdbcRouteGeometryStore.class,JdbcRouteSourceArchive.class})
    static class Config { @Bean ObjectMapper json() { return new ObjectMapper(); } }
    @Autowired JdbcClient jdbc;
    @Autowired ObjectMapper json;
    @Autowired JdbcRouteGeometryStore store;
    @Autowired RouteSourceArchive archive;
    private static final String ROUTE="204000057";
    private static final String KEY="archive-test-secret+/=";
    private static final Instant START=Instant.parse("2026-11-02T03:00:00Z");
    private MutableClock clock;
    private MockRestServiceServer http;
    private GbisRouteGeometrySource primary;
    private ApiCallQuota quota;

    @BeforeEach void reset() {
        jdbc.sql("TRUNCATE route,route_source_blob,route_source_receipt,route_source_manifest CASCADE").update();
        RouteDisplayDbSamples.seed(jdbc,ROUTE);
        jdbc.sql("DELETE FROM route_geometry").update();
        clock=new MutableClock(START);
        var builder=RestClient.builder().baseUrl("https://gbis.test");
        http=MockRestServiceServer.bindTo(builder).build();
        primary=new GbisRouteGeometrySource(new GbisRouteLineSource(new GbisApiCaller(builder.build(),
            new GbisClientOptions("https://gbis.test",KEY),json),json),clock);
        quota=mock(ApiCallQuota.class);
        when(quota.reserveRouteCatalog(any(OffsetDateTime.class),eq(1))).thenReturn(true);
    }
    private Outcome refresh(Contribution... contributions) {
        var sources=new ArrayList<SupplementaryRouteGeometrySource>();
        for(var value:contributions) sources.add(new SupplementaryRouteGeometrySource() {
            @Override public String id() { return value.source().id(); }
            @Override public Contribution read(String routeId) { assertThat(routeId).isEqualTo(ROUTE);return value; }
        });
        return new RouteGeometryRefresher(store,primary,quota,sources,Optional.of(archive))
            .refresh(ROUTE,OffsetDateTime.ofInstant(clock.instant(),ZoneOffset.UTC));
    }
    private static byte[] raw(double middleX) {
        return (" {\n  \"response\":{\"msgHeader\":{\"resultCode\":0},\"msgBody\":{\"busRouteLineList\":["
            +"{\"lineSeq\":1,\"x\":127,\"y\":37},"
            +"{\"lineSeq\":2,\"x\":"+middleX+",\"y\":37.01},"
            +"{\"lineSeq\":3,\"x\":127,\"y\":37}]}},\"note\":\"원응답 그대로\"\n}\n")
            .getBytes(StandardCharsets.UTF_8);
    }
    private static java.util.Map<String,Object> webPoint(int sequence,double x,double y) {
        return java.util.Map.of("seq",sequence,"type",3,"lon",6378137d*Math.toRadians(x),
            "lat",6378137d*Math.log(Math.tan(Math.PI/4+Math.toRadians(y)/2)));
    }
    private void reply(HttpStatus status,byte[] bytes) {
        http.expect(requestTo(containsString("routeId="+ROUTE))).andRespond(request -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                .as("No database transaction is held while waiting on the provider").isFalse();
            return withStatus(status).contentType(MediaType.APPLICATION_JSON).body(bytes).createResponse(request);
        });
    }
    private String currentManifest() {
        return jdbc.sql("SELECT source_manifest_id FROM route_geometry ORDER BY received_at DESC,id DESC LIMIT 1")
            .query(String.class).single();
    }
    private JdbcRouteSourceArchive.InputDocument inputDocument(String id) {
        return json.readValue(jdbc.sql("SELECT document FROM route_input_manifest WHERE manifest_id=?")
            .param(id).query(String.class).single(),JdbcRouteSourceArchive.InputDocument.class);
    }
    private List<RouteSourceReceipt> allReceipts(String manifest) {
        var result=new ArrayList<RouteSourceReceipt>();flatten(manifest,new HashSet<>(),result);return result;
    }
    private void flatten(String id,Set<String> visited,List<RouteSourceReceipt> result) {
        if(!visited.add(id)) return;
        var restored=archive.restore(id);result.addAll(restored.receipts());
        for(String reused:restored.reusedManifestIds()) flatten(reused,visited,result);
    }
    private int count(String table) { return jdbc.sql("SELECT count(*) FROM "+table).query(Integer.class).single(); }
    private RouteDisplayInput expectedInput(double middleX) {
        return new RouteDisplayInput(ROUTE,2,List.of(new RouteDisplayInput.Point(127,37),
            new RouteDisplayInput.Point(middleX,37.01),new RouteDisplayInput.Point(127,37)),List.of(
                new RouteDisplayInput.Visit("206000637",1,"UP",true,new RouteDisplayInput.Point(127,37)),
                new RouteDisplayInput.Visit("206000638",2,"UP",true,new RouteDisplayInput.Point(127.01,37.01)),
                new RouteDisplayInput.Visit("206000637",3,"DOWN",true,new RouteDisplayInput.Point(127,37))));
    }
    private void noCredentialsStored() {
        for(byte[] bytes:jdbc.sql("SELECT body FROM route_source_blob").query((r,n)->r.getBytes(1)).list())
            assertThat(new String(bytes,StandardCharsets.UTF_8)).doesNotContain(KEY,"serviceKey=","https://gbis.test");
        for(String table:List.of("route_source_receipt","route_source_manifest","route_input_manifest"))
            for(String text:jdbc.sql("SELECT document FROM "+table).query(String.class).list())
                assertThat(text).doesNotContain(KEY,"serviceKey=","https://gbis.test");
    }

    @Test void real_primary_response_is_restorable_and_bound_to_exact_route_stops_with_explicit_legacy_catalog() {
        byte[] original=raw(127.01);reply(HttpStatus.OK,original);
        assertThat(refresh()).isEqualTo(Outcome.UPDATED);http.verify();
        String id=currentManifest();var bound=inputDocument(id);
        assertThat(bound.input()).isEqualTo(expectedInput(127.01));
        assertThat(bound.inputDigest()).isEqualTo(expectedInput(127.01).digest());
        assertThat(bound.evidenceRevision()).isEqualTo(bound.evidence().revision());
        var receipts=allReceipts(bound.acquisitionManifestId());
        var primaryReceipt=receipts.stream().filter(r->r.sourceId().equals("GBIS_API")).findFirst().orElseThrow();
        assertThat(primaryReceipt.status()).isEqualTo(RouteSourceReceipt.Status.SUCCESS);
        assertThat(primaryReceipt.body()).containsExactly(original);
        assertThat(primaryReceipt.bodySha256()).isEqualTo(RouteSourceReceipt.sha256(original));
        assertThat(primaryReceipt.acquiredAt()).isEqualTo(START);
        assertThat(primaryReceipt.providerVersion()).isEqualTo("NOT_PROVIDED");
        assertThat(primaryReceipt.coordinateSystem()).isEqualTo(RouteSourceReceipt.CoordinateSystem.WGS84_ASSUMED);
        var catalog=receipts.stream().filter(r->r.sourceId().equals("GBIS_CATALOG")).findFirst().orElseThrow();
        assertThat(catalog.status()).isEqualTo(RouteSourceReceipt.Status.UNRECORDED_LEGACY);
        assertThat(catalog.body()).isNull();assertThat(catalog.bodySha256()).isNull();
        assertThat(count("route_source_blob")).isEqualTo(1);noCredentialsStored();
    }
    @Test void primary_HTTP_failure_and_supplementary_success_preserve_partial_receipts_and_web_selection() {
        reply(HttpStatus.UNAUTHORIZED,("{\"error\":\"https://gbis.test?serviceKey="+KEY+"\"}").getBytes(StandardCharsets.UTF_8));
        byte[] web=json.writeValueAsBytes(java.util.Map.of("success",true,"result",java.util.Map.of("gg",java.util.Map.of(
            "upLine",java.util.Map.of("count",2,"list",List.of(webPoint(1,127,37),webPoint(2,127.02,37.01))),
            "downLine",java.util.Map.of("count",2,"list",List.of(webPoint(1,127.02,37.01),webPoint(2,127,37)))))));
        var shape=new RouteShape(List.of(new RouteShape.Point(127,37),new RouteShape.Point(127.02,37.01),new RouteShape.Point(127,37)));
        var receipt=new RouteSourceReceipt("GBIS_WEB","MAP_LINE",ROUTE,RouteSourceReceipt.Status.SUCCESS,200,
            START,"NOT_PROVIDED",RouteSourceReceipt.CoordinateSystem.WEB_MERCATOR_ASSUMED,web);
        var contribution=new Contribution(Optional.of(new RouteGeometrySource.Read(shape,OffsetDateTime.ofInstant(START,ZoneOffset.UTC))),
            new RouteShapeEvidence.Source("GBIS_WEB","AVAILABLE",receipt.bodySha256()),List.of(),List.of(),List.of(receipt),List.of());
        assertThat(refresh(contribution)).isEqualTo(Outcome.UPDATED);http.verify();
        var bound=inputDocument(currentManifest());assertThat(bound.evidence().selectedSource()).isEqualTo("GBIS_WEB");
        assertThat(bound.inputDigest()).isEqualTo(expectedInput(127.02).digest());
        var receipts=allReceipts(bound.acquisitionManifestId());
        var failed=receipts.stream().filter(r->r.sourceId().equals("GBIS_API")).findFirst().orElseThrow();
        assertThat(failed.status()).isEqualTo(RouteSourceReceipt.Status.HTTP_ERROR);assertThat(failed.httpStatus()).isEqualTo(401);
        assertThat(failed.body()).isNull();assertThat(failed.bodySha256()).isNull();
        assertThat(receipts.stream().filter(r->r.sourceId().equals("GBIS_WEB")).findFirst().orElseThrow().body()).containsExactly(web);
        assertThat(count("route_source_blob")).isEqualTo(1);noCredentialsStored();
    }
    @Test void total_failure_still_archives_the_attempt_without_publishing_geometry_or_error_bodies() {
        reply(HttpStatus.INTERNAL_SERVER_ERROR,("https://gbis.test?serviceKey="+KEY).getBytes(StandardCharsets.UTF_8));
        assertThat(refresh(Contribution.unavailable("GBIS_WEB"))).isEqualTo(Outcome.SOURCE_UNAVAILABLE);http.verify();
        assertThat(count("route_geometry")).isZero();assertThat(count("route_input_manifest")).isZero();
        assertThat(count("route_source_blob")).isZero();
        String id=jdbc.sql("SELECT manifest_id FROM route_source_manifest WHERE purpose='ROUTE_GEOMETRY_ACQUISITION'").query(String.class).single();
        var receipts=archive.restore(id).receipts();
        assertThat(receipts).extracting(RouteSourceReceipt::status)
            .containsExactly(RouteSourceReceipt.Status.HTTP_ERROR,RouteSourceReceipt.Status.UNAVAILABLE);
        assertThat(receipts).allSatisfy(r->{assertThat(r.body()).isNull();assertThat(r.bodySha256()).isNull();});
        noCredentialsStored();
    }
    @Test void quota_denial_is_an_explicit_bodyless_receipt_without_a_provider_request() {
        when(quota.reserveRouteCatalog(any(OffsetDateTime.class),eq(1))).thenReturn(false);
        assertThat(refresh()).isEqualTo(Outcome.NO_QUOTA);http.verify();
        String id=jdbc.sql("SELECT manifest_id FROM route_source_manifest").query(String.class).single();
        assertThat(archive.restore(id).receipts()).extracting(RouteSourceReceipt::status).containsExactly(RouteSourceReceipt.Status.NO_QUOTA);
        assertThat(count("route_geometry")).isZero();assertThat(count("route_source_blob")).isZero();
    }
    @Test void fresh_identical_geometry_advances_provenance_and_preserves_old_manifest_while_deduplicating_bytes() {
        byte[] bytes=raw(127.01);reply(HttpStatus.OK,bytes);reply(HttpStatus.OK,bytes);
        assertThat(refresh()).isEqualTo(Outcome.UPDATED);
        String oldId=currentManifest();var old=inputDocument(oldId);
        clock.now=START.plusSeconds(10);
        assertThat(refresh()).isEqualTo(Outcome.UNCHANGED);http.verify();
        String newId=currentManifest();var fresh=inputDocument(newId);
        assertThat(newId).isNotEqualTo(oldId);assertThat(fresh.inputDigest()).isEqualTo(old.inputDigest());
        assertThat(inputDocument(oldId)).isEqualTo(old);
        assertThat(allReceipts(old.acquisitionManifestId()).stream().filter(r->r.sourceId().equals("GBIS_API")).findFirst().orElseThrow().acquiredAt()).isEqualTo(START);
        assertThat(allReceipts(fresh.acquisitionManifestId()).stream().filter(r->r.sourceId().equals("GBIS_API")).findFirst().orElseThrow().acquiredAt()).isEqualTo(START.plusSeconds(10));
        assertThat(count("route_geometry")).isEqualTo(1);assertThat(count("route_input_manifest")).isEqualTo(2);
        assertThat(count("route_source_blob")).isEqualTo(1);
    }
    @Test void late_different_geometry_is_archived_but_cannot_relabel_the_current_input_manifest() {
        byte[] late=raw(127.02);reply(HttpStatus.OK,raw(127.01));reply(HttpStatus.OK,late);
        assertThat(refresh()).isEqualTo(Outcome.UPDATED);
        String current=currentManifest();var original=inputDocument(current);
        clock.now=START.minusSeconds(1);
        assertThat(refresh()).isEqualTo(Outcome.UNCHANGED);http.verify();
        assertThat(currentManifest()).isEqualTo(current);assertThat(inputDocument(current)).isEqualTo(original);
        assertThat(count("route_geometry")).isEqualTo(1);assertThat(count("route_input_manifest")).isEqualTo(1);
        assertThat(count("route_source_blob")).isEqualTo(2);
        assertThat(jdbc.sql("SELECT body FROM route_source_blob WHERE body_sha256=?").param(RouteSourceReceipt.sha256(late))
            .query((r,n)->r.getBytes(1)).single()).containsExactly(late);
    }
    private static class MutableClock extends Clock {
        private Instant now;
        MutableClock(Instant now) { this.now=now; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
