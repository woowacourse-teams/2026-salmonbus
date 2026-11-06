package com.gustler.backend.routegeometry.api;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

/** Credential-free acquisition metadata. SUCCESS retains accepted original response bytes;
 * DERIVED is explicitly internal normalized data and never counts as an upstream response. */
public record RouteSourceReceipt(String sourceId, String operation, String scope, Status status,
        Integer httpStatus, Instant acquiredAt, String providerVersion, CoordinateSystem coordinateSystem,
        byte[] body) {
    public enum Status { SUCCESS, DERIVED, HTTP_ERROR, TRANSPORT_FAILURE, INVALID_RESPONSE, SENSITIVE_RESPONSE,
        NO_QUOTA, NOT_CONFIGURED, UNAVAILABLE, STALE, UNRECORDED_LEGACY }
    public enum CoordinateSystem { WGS84_ASSUMED, WEB_MERCATOR_ASSUMED, DEGREES_MINUTES_ASSUMED, UNKNOWN }
    public RouteSourceReceipt {
        if (sourceId == null || !sourceId.matches("[A-Z0-9_]{1,64}")
                || operation == null || !operation.matches("[A-Z0-9_]{1,64}")
                || scope == null || !(scope.matches("[0-9]{1,20}|PAGE:[0-9]{1,3}|SHARED|UNSPECIFIED")))
            throw new IllegalArgumentException("Invalid acquisition metadata");
        Objects.requireNonNull(status);Objects.requireNonNull(acquiredAt);Objects.requireNonNull(coordinateSystem);
        if (httpStatus != null && (httpStatus < 100 || httpStatus > 599))
            throw new IllegalArgumentException("Invalid HTTP status");
        if (providerVersion == null || !providerVersion.matches("[A-Za-z0-9_.:-]{1,100}"))
            throw new IllegalArgumentException("Invalid provider version");
        boolean payloadAllowed = status == Status.SUCCESS || status == Status.DERIVED;
        if (!payloadAllowed && body != null || payloadAllowed && (body == null || body.length == 0)
                || status == Status.DERIVED && httpStatus != null)
            throw new IllegalArgumentException("Only accepted original or explicitly derived payloads retain bytes");
        body = body == null ? null : body.clone();
    }
    @Override public byte[] body() { return body == null ? null : body.clone(); }
    public String bodySha256() { return body == null ? null : sha256(body); }
    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    @Override public String toString() {
        return "RouteSourceReceipt[sourceId="+sourceId+", operation="+operation+", scope="+scope
            +", status="+status+", httpStatus="+httpStatus+", acquiredAt="+acquiredAt
            +", providerVersion="+providerVersion+", coordinateSystem="+coordinateSystem+", bodySha256="+bodySha256()+"]";
    }
    @Override public boolean equals(Object other) {
        return other instanceof RouteSourceReceipt r && sourceId.equals(r.sourceId) && operation.equals(r.operation)
            && scope.equals(r.scope) && status==r.status && Objects.equals(httpStatus,r.httpStatus)
            && acquiredAt.equals(r.acquiredAt) && providerVersion.equals(r.providerVersion)
            && coordinateSystem==r.coordinateSystem && Arrays.equals(body,r.body);
    }
    @Override public int hashCode() {
        return 31*Objects.hash(sourceId,operation,scope,status,httpStatus,acquiredAt,providerVersion,coordinateSystem)+Arrays.hashCode(body);
    }
}
