package com.gustler.backend.routecatalog.infrastructure.supplementary;

import com.gustler.backend.gbis.api.GbisCapturePolicy;
import com.gustler.backend.gbis.api.GbisResponseCapture;
import com.gustler.backend.routecatalog.domain.geometry.RouteSourceArchive;
import com.gustler.backend.routecatalog.api.RefreshRouteGeometryEvidence;
import com.gustler.backend.routecatalog.domain.geometry.SupplementaryRouteGeometrySource;
import com.gustler.backend.routegeometry.api.RouteDisplayInput.Point;
import com.gustler.backend.routegeometry.api.RouteShapeEvidence;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.CoordinateSystem;
import com.gustler.backend.routegeometry.api.RouteSourceReceipt.Status;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Pages are archived once per shared acquisition; a failed refresh never replaces usable cache data. */
@Component
@ConditionalOnProperty(name = "route-geometry.multi-source.enabled", havingValue = "true")
public class GgBmsNodeGeometrySource implements SupplementaryRouteGeometrySource, RefreshRouteGeometryEvidence {
    private final ObjectMapper json;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final String key;
    private final BoundedJsonHttp.Fetch fetch;
    private final URI endpoint;
    static final String DEFAULT_ENDPOINT="https://openapi.gg.go.kr/TBBMSNODEM";
    private final RouteSourceArchive archive;
    static final int MAX_CAPTURE_BYTES = 32 * 1024 * 1024;
    private final int maxCaptureBytes;
    private volatile Snapshot cache;
    private volatile String lastFailedManifest;

    public GgBmsNodeGeometrySource(ObjectMapper json,JdbcClient jdbc,Clock clock,String key,Optional<RouteSourceArchive> archive) {
        this(json,jdbc,clock,key,new BoundedJsonHttp()::getWithMetadata,archive.orElse(RouteSourceArchive.NONE));
    }
    @org.springframework.beans.factory.annotation.Autowired
    public GgBmsNodeGeometrySource(ObjectMapper json,JdbcClient jdbc,Clock clock,
            @Value("${route-geometry.multi-source.gg-key:}") String key,Optional<RouteSourceArchive> archive,
            @Value("${route-geometry.multi-source.bms-node-endpoint:https://openapi.gg.go.kr/TBBMSNODEM}") String endpoint) {
        this(json,jdbc,clock,key,new BoundedJsonHttp()::getWithMetadata,archive.orElse(RouteSourceArchive.NONE),MAX_CAPTURE_BYTES,endpoint);
    }
    public GgBmsNodeGeometrySource(ObjectMapper json, JdbcClient jdbc, Clock clock, String key) {
        this(json, jdbc, clock, key, new BoundedJsonHttp()::getWithMetadata, RouteSourceArchive.NONE);
    }
    GgBmsNodeGeometrySource(ObjectMapper json, JdbcClient jdbc, Clock clock, String key,
            java.util.function.Function<URI, byte[]> fetch) {
        this(json, jdbc, clock, key, BoundedJsonHttp.legacy(fetch), RouteSourceArchive.NONE);
    }
    GgBmsNodeGeometrySource(ObjectMapper json, JdbcClient jdbc, Clock clock, String key,
            BoundedJsonHttp.Fetch fetch, RouteSourceArchive archive) {
        this(json, jdbc, clock, key, fetch, archive, MAX_CAPTURE_BYTES);
    }
    GgBmsNodeGeometrySource(ObjectMapper json, JdbcClient jdbc, Clock clock, String key,
            BoundedJsonHttp.Fetch fetch, RouteSourceArchive archive, int maxCaptureBytes) {
        this(json,jdbc,clock,key,fetch,archive,maxCaptureBytes,DEFAULT_ENDPOINT);
    }
    GgBmsNodeGeometrySource(ObjectMapper json,JdbcClient jdbc,Clock clock,String key,
            BoundedJsonHttp.Fetch fetch,RouteSourceArchive archive,int maxCaptureBytes,String endpoint) {
        this.endpoint=ConfiguredGeometryEndpoint.parse(endpoint);
        new CaptureBudget(maxCaptureBytes); // Reject invalid limits before a network request can run.
        this.json = json; this.jdbc = jdbc; this.clock = clock; this.key = key; this.fetch = fetch; this.archive = archive;
        this.maxCaptureBytes = maxCaptureBytes;
    }
    public String id() { return "GG_BMS_NODES"; }

