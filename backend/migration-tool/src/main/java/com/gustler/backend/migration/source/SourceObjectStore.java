package com.gustler.backend.migration.source;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public interface SourceObjectStore extends AutoCloseable {

    String callerAccountId();

    List<ObjectInfo> list(
        String bucket,
        String prefix,
        Instant cutoffAt
    );

    LoadedObject get(
        String bucket,
        ObjectInfo expected
    );

    @Override
    void close();

    record ObjectInfo(
        Kind kind,
        String key,
        String etag,
        long size,
        Instant lastModified
    ) {
    }

    enum Kind {
        RECORD,
        RAW,
    }

    record LoadedObject(
        ObjectInfo info,
        byte[] body,
        Map<String, String> metadata,
        boolean kmsEncrypted,
        String kmsKeyDigest
    ) {

        @Override
        public String toString() {
            return "LoadedObject[info=***, body=***, metadata=***]";
        }
    }
}
