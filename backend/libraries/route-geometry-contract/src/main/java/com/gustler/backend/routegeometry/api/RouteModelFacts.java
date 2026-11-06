package com.gustler.backend.routegeometry.api;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

/** Primary-source model inputs, not accuracy grades or reviewed route truth. Receipt identity is not semantic identity. */
public record RouteModelFacts(String inputDigest, Set<Role> consumedRoles, Map<Role, Fact> routeValues,
        List<VisitFacts> visits, Availability availability, Reason reason, List<BasisRef> basisRefs) {
    public enum Role { CENTER_YN, ROUTE_TYPE_CD, BUS_TYPE_CD }
    public enum FieldState { VALUE, MISSING, NULL, UNKNOWN }
    public enum Availability { RESOLVED, UNRESOLVED }
    public enum Reason { AVAILABLE, NOT_REQUIRED, CURRENT_VERSION_MISMATCH, CURRENT_INPUT_MISMATCH,
        CATALOG_MANIFEST_MISSING, SOURCE_UNAVAILABLE, SOURCE_SCOPE_MISMATCH, SOURCE_SCHEMA_INVALID,
        SOURCE_INPUT_MISMATCH, CONSUMED_FIELD_MISSING, CONSUMED_FIELD_NULL, CONSUMED_FIELD_UNKNOWN }
    /** Codes are opaque provider values. A VALUE code does not assert a documented bus-service classification. */
    public record Fact(FieldState state, String value) {
        public Fact {
            Objects.requireNonNull(state);
            if (state == FieldState.VALUE && (value == null || value.isBlank())
                    || state != FieldState.VALUE && value != null)
                throw new IllegalArgumentException("Invalid model fact value");
        }
        public static Fact unknown() { return new Fact(FieldState.UNKNOWN,null); }
    }
    public record VisitFacts(String stationId, int sequence, String direction, Fact centerYn) {
        public VisitFacts {
            if (stationId == null || stationId.isBlank() || sequence < 1 || !Set.of("UP","DOWN").contains(direction))
                throw new IllegalArgumentException("Invalid model fact visit");
            Objects.requireNonNull(centerYn);
            if(centerYn.state()==FieldState.VALUE && !Set.of("Y","N").contains(centerYn.value()))
                throw new IllegalArgumentException("Unknown center lane code");
        }
    }
    public record BasisRef(String catalogManifestId, String receiptId, String operation,
                           String bodySha256, Instant acquiredAt) {
        public BasisRef {
            if(!sha(catalogManifestId) || !sha(receiptId) || bodySha256 != null && !sha(bodySha256)
                    || !Set.of("ROUTE_INFO","ROUTE_STATIONS").contains(operation))
                throw new IllegalArgumentException("Invalid primary-source basis reference");
            Objects.requireNonNull(acquiredAt);
        }
    }
    public RouteModelFacts {
        if(!sha(inputDigest)) throw new IllegalArgumentException("Invalid source input digest");
        consumedRoles=Set.copyOf(consumedRoles);routeValues=Map.copyOf(routeValues);
        visits=List.copyOf(visits);basisRefs=List.copyOf(basisRefs);
        Objects.requireNonNull(availability);Objects.requireNonNull(reason);
        var routeRoles=EnumSet.noneOf(Role.class);routeRoles.addAll(consumedRoles);routeRoles.remove(Role.CENTER_YN);
        if(!routeValues.keySet().equals(routeRoles)) throw new IllegalArgumentException("Consumed route roles differ");
        if(consumedRoles.contains(Role.CENTER_YN) != !visits.isEmpty())
            throw new IllegalArgumentException("Consumed visit roles differ");
        int prior=0;
        for(var visit:visits) { if(visit.sequence()<=prior) throw new IllegalArgumentException("Visit order differs"); prior=visit.sequence(); }
        for(var value:routeValues.values()) if(value.state()==FieldState.VALUE && !value.value().matches("[0-9]{1,12}"))
            throw new IllegalArgumentException("Route classification must be an opaque numeric source code");
        if(availability==Availability.RESOLVED && (routeValues.values().stream().anyMatch(v->v.state()!=FieldState.VALUE)
                || visits.stream().anyMatch(v->v.centerYn().state()!=FieldState.VALUE)))
            throw new IllegalArgumentException("Unresolved fields cannot be resolved facts");
    }
    public static RouteModelFacts unresolved(RouteDisplayInput input,Set<Role> consumed,Reason reason) {
        return unresolved(input,consumed,reason,List.of());
    }
    public static RouteModelFacts unresolved(RouteDisplayInput input,Set<Role> consumed,Reason reason,List<BasisRef> basisRefs) {
        Map<Role,Fact> route=new EnumMap<>(Role.class);
        for(var role:consumed) if(role!=Role.CENTER_YN) route.put(role,Fact.unknown());
        var visits=consumed.contains(Role.CENTER_YN)?input.visits().stream()
            .map(v->new VisitFacts(v.stationId(),v.sequence(),v.direction(),Fact.unknown())).toList():List.<VisitFacts>of();
        return new RouteModelFacts(input.digest(),consumed,route,visits,Availability.UNRESOLVED,reason,basisRefs);
    }
    public static RouteModelFacts notRequired(RouteDisplayInput input) {
        return new RouteModelFacts(input.digest(),Set.of(),Map.of(),List.of(),Availability.RESOLVED,Reason.NOT_REQUIRED,List.of());
    }
    /** Includes only consumed semantic fields, their states, visit identities and the exact source input digest. */
    public String factsDigest() {
        try {
            var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);
            text(out,"route-model-facts-v1");text(out,inputDigest);
            var roles=consumedRoles.stream().sorted().toList();out.writeInt(roles.size());
            for(var role:roles) {
                text(out,role.name());
                if(role!=Role.CENTER_YN) fact(out,routeValues.get(role));
                else {
                    out.writeInt(visits.size());
                    for(var visit:visits) {
                        text(out,visit.stationId());out.writeInt(visit.sequence());text(out,visit.direction());fact(out,visit.centerYn());
                    }
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch(IOException|NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static void fact(DataOutputStream out,Fact fact) throws IOException {
        text(out,fact.state().name());if(fact.value()!=null) text(out,fact.value());
    }
    private static void text(DataOutputStream out,String value) throws IOException {
        byte[] bytes=value.getBytes(StandardCharsets.UTF_8);out.writeInt(bytes.length);out.write(bytes);
    }
    private static boolean sha(String value) { return value!=null && value.matches("[0-9a-f]{64}"); }
}