    @Override
    public synchronized void refresh() {
        List<RouteSourceReceipt> receipts = new ArrayList<>();
        var budget = new CaptureBudget(maxCaptureBytes);
        if (key.isBlank()) {
            lastFailedManifest = archive.record("BMS_ATTEMPT", List.of(receipt("SHARED", Status.NOT_CONFIGURED, null, null)), List.of());
            return;
        }
        try {
            List<Point> points = new ArrayList<>();List<QuarantinedNode> quarantined=new ArrayList<>();
            Set<String> ids = new HashSet<>();
            int total = -1, received=0;
            for (int page = 1; page <= 100; page++) {
                var response = safeFetch(URI.create(endpoint+"?Type=json&pSize=1000&pIndex="
                        + page + "&KEY=" + URLEncoder.encode(key, StandardCharsets.UTF_8)));
                Instant acquired = clock.instant();
                if (response.httpStatus() == null || response.httpStatus() != 200) {
                    receipts.add(receipt("PAGE:" + page, response.httpStatus() == null ? Status.TRANSPORT_FAILURE : Status.HTTP_ERROR,
                            response.httpStatus(), null, acquired));
                    throw new IllegalArgumentException("BMS_PAGE_UNAVAILABLE");
                }
                byte[] bytes = response.body();
                if (bytes != null && !budget.tryCapture(bytes.length)) {
                    receipts.add(new RouteSourceReceipt(id(), "NODE_PAGE_RETENTION_LIMIT", "PAGE:" + page,
                            Status.INVALID_RESPONSE, response.httpStatus(), acquired, "NOT_PROVIDED",
                            CoordinateSystem.DEGREES_MINUTES_ASSUMED, null));
                    throw new IllegalArgumentException("BMS_CAPTURE_BUDGET_EXCEEDED");
                }
                final Page parsed;
                try { parsed = parsePage(bytes,total,received,ids); }
                catch (RuntimeException invalid) {
                    receipts.add(receipt("PAGE:" + page, Status.INVALID_RESPONSE, response.httpStatus(), null, acquired));
                    throw new IllegalArgumentException("BMS_PAGE_INVALID");
                }
                var screened = GbisCapturePolicy.screen(bytes, key, json);
                Status status = screened.disposition() == GbisResponseCapture.Disposition.AVAILABLE ? Status.SUCCESS
                        : screened.disposition() == GbisResponseCapture.Disposition.SENSITIVE_RESPONSE ? Status.SENSITIVE_RESPONSE : Status.INVALID_RESPONSE;
                receipts.add(receipt("PAGE:" + page, status, response.httpStatus(), status == Status.SUCCESS ? bytes : null, acquired));
                total=parsed.total();received+=parsed.rawCount();points.addAll(parsed.points());quarantined.addAll(parsed.quarantined());
                if(received==total)break;
            }
            if(received!=total || points.isEmpty())throw new IllegalArgumentException("BMS_INCOMPLETE_OR_NO_VALID_POINTS");
            NormalizedV2 normalized=quarantined.isEmpty()?null:new NormalizedV2("bms-positive-node-snapshot-v2",total,List.copyOf(points),List.copyOf(quarantined),true,true,"POSITIVE_CORROBORATION_ONLY_NO_ABSENCE_INFERENCE");
            String text=json.writeValueAsString(normalized==null?points:normalized);
            byte[] normalizedBytes = text.getBytes(StandardCharsets.UTF_8);
            String digest = GbisWebGeometrySource.sha(normalizedBytes);
            // This payload is our normalized input, never an upstream successful response.
            // Keep it out of the raw receipt list so failed attempts cannot claim a complete derived snapshot.
            List<RouteSourceReceipt> complete = new ArrayList<>(receipts);
            complete.add(new RouteSourceReceipt(id(), normalized==null?"NORMALIZED_NODE_SNAPSHOT_V1":"NORMALIZED_NODE_SNAPSHOT_V2", "SHARED", Status.DERIVED,
                    null, clock.instant(), "NOT_PROVIDED", CoordinateSystem.WGS84_ASSUMED, normalizedBytes));
            String manifest = archive.record("BMS_SNAPSHOT", complete, List.of());
            // PostgreSQL timestamps use microseconds; normalize the cache anchor before both memory and DB use.
            // Receipt documents separately preserve the precise acquisition instant.
            Instant now = clock.instant().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            int updated = jdbc.sql("""
                INSERT INTO route_geometry_source_cache(source_id,fetched_at,content_digest,document,acquisition_manifest_id)
                VALUES (?,?,?,CAST(? AS jsonb),?)
                ON CONFLICT(source_id) DO UPDATE SET fetched_at=EXCLUDED.fetched_at,content_digest=EXCLUDED.content_digest,
                    document=EXCLUDED.document,acquisition_manifest_id=EXCLUDED.acquisition_manifest_id
                WHERE route_geometry_source_cache.fetched_at<=EXCLUDED.fetched_at
                """).params(id(), OffsetDateTime.ofInstant(now, ZoneOffset.UTC), digest, text, manifest).update();
            // Another worker may already have committed a newer acquisition. Never serve a rejected local candidate.
            cache = updated == 1 ? new Snapshot(now,digest,List.copyOf(points),manifest,normalized) : null;
            lastFailedManifest = null;
        } catch (RuntimeException failure) {
            if (receipts.isEmpty()) receipts.add(receipt("SHARED", Status.UNAVAILABLE, null, null));
            lastFailedManifest = archive.record("BMS_ATTEMPT", receipts, List.of());
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("event=route_geometry_reference_refresh_failed source=GG_BMS_NODES");
        }
    }

