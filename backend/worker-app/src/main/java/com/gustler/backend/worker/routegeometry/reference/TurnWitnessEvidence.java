package com.gustler.backend.worker.routegeometry.reference;

import static com.gustler.backend.worker.routegeometry.reference.ReferenceJson.*;
import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Completes an omitted source-record witness, never infers a turn from graph connectivity. */
final class TurnWitnessEvidence {
    private TurnWitnessEvidence() {}
    static Set<String> inputDigests(JsonNode root) {
        if(!root.has("turnWitnessEvidence"))return Set.of();
        var e=root.path("turnWitnessEvidence");
        require("source-turn-witnesses-v1".equals(text(e,"format")),"UNSUPPORTED_TURN_WITNESS_FORMAT");
        for(String flag:List.of("selectedPathRead","groundTruthRead"))
            require(e.path(flag).isBoolean() && !e.path(flag).booleanValue(),"TURN_WITNESSES_MUST_BE_SOURCE_ONLY");
        require(integer(e.path("modelsExecuted"))==0,"TURN_WITNESSES_MUST_BE_SOURCE_ONLY");
        String source=hash(e,"referenceSourceSha256"),archive=hash(e,"sourceArchiveSha256"),content=hash(e,"contentSha256"),producer=hash(e.path("producer"),"sha256");
        boolean bound=false;
        for(var ref:root.path("referenceSources"))if("OFFICIAL_TURN_BINDINGS".equals(ref.path("role").asString()) && source.equals(ref.path("sha256").asString()))bound=true;
        require(bound && producer.equals(text(root.path("producer"),"turnWitnessExtractorSha256")),"TURN_WITNESS_SOURCE_PIN_MISMATCH");
        require("20261014".equals(text(e,"referenceEpoch")),"UNSUPPORTED_TURN_REFERENCE_EPOCH");
        var unsigned=(ObjectNode)e.deepCopy();unsigned.remove("contentSha256");
        require(content.equals(CanonicalSourceObservations.canonicalSha(unsigned)),"TURN_WITNESS_CONTENT_HASH_MISMATCH");
        Map<Integer,JsonNode> expected=new HashMap<>();
        require(root.path("turnRows").isArray(),"TURN_ROWS_REQUIRED");
        for(var row:root.path("turnRows"))require(expected.put(integer(row.path("sourceRecordIndex")),row)==null,"DUPLICATE_TURN_SOURCE_INDEX");
        var rows=e.path("sourceRows");require(rows.isArray() && rows.size()==expected.size(),"INCOMPLETE_TURN_WITNESS_SCOPE");
        Set<Integer> seen=new HashSet<>();
        for(var row:rows) {
            int index=integer(row.path("sourceRecordIndex"));var old=expected.get(index);
            require(index>=0 && seen.add(index) && old!=null && row.path("turn").equals(old.path("turn"))
                && text(row,"bindingStatus").equals(text(old,"bindingStatus")),"TURN_WITNESS_RECORD_BINDING_MISMATCH");
            require(row.path("startLinks").isArray() && row.path("endLinks").isArray() && row.path("nodes").isArray(),"TURN_SOURCE_WITNESSES_REQUIRED");
            for(String side:List.of("startLinks","endLinks"))for(var witness:row.path(side)) {
                require(integer(witness.path("sourceRecordIndex"))>=0,"LINK_WITNESS_SOURCE_INDEX_REQUIRED");
                text(witness,"LINK_ID");text(witness,"F_NODE");text(witness,"T_NODE");
            }
            for(var witness:row.path("nodes")) {require(integer(witness.path("sourceRecordIndex"))>=0,"NODE_WITNESS_SOURCE_INDEX_REQUIRED");text(witness,"NODE_ID");text(witness,"TURN_P");}
            for(var witness:row.path("startLinks"))require(text(witness,"LINK_ID").equals(text(row.path("turn"),"ST_LINK")),"TURN_START_WITNESS_ID_MISMATCH");
            for(var witness:row.path("endLinks"))require(text(witness,"LINK_ID").equals(text(row.path("turn"),"ED_LINK")),"TURN_END_WITNESS_ID_MISMATCH");
            for(var witness:row.path("nodes"))require(text(witness,"NODE_ID").equals(text(row.path("turn"),"NODE_ID")),"TURN_NODE_WITNESS_ID_MISMATCH");
        }
        return Set.of(content,archive,producer);
    }
    private static String hash(JsonNode node,String field){String value=text(node,field);require(value.matches("[0-9a-f]{64}"),"TURN_WITNESS_HASH_REQUIRED");return value;}
}
