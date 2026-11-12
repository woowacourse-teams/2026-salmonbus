package com.gustler.backend.forecasting.infrastructure.jdbc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import tools.jackson.databind.json.JsonMapper;

final class SettlementProjectionCodec {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final int MAX_BYTES = 64 * 1024 * 1024;

    private SettlementProjectionCodec() {
    }

    static byte[] encode(Object value) {
        byte[] raw = JSON.writeValueAsBytes(value);
        if (raw.length > MAX_BYTES) {
            throw new IllegalArgumentException("Settlement projection exceeds size limit");
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
                gzip.write(raw);
            }
            return buffer.toByteArray();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode settlement projection", failure);
        }
    }

    static <T> T decode(byte[] encoded, Class<T> type) {
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(encoded))) {
            byte[] raw = gzip.readNBytes(MAX_BYTES + 1);
            if (raw.length > MAX_BYTES) {
                throw new IllegalArgumentException("Settlement projection exceeds size limit");
            }
            return JSON.readValue(raw, type);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode settlement projection", failure);
        }
    }
}
