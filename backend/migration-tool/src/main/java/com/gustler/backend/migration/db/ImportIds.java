package com.gustler.backend.migration.db;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

final class ImportIds {

    private ImportIds() {
    }

    static UUID fromManifest(
        String manifestSha256
    ) {
        return UUID.nameUUIDFromBytes(
            ("salmonbus-historical-import\n" + manifestSha256).getBytes(StandardCharsets.UTF_8));
    }
}