    private BoundedJsonHttp.Result safeFetch(URI uri) {
        try { return fetch.get(uri); }
        catch (RuntimeException failure) { return new BoundedJsonHttp.Result(null, null); }
    }

    /** Validate complete page ordering before retaining any bytes from that page. */
    private Page parsePage(byte[] bytes, int expectedTotal, int previousCount, Set<String> ids) {
        var service = json.readTree(bytes).path("TBBMSNODEM");
        var header = service.path(0).path("head");
        int declared = header.path(0).path("list_total_count").asInt(-1);
        if (!"INFO-000".equals(header.path(1).path("RESULT").path("CODE").asText()) || declared < 1 || declared > 100000
                || expectedTotal != -1 && declared != expectedTotal) throw new IllegalArgumentException("BMS_HEADER");
        var rows = service.path(1).path("row");
        if (!rows.isArray() || rows.size() != Math.min(1000, declared - previousCount))
            throw new IllegalArgumentException("BMS_PARTIAL_PAGE");
        List<Point> points=new ArrayList<>();List<QuarantinedNode> quarantined=new ArrayList<>();int offset=0;
        String pageSha=GbisWebGeometrySource.sha(bytes);
        for(var row:rows) {
            String nodeId=row.path("NODE_ID").asText();
            if(nodeId.isBlank() || !ids.add(nodeId))throw new IllegalArgumentException("BMS_DUPLICATE");
            try {
                points.add(new Point(ddmm(Double.parseDouble(row.path("LOGT").asText())),ddmm(Double.parseDouble(row.path("LAT").asText()))));
            } catch(RuntimeException invalid) {
                String reason=invalid.getMessage();
                if(reason==null || !reason.matches("BMS_[A-Z_]{1,60}"))reason="BMS_INVALID_NODE_COORDINATE";
                quarantined.add(new QuarantinedNode(previousCount+offset,nodeId,reason,pageSha,GbisWebGeometrySource.sha(json.writeValueAsBytes(row))));
            }
            offset++;
        }
        return new Page(declared,List.copyOf(points),rows.size(),List.copyOf(quarantined));
    }

