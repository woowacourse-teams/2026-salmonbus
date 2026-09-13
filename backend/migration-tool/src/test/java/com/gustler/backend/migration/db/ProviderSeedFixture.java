package com.gustler.backend.migration.db;

import com.gustler.backend.migration.SecureFiles;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Map;

final class ProviderSeedFixture {

    static final String COMPRESSED_SHA256 =
        "0972303007e55b729c201dd760626e6cb986c554f80325b53a2e66e806638d94";
    static final String RECEIPT_SHA256 =
        "bba59070064e09a60be9dae4c3818d01db14d23e44e3cd1a4842768fdc777cb9";
    static final String CANONICAL_SHA256 =
        "4cd3985cc46d8a7032eae078fbec66c4afe9a294a19823689aa72b0a8d9cea0a";
    static final String ROWS_SHA256 =
        "f0530062ea3cd874b5f0ef360c7ae430e3d009f0bfcf81bbeba1387b4364e6d8";
    static final String PRIMARY_KEY_SHA256 =
        "49cd771e06567b1050095f67f45aa9ff5d1efe9c62a8a1108798860e4417c56b";

    private ProviderSeedFixture() {
    }

    static Files writeTo(
        Path directory
    ) throws Exception {
        Path seed = directory.resolve("provider-seed.json.gz");
        Path receipt = directory.resolve("provider-receipt.json");
        SecureFiles.writeNew(seed, decode("/seed/provider-fixture.json.gz.b64"));
        SecureFiles.writeNew(receipt, decode("/seed/provider-fixture-receipt.json.b64"));
        return new Files(seed, receipt, new AggregateSeedReader(authority()));
    }

    private static AggregateSeedReader.Authority authority() {
        return new AggregateSeedReader.Authority(
            COMPRESSED_SHA256,
            RECEIPT_SHA256,
            CANONICAL_SHA256,
            ROWS_SHA256,
            PRIMARY_KEY_SHA256,
            3_001,
            21_753,
            100,
            expectedAggregates(),
            false);
    }

    static SeedRows.AggregateSet expectedAggregates() {
        return new SeedRows.AggregateSet(
            Map.of(
                "1650", totals(50, 1_378, "571.00633889919610168", "-350.0", "61232.0"),
                "3330", totals(50, 1_062, "255.688910599204847864", "-359.0", "49764.0")),
            totals(100, 2_440, "826.695249498400949544", "-709.0", "110996.0"));
    }

    private static SeedRows.AggregateTotals totals(
        int rows,
        long samples,
        String fill,
        String net,
        String capacity
    ) {
        return new SeedRows.AggregateTotals(
            rows, samples, new BigDecimal(fill), new BigDecimal(net), new BigDecimal(capacity));
    }

    private static byte[] decode(
        String resource
    ) throws Exception {
        try (java.io.InputStream input = ProviderSeedFixture.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException(resource);
            }
            String encoded = new String(input.readAllBytes(), StandardCharsets.US_ASCII);
            return Base64.getMimeDecoder().decode(encoded);
        }
    }

    record Files(
        Path seed,
        Path receipt,
        AggregateSeedReader reader
    ) {
    }
}
