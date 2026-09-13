package com.gustler.backend.collector;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public record RouteContentDigest(
    String value
) {

    private static final String ALGORITHM = "SHA-256";
    private static final String NO_TURN_SEQUENCE = "단방향";

    public static RouteContentDigest of(
        RouteStops routeStops
    ) {
        MessageDigest digest = newDigest();
        updateWithLengthPrefixed(digest, turnSequenceOf(routeStops));
        routeStops.stops().forEach(stop -> updateWithIdentityOf(digest, stop));
        return new RouteContentDigest(HexFormat.of().formatHex(digest.digest()));
    }

    private static String turnSequenceOf(
        RouteStops routeStops
    ) {
        if (routeStops.turnSequence() == null) {
            return NO_TURN_SEQUENCE;
        }
        return String.valueOf(routeStops.turnSequence());
    }

    private static void updateWithIdentityOf(
        MessageDigest digest,
        RouteStop stop
    ) {
        updateWithLengthPrefixed(digest, String.valueOf(stop.stopOrder()));
        updateWithLengthPrefixed(digest, stop.stopId());
        updateWithLengthPrefixed(digest, stop.name());
    }

    private static void updateWithLengthPrefixed(
        MessageDigest digest,
        String value
    ) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance(ALGORITHM);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(ALGORITHM + " 을 쓸 수 없다", e);
        }
    }
}