    public Contribution read(String routeId) {
        Snapshot current = cache;
        if (current == null) {
            try {
                current = jdbc.sql("SELECT fetched_at,content_digest,document::text,acquisition_manifest_id FROM route_geometry_source_cache WHERE source_id=?")
                    .param(id()).query((row, index) -> restoreSnapshot(row.getTimestamp(1).toInstant(),row.getString(2),row.getString(3),row.getString(4))).optional().orElse(null);
                if (current != null) verifyRestored(current);
                cache = current;
            } catch (RuntimeException corrupt) {
                return unavailable("UNAVAILABLE", Status.INVALID_RESPONSE, List.of());
            }
        }
        if (current == null) return unavailable(key.isBlank() ? "NOT_CONFIGURED" : "UNAVAILABLE",
                key.isBlank() ? Status.NOT_CONFIGURED : Status.UNAVAILABLE, failedReferences());
        List<String> references = new ArrayList<>();
        if (current.manifestId() != null) references.add(current.manifestId());
        for (String failed : failedReferences()) if (!references.contains(failed)) references.add(failed);
        if (current.at().isAfter(clock.instant()) || !current.at().plus(Duration.ofHours(24)).isAfter(clock.instant()))
            return new Contribution(Optional.empty(), new RouteShapeEvidence.Source(id(), "STALE", current.digest()),
                    List.of(), List.of(), List.of(receipt("SHARED", Status.STALE, null, null)), references);
        List<RouteSourceReceipt> receipts = current.manifestId() == null
                ? List.of(receipt("SHARED", Status.UNRECORDED_LEGACY, null, null)) : List.of();
        return new Contribution(Optional.empty(), new RouteShapeEvidence.Source(id(),current.normalized()==null?"AVAILABLE":"AVAILABLE_WITH_QUARANTINE",current.digest(),
                current.at().plus(Duration.ofHours(24))), List.of(), current.points(), receipts, references);
    }

