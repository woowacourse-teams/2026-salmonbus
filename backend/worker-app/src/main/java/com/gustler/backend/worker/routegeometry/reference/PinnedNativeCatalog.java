package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;

import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.Crs;
import com.gustler.backend.routegeometry.engine.api.NativeReferenceProjection.NativePoint;
import com.gustler.backend.routegeometry.engine.api.RouteDisplayGeneration;
import com.gustler.backend.routegeometry.engine.api.accuracy.AccuracyEvidence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Server-pinned bounded slice of an NGII catalog. A model packet cannot install or re-pin this catalog. */
public final class PinnedNativeCatalog implements AccuracyEvidence.NativeCatalog {
    public record Pin(String bytesSha256,String originCatalogSha256,Set<String> archiveSha256) {
        public Pin {
            require(hash(bytesSha256) && hash(originCatalogSha256),"INVALID_SERVER_PIN");
            archiveSha256=Set.copyOf(archiveSha256);
            require(!archiveSha256.isEmpty() && archiveSha256.stream().allMatch(PinnedNativeCatalog::hash),"INVALID_ARCHIVE_PIN");
        }
    }
    public record NativeXYZ(double x,double y,double z) {
        public NativeXYZ { require(Double.isFinite(x)&&Double.isFinite(y)&&Double.isFinite(z),"INVALID_NATIVE_XYZ"); }
        public NativePoint xy() { return new NativePoint(x,y); }
        NativeXYZ at(NativeXYZ b,double t) { return new NativeXYZ(x+(b.x-x)*t,y+(b.y-y)*t,z+(b.z-z)*t); }
    }
    /** Original source frame is preserved independently from catalog canonical X/Y coordinates. */
    public record CatalogFeature(AccuracyEvidence.NativeFeature feature,List<NativeXYZ> xyz,
            List<NativeXYZ> sourceXYZ,String sourceHorizontalCrs,boolean sourceXYTransformApplied,String verticalDatum) {
        public CatalogFeature { xyz=List.copyOf(xyz);sourceXYZ=List.copyOf(sourceXYZ); }
    }
    private final Pin pin;
    private final Map<String,CatalogFeature> features;
    private PinnedNativeCatalog(Pin pin,Map<String,CatalogFeature> features) { this.pin=pin;this.features=Map.copyOf(features); }
    public static PinnedNativeCatalog load(byte[] bytes,Pin pin,ObjectMapper ignored) {
        java.util.Objects.requireNonNull(pin);java.util.Objects.requireNonNull(ignored);
        require(bytes!=null && bytes.length<=8_000_000,"JSON_TOO_LARGE");
        require(com.gustler.backend.routegeometry.api.RouteSourceReceipt.sha256(bytes).equals(pin.bytesSha256()),"REFERENCE_HASH_MISMATCH");
        var root=read(bytes,8_000_000);
        require("route-ngii-vector-slice-v1".equals(text(root,"format")),"UNSUPPORTED_REFERENCE_FORMAT");
        require(text(root,"originCatalogSha256").equals(pin.originCatalogSha256()),"REFERENCE_ORIGIN_MISMATCH");
        Map<String,JsonNode> layers=new HashMap<>();
        require(root.path("sourceLayers").isArray() && root.path("sourceLayers").size()<=256,"INVALID_SOURCE_LAYERS");
        for(var layer:root.path("sourceLayers")) {
            require(pin.archiveSha256().contains(text(layer,"archiveSha256")),"UNPINNED_SOURCE_ARCHIVE");
            require(layers.put(text(layer,"id"),layer)==null,"DUPLICATE_SOURCE_LAYER");
        }
        var rows=root.path("links");require(rows.isArray() && rows.size()>0 && rows.size()<=2048,"INVALID_REFERENCE_LINKS");
        Map<String,CatalogFeature> features=new HashMap<>();int total=0;
        for(var row:rows) {
            require("links".equals(text(row,"kind")) && "A2_LINK".equals(text(row,"sourceLayer")),"UNSUPPORTED_NATIVE_FEATURE");
            require(row.path("continuousSinglePart").asBoolean(false) && row.path("parts").size()==1
                && integer(row.path("parts").get(0))==0,"MULTIPART_NATIVE_FEATURE");
            String id=text(row,"id"),namespace=text(row,"namespace"),source=text(row,"sourceId");
            String from=text(row,"fromNodeId"),to=text(row,"toNodeId"),wkt=text(row,"sourceHorizontalCrs");
            var attrs=row.path("attributes");
            require(source.equals(text(attrs,"ID")) && id.equals(namespace+":links:"+source)
                && from.equals(namespace+":nodes:"+text(attrs,"FromNodeID"))
                && to.equals(namespace+":nodes:"+text(attrs,"ToNodeID")),"NATIVE_IDENTITY_MISMATCH");
            Crs crs=crs(text(row,"horizontalCrs"));
            List<NativeXYZ> xyz=xyz(row.path("xyz")),original=xyz(row.path("sourceXYZ"));total+=xyz.size();
            require(total<=250_000 && xyz.size()==original.size(),"NATIVE_VERTEX_BUDGET_OR_FRAME_MISMATCH");
            boolean transformed=row.path("sourceXYTransformApplied").asBoolean(false);
            if(!transformed) require(xyz.equals(original),"UNDECLARED_NATIVE_TRANSFORM");
            List<AccuracyEvidence.Basis> basis=new ArrayList<>();
            var occurrences=row.path("sourceOccurrences");require(occurrences.isArray() && !occurrences.isEmpty() && occurrences.size()<=16,"SOURCE_OCCURRENCES_REQUIRED");
            for(var occurrence:occurrences) {
                String layerId=text(occurrence,"layerId");var layer=layers.get(layerId);
                require(layer!=null && "A2_LINK".equals(text(layer,"layer"))
                    && wkt.equals(text(layer,"sourceHorizontalCrs")) && namespace.equals(text(layer,"namespace")),"SOURCE_FRAME_OR_LAYER_MISMATCH");
                String archive=text(layer,"archiveSha256");
                require(namespace.startsWith("ngii:"+archive+":") && occurrence.path("attributes").equals(attrs),"SOURCE_RECORD_MISMATCH");
                int index=integer(occurrence.path("recordIndex"));require(index>=0,"INVALID_RECORD_INDEX");
                basis.add(new AccuracyEvidence.Basis("NGII_ORIGINAL_ARCHIVE",archive,layerId+":"+index));
            }
            var feature=new AccuracyEvidence.NativeFeature(id,namespace,from,to,AccuracyEvidence.FeatureKind.LANE,
                crs,xyz.stream().map(NativeXYZ::xy).toList(),basis);
            require(NativeReferenceProjection.nativeLength(crs,feature.vertices())>0,"EMPTY_NATIVE_GEOMETRY");
            require(features.put(id,new CatalogFeature(feature,xyz,original,wkt,transformed,text(row,"verticalDatum")))==null,"DUPLICATE_NATIVE_FEATURE");
        }
        return new PinnedNativeCatalog(pin,features);
    }
    @Override public String bundleDigest() { return pin.bytesSha256(); }
    public String originCatalogDigest() { return pin.originCatalogSha256(); }
    @Override public Optional<AccuracyEvidence.NativeFeature> find(String id) { return selected(id).map(CatalogFeature::feature); }
    public Optional<CatalogFeature> selected(String id) { return Optional.ofNullable(features.get(id)); }
    static Crs crs(String value) {
        return switch(value) { case "EPSG:5179" -> Crs.EPSG_5179;case "EPSG:5186" -> Crs.EPSG_5186;
            default -> throw new IllegalArgumentException("UNSUPPORTED_NATIVE_CRS"); };
    }
    static List<NativeXYZ> xyz(JsonNode node) {
        require(node.isArray() && node.size()>=2 && node.size()<=100_000,"INVALID_NATIVE_COORDINATES");
        var result=new ArrayList<NativeXYZ>();
        for(var p:node) { require(p.isArray() && p.size()==3,"NATIVE_XYZ_REQUIRED");result.add(new NativeXYZ(number(p.get(0)),number(p.get(1)),number(p.get(2)))); }
        return List.copyOf(result);
    }
    private static boolean hash(String v) { return v!=null && v.matches("[0-9a-f]{64}"); }
}
