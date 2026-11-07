package com.gustler.backend.worker.routegeometry.reference;

import java.util.Set;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded, duplicate-key-rejecting packets. No JSON value is interpreted as a resource path or URL. */
final class ReferenceJson {
    private static final ObjectMapper JSON=new ObjectMapper(JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
            .maxStringLength(8192).maxNumberLength(64).build()).build())
        .rebuild().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private ReferenceJson() {}
    static JsonNode read(byte[] bytes,int maxBytes) { return read(bytes,maxBytes,8192); }
    static JsonNode read(byte[] bytes,int maxBytes,int maxStringLength) {
        require(bytes!=null && bytes.length>0,"EMPTY_JSON");
        require(bytes.length<=maxBytes,"JSON_TOO_LARGE");
        try {
            ObjectMapper parser=maxStringLength==8192?JSON:new ObjectMapper(JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(maxStringLength).maxNumberLength(64).build()).build())
                .rebuild().enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
            JsonNode root=parser.readTree(bytes);
            require(root!=null && root.isObject(),"JSON_OBJECT_REQUIRED");
            return root;
        } catch(IllegalArgumentException invalid) { throw invalid; }
        catch(RuntimeException invalid) { throw new IllegalArgumentException("INVALID_JSON"); }
    }
    static void require(boolean ok,String code) { if(!ok) throw new IllegalArgumentException(code); }
    static String text(JsonNode node,String field) {
        JsonNode v=node.path(field);require(v.isString() && !v.asString().isBlank(),"TEXT_REQUIRED");return v.asString();
    }
    static double number(JsonNode node) {
        require(node.isNumber() && Double.isFinite(node.doubleValue()),"FINITE_NUMBER_REQUIRED");return node.doubleValue();
    }
    static int integer(JsonNode node) {
        require(node.isIntegralNumber() && node.canConvertToInt(),"INTEGER_REQUIRED");return node.intValue();
    }
    static void fields(JsonNode node,Set<String> allowed) {
        require(node.isObject(),"OBJECT_REQUIRED");
        for(var p:node.properties()) require(allowed.contains(p.getKey()),"UNKNOWN_CANDIDATE_FIELD");
    }
}