    private void verifyRestored(Snapshot snapshot) {
        Objects.requireNonNull(snapshot.points());
        String actual=GbisWebGeometrySource.sha(json.writeValueAsBytes(snapshot.normalized()==null?snapshot.points():snapshot.normalized()));
        if (!actual.equals(snapshot.digest())) throw new IllegalArgumentException("BMS_CACHE_DIGEST_MISMATCH");
        if (snapshot.manifestId() == null) return;
        var manifest = archive.restore(snapshot.manifestId());
        if (!"BMS_SNAPSHOT".equals(manifest.purpose())) throw new IllegalArgumentException("BMS_CACHE_MANIFEST_KIND");
        var receipts = manifest.receipts();
        var derived = receipts.stream().filter(r -> r.status() == Status.DERIVED).toList();
        if (derived.size() != 1) throw new IllegalArgumentException("BMS_CACHE_DERIVED_SNAPSHOT_REQUIRED");
        var normalized = derived.getFirst();
        boolean version2=snapshot.normalized()!=null;
        if (!id().equals(normalized.sourceId()) || !(version2?"NORMALIZED_NODE_SNAPSHOT_V2":"NORMALIZED_NODE_SNAPSHOT_V1").equals(normalized.operation())
                || !"SHARED".equals(normalized.scope()) || normalized.httpStatus() != null
                || normalized.coordinateSystem() != CoordinateSystem.WGS84_ASSUMED
                || !actual.equals(normalized.bodySha256()))
            throw new IllegalArgumentException("BMS_CACHE_DERIVED_METADATA_OR_DIGEST_MISMATCH");
        var normalizedPoints=version2?json.readValue(normalized.body(),NormalizedV2.class).points():json.readValue(normalized.body(),new TypeReference<List<Point>>() {});
        if(version2 && !json.readValue(normalized.body(),NormalizedV2.class).equals(snapshot.normalized()))
            throw new IllegalArgumentException("BMS_CACHE_DERIVED_QUARANTINE_MISMATCH");
        if (normalizedPoints == null || normalizedPoints.isEmpty() || normalizedPoints.size() > 100000
                || !normalizedPoints.equals(snapshot.points()))
            throw new IllegalArgumentException("BMS_CACHE_DERIVED_POINTS_MISMATCH");

        var pages = receipts.stream().filter(r -> r.status() != Status.DERIVED).toList();
        int sourceRows=version2?snapshot.normalized().sourceRows():normalizedPoints.size();
        if (pages.size() != (sourceRows+999)/1000)
            throw new IllegalArgumentException("BMS_CACHE_PAGE_COUNT");
        Set<String> ids = new HashSet<>();int acceptedOffset=0;List<QuarantinedNode> rechecked=new ArrayList<>();boolean completeRaw=true;
        for (int i=0;i<pages.size();i++) {
            var receipt = pages.get(i);
            if (!id().equals(receipt.sourceId()) || !"NODE_PAGE".equals(receipt.operation()) || !("PAGE:"+(i+1)).equals(receipt.scope())
                    || !Integer.valueOf(200).equals(receipt.httpStatus())
                    || receipt.coordinateSystem() != CoordinateSystem.DEGREES_MINUTES_ASSUMED
                    || (receipt.status() != Status.SUCCESS && receipt.status() != Status.SENSITIVE_RESPONSE))
                throw new IllegalArgumentException("BMS_CACHE_PAGE_ORDER");
            // Sensitive originals remain unavailable. Their normalized values are still sealed above.
            // Every available original page is additionally checked against its exact normalized slice.
            if (receipt.status() == Status.SUCCESS) {
                int offset = i * 1000;
                var page=parsePage(receipt.body(),sourceRows,offset,ids);
                int excludedBefore=version2?(int)snapshot.normalized().quarantined().stream().filter(q->q.sourceRecordIndex()<offset).count():0;
                acceptedOffset=offset-excludedBefore;
                if(acceptedOffset+page.points().size()>normalizedPoints.size() || !page.points().equals(normalizedPoints.subList(acceptedOffset,acceptedOffset+page.points().size())))
                    throw new IllegalArgumentException("BMS_CACHE_MANIFEST_POINTS_MISMATCH");
                if(version2) {
                    var expected=snapshot.normalized().quarantined().stream().filter(q->q.sourceRecordIndex()>=offset && q.sourceRecordIndex()<offset+page.rawCount()).toList();
                    if(!page.quarantined().equals(expected))throw new IllegalArgumentException("BMS_CACHE_QUARANTINE_SOURCE_MISMATCH");
                    rechecked.addAll(page.quarantined());
                } else if(!page.quarantined().isEmpty())throw new IllegalArgumentException("BMS_V1_CANNOT_HIDE_QUARANTINE");
            } else completeRaw=false;
        }
        if(version2 && completeRaw && !rechecked.equals(snapshot.normalized().quarantined()))throw new IllegalArgumentException("BMS_CACHE_QUARANTINE_INCOMPLETE");
    }
    private List<String> failedReferences() { return lastFailedManifest == null ? List.of() : List.of(lastFailedManifest); }
    private Contribution unavailable(String status, Status receiptStatus, List<String> references) {
        return new Contribution(Optional.empty(), new RouteShapeEvidence.Source(id(), status, null), List.of(), List.of(),
                List.of(receipt("SHARED", receiptStatus, null, null)), references);
    }
    private RouteSourceReceipt receipt(String scope, Status status, Integer http, byte[] body) {
        return receipt(scope, status, http, body, clock.instant());
    }
    private RouteSourceReceipt receipt(String scope, Status status, Integer http, byte[] body, Instant acquired) {
        return new RouteSourceReceipt(id(), scope.startsWith("PAGE:") ? "NODE_PAGE" : "CACHE_READ", scope, status,
                http, acquired, "NOT_PROVIDED", CoordinateSystem.DEGREES_MINUTES_ASSUMED, body);
    }
    static double ddmm(double value) {
        if (!Double.isFinite(value) || value <= 0) throw new IllegalArgumentException("BMS_COORDINATE");
        double degrees = Math.floor(value / 100), minutes = value - degrees * 100;
        if (minutes >= 60) throw new IllegalArgumentException("BMS_MINUTES");
        return degrees + minutes / 60;
    }
    /** Aggregate response budget bounds both retained pages and later validation work; no body allocation is needed to account for it. */
    static final class CaptureBudget {
        private final int maximum;
        private int captured;
        CaptureBudget(int maximum) {
            if (maximum < 1 || maximum > MAX_CAPTURE_BYTES)
                throw new IllegalArgumentException("BMS_CAPTURE_BUDGET_INVALID");
            this.maximum = maximum;
        }
        boolean tryCapture(int bytes) {
            if (bytes < 0) throw new IllegalArgumentException("BMS_CAPTURE_SIZE_INVALID");
            if (bytes > maximum - captured) return false;
            captured += bytes;
            return true;
        }
        int capturedBytes() { return captured; }
    }

    private Snapshot restoreSnapshot(Instant at,String digest,String document,String manifest) {
        var tree=json.readTree(document);
        if(tree.isArray())return new Snapshot(at,digest,json.readValue(document,new TypeReference<List<Point>>(){}),manifest,null);
        var normalized=json.readValue(document,NormalizedV2.class);validateNormalized(normalized);
        return new Snapshot(at,digest,normalized.points(),manifest,normalized);
    }
    private static void validateNormalized(NormalizedV2 value) {
        if(!"bms-positive-node-snapshot-v2".equals(value.format()) || value.sourceRows()<1 || value.sourceRows()>100000
                || value.points().isEmpty() || value.quarantined().isEmpty() || value.sourceRows()!=value.points().size()+value.quarantined().size()
                || !value.completeSourcePages() || !value.duplicateNodeIdsRejected()
                || !"POSITIVE_CORROBORATION_ONLY_NO_ABSENCE_INFERENCE".equals(value.condition()))
            throw new IllegalArgumentException("BMS_QUARANTINE_DOCUMENT_INVALID");
        Set<Integer> records=new HashSet<>();Set<String> ids=new HashSet<>();int previous=-1;
        for(var q:value.quarantined()) {
            if(q.sourceRecordIndex()<=previous || q.sourceRecordIndex()>=value.sourceRows() || !records.add(q.sourceRecordIndex()) || q.nodeId()==null || q.nodeId().isBlank()
                    || !ids.add(q.nodeId()) || q.reason()==null || !q.reason().matches("BMS_[A-Z_]{1,60}") || q.pageSha256()==null || !q.pageSha256().matches("[0-9a-f]{64}")
                    || q.normalizedRowSha256()==null || !q.normalizedRowSha256().matches("[0-9a-f]{64}"))throw new IllegalArgumentException("BMS_QUARANTINE_RECORD_INVALID");
            previous=q.sourceRecordIndex();
        }
    }
    public record QuarantinedNode(int sourceRecordIndex,String nodeId,String reason,String pageSha256,String normalizedRowSha256) {}
    public record NormalizedV2(String format,int sourceRows,List<Point> points,List<QuarantinedNode> quarantined,
            boolean completeSourcePages,boolean duplicateNodeIdsRejected,String condition) {
        public NormalizedV2 {points=List.copyOf(points);quarantined=List.copyOf(quarantined);}
    }
    private record Page(int total,List<Point> points,int rawCount,List<QuarantinedNode> quarantined) {}
    private record Snapshot(Instant at,String digest,List<Point> points,String manifestId,NormalizedV2 normalized) {}
}
