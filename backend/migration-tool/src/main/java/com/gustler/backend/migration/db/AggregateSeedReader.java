package com.gustler.backend.migration.db;

import com.gustler.backend.migration.MigrationException;
import com.gustler.backend.migration.SecureFiles;
import com.gustler.backend.migration.Sha256;
import com.gustler.backend.processor.StopDemandStatisticsJob;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPInputStream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

final class AggregateSeedReader {

    static final String SOURCE_SEED_SHA256 =
        "7f3be7dc2c668d1ed4b8665c341c2cda24968d3fa2e6ed2755261a20c3826ec4";
    static final String SOURCE_RECEIPT_SHA256 =
        "9985723799bc859cc22b4cda613a50922ff2191b15c7c155d8f1d7e177db8c26";
    static final String SOURCE_CANONICAL_SHA256 =
        "adcf0d04a4b67dc40bed6f3640f0f78697e9215bf794ff2b262f0ba0ec0a5ada";
    static final String SOURCE_ROWS_SHA256 =
        "15c221a149f241555e32a4133ed1f31b3d9eaeedc3062c5a454d6b9505489013";
    static final String SOURCE_PRIMARY_KEY_SHA256 =
        "30b2ffc79cc64decc7936efd0a03e51602e5e12424e0e8cbdb7ebf7d98a6a73d";
    static final String CALCULATION_VERSION = StopDemandStatisticsJob.CURRENT_CALCULATION_VERSION;

    private static final String SCHEMA = "stop-demand-hourly-aggregate-seed-v1";
    private static final String RECEIPT_SCHEMA = "stop-demand-hourly-aggregate-seed-receipt-v1";
    private static final String CLASSIFICATION = "PRIVACY_SAFE_AGGREGATE_BACKFILL_SEED_DRY_RUN_ONLY";
    private static final String ROUTE_REFERENCE_VERSION = "gbis-2026-08-19";
    private static final String ROUTE_REFERENCE_DIGEST =
        "50568d9a10b567ea0b650cd79ceed39a86947648e303e0d8fd1093840bb54c5e";
    private static final Set<String> ROOT_FIELDS = Set.of(
        "backfillPolicyId", "classification", "featureContractVersion", "generatedAtGuardSeconds",
        "lastBackfilledGenerationUtc", "privacy", "rdsObservationDeltaContract",
        "requiresRdsObservationDeltaBeforeFormalCutover", "routeReference", "rows", "schemaVersion",
        "scope", "settlementAvailabilityGuardSeconds", "sourceAuthorityThroughExclusiveUtcByRoute");
    private static final Set<String> ROW_FIELDS = Set.of(
        "arrivalDateKst", "arrivalHourStartUtc", "capacityTotal", "fillRateTotal", "modelRoute",
        "netBoardingTotal", "sampleCount", "stopOrder");
    private static final Set<String> PRIVACY_FIELDS = Set.of(
        "containsPlateValues", "containsRawRows", "containsVehicleHmacs", "containsVehicleIdentifiers");
    private static final Set<String> ROUTE_REFERENCE_FIELDS = Set.of("digest", "version");
    private static final Set<String> ROUTE_FIELDS = Set.of("1650", "3330");
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final int MAX_UNCOMPRESSED_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final ObjectMapper DECIMAL_READER = JsonMapper.builder()
        .enable(JsonNodeFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .disable(JsonNodeFeature.STRIP_TRAILING_BIGDECIMAL_ZEROES)
        .build();

    private final Authority authority;

    AggregateSeedReader() {
        this(productionAuthority());
    }

    AggregateSeedReader(
        Authority authority
    ) {
        this.authority = authority;
    }

    AggregateSeedPayload read(
        Path seedPath,
        Path receiptPath
    ) {
        SecureFiles.requirePrivateRegularFile(seedPath);
        SecureFiles.requirePrivateRegularFile(receiptPath);
        requireDigest(seedPath, authority.compressedSha256(), "SEED_PAYLOAD_SHA256_MISMATCH");
        requireDigest(receiptPath, authority.receiptSha256(), "SEED_RECEIPT_SHA256_MISMATCH");
        byte[] compressed = readBytes(
            seedPath, authority.compressedBytes(), authority.compressedBytes(), "SEED_PAYLOAD_READ_FAILED");
        byte[] uncompressed = decompress(compressed, authority.uncompressedBytes());
        require(Sha256.of(uncompressed).equals(authority.canonicalSha256()),
            "SEED_CANONICAL_SHA256_MISMATCH");
        JsonNode document = parseDecimal(uncompressed, "SEED_PAYLOAD_JSON_INVALID");
        JsonNode receipt = parseDecimal(
            readBytes(receiptPath, -1, MAX_RECEIPT_BYTES, "SEED_RECEIPT_READ_FAILED"),
            "SEED_RECEIPT_JSON_INVALID");
        validateEnvelope(document, receipt);
        Map<String, Instant> cutoffs = cutoffs(document.path("sourceAuthorityThroughExclusiveUtcByRoute"));
        ArrayList<SeedHourlyRow> rows = readRows(document.path("rows"));
        require(rows.size() == authority.rowCount(), "SEED_ROW_COUNT_MISMATCH");
        String rowsSha256 = SeedRows.canonicalRowsSha256(rows);
        String primaryKeySha256 = SeedRows.primaryKeySha256(rows);
        SeedRows.AggregateSet aggregates = SeedRows.aggregateSet(rows);
        require(authority.rowsSha256().equals(rowsSha256), "SEED_ROWS_SHA256_MISMATCH");
        require(authority.primaryKeySha256().equals(primaryKeySha256), "SEED_PRIMARY_KEY_SHA256_MISMATCH");
        require(aggregateSetsEqual(authority.aggregates(), aggregates), "SEED_AGGREGATE_TOTAL_MISMATCH");
        return new AggregateSeedPayload(
            authority.compressedSha256(), authority.receiptSha256(), authority.canonicalSha256(),
            rowsSha256, primaryKeySha256, CALCULATION_VERSION, 60, 60, cutoffs, rows, aggregates);
    }

    private void validateEnvelope(
        JsonNode document,
        JsonNode receipt
    ) {
        require(fields(document).equals(ROOT_FIELDS), "SEED_ROOT_FIELDS_INVALID");
        require(SCHEMA.equals(text(document, "schemaVersion")), "SEED_SCHEMA_INVALID");
        require(CLASSIFICATION.equals(text(document, "classification")), "SEED_CLASSIFICATION_INVALID");
        require(CALCULATION_VERSION.equals(text(document, "featureContractVersion")),
            "SEED_CALCULATION_VERSION_INVALID");
        require("s3-lag60-settle60-kst6h-chronological-v1".equals(text(document, "backfillPolicyId")),
            "SEED_BACKFILL_POLICY_INVALID");
        require("SOURCE_SIDE_THROUGH_TARGET_AUTHORITY".equals(text(document, "scope")),
            "SEED_SCOPE_INVALID");
        require(document.path("generatedAtGuardSeconds").isIntegralNumber()
            && document.path("generatedAtGuardSeconds").intValue() == 60
            && document.path("settlementAvailabilityGuardSeconds").isIntegralNumber()
            && document.path("settlementAvailabilityGuardSeconds").intValue() == 60,
            "SEED_GUARD_INVALID");
        require(document.path("requiresRdsObservationDeltaBeforeFormalCutover").isBoolean()
            && document.path("requiresRdsObservationDeltaBeforeFormalCutover").booleanValue(),
            "SEED_RDS_DELTA_REQUIREMENT_MISSING");
        require("rds-observation-delta-contract.json".equals(
            text(document, "rdsObservationDeltaContract")), "SEED_RDS_DELTA_CONTRACT_INVALID");
        Instant.parse(text(document, "lastBackfilledGenerationUtc"));
        JsonNode routeReference = document.path("routeReference");
        require(fields(routeReference).equals(ROUTE_REFERENCE_FIELDS)
            && ROUTE_REFERENCE_VERSION.equals(text(routeReference, "version"))
            && ROUTE_REFERENCE_DIGEST.equals(text(routeReference, "digest")),
            "SEED_ROUTE_REFERENCE_INVALID");
        JsonNode privacy = document.path("privacy");
        require(fields(privacy).equals(PRIVACY_FIELDS)
            && !privacy.path("containsVehicleIdentifiers").booleanValue()
            && !privacy.path("containsVehicleHmacs").booleanValue()
            && !privacy.path("containsPlateValues").booleanValue()
            && !privacy.path("containsRawRows").booleanValue(), "SEED_PRIVACY_FLAGS_INVALID");

        if (authority.productionReceipt()) {
            require(RECEIPT_SCHEMA.equals(text(receipt, "schemaVersion")), "SEED_RECEIPT_SCHEMA_INVALID");
            require(authority.compressedSha256().equals(text(receipt, "compressedSha256"))
                && authority.canonicalSha256().equals(text(receipt, "canonicalJsonSha256")),
                "SEED_RECEIPT_DIGEST_MISMATCH");
            require(receipt.path("compressedBytes").longValue() == authority.compressedBytes()
                && receipt.path("uncompressedBytes").longValue() == authority.uncompressedBytes()
                && receipt.path("rowCount").intValue() == authority.rowCount()
                && !receipt.path("databaseWritePerformed").booleanValue()
                && receipt.path("requiresRdsObservationDeltaBeforeFormalCutover").booleanValue(),
                "SEED_RECEIPT_AUTHORITY_MISMATCH");
        }
    }

    private static Map<String, Instant> cutoffs(
        JsonNode value
    ) {
        require(fields(value).equals(ROUTE_FIELDS), "SEED_SOURCE_CUTOFF_FIELDS_INVALID");
        Map<String, Instant> cutoffs = Map.of(
            "3330", Instant.parse(text(value, "3330")),
            "1650", Instant.parse(text(value, "1650")));
        require(cutoffs.equals(Map.of(
            "3330", Instant.parse("2026-09-02T10:27:52.390820Z"),
            "1650", Instant.parse("2026-09-02T12:49:33.041299Z"))),
            "SEED_SOURCE_CUTOFF_MISMATCH");
        return cutoffs;
    }

    private static ArrayList<SeedHourlyRow> readRows(
        JsonNode values
    ) {
        require(values.isArray(), "SEED_ROWS_INVALID");
        ArrayList<SeedHourlyRow> rows = new ArrayList<>(values.size());
        Set<RowKey> seen = new HashSet<>();
        RowKey previous = null;
        for (JsonNode value : values) {
            require(fields(value).equals(ROW_FIELDS), "SEED_ROW_FIELDS_INVALID");
            String modelRoute = text(value, "modelRoute");
            require(value.path("stopOrder").isIntegralNumber()
                && value.path("sampleCount").isIntegralNumber(), "SEED_ROW_TYPE_INVALID");
            int stopOrder = value.path("stopOrder").intValue();
            Instant hour = Instant.parse(text(value, "arrivalHourStartUtc"));
            LocalDate date = LocalDate.parse(text(value, "arrivalDateKst"));
            BigDecimal fill = decimal(value, "fillRateTotal");
            BigDecimal net = decimal(value, "netBoardingTotal");
            BigDecimal capacity = decimal(value, "capacityTotal");
            int samples = value.path("sampleCount").intValue();
            int maximumStop = "1650".equals(modelRoute) ? 89 : 85;
            require(ROUTE_FIELDS.contains(modelRoute)
                && stopOrder >= 1 && stopOrder <= maximumStop
                && hour.equals(hour.truncatedTo(ChronoUnit.HOURS))
                && date.equals(hour.atZone(KST).toLocalDate())
                && samples > 0 && capacity.signum() > 0
                && fill.signum() >= 0 && fill.compareTo(BigDecimal.valueOf(samples)) <= 0
                && net.abs().compareTo(capacity) <= 0, "SEED_ROW_INVALID");
            RowKey key = new RowKey(modelRoute, stopOrder, hour);
            require(seen.add(key), "SEED_ROW_DUPLICATE");
            require(previous == null || previous.compareTo(key) < 0, "SEED_ROW_ORDER_INVALID");
            previous = key;
            rows.add(new SeedHourlyRow(modelRoute, stopOrder, date, hour, fill, net, capacity, samples));
        }
        return rows;
    }

    private static boolean aggregateSetsEqual(
        SeedRows.AggregateSet expected,
        SeedRows.AggregateSet actual
    ) {
        return expected.global().numericallyEquals(actual.global())
            && expected.routes().get("1650").numericallyEquals(actual.routes().get("1650"))
            && expected.routes().get("3330").numericallyEquals(actual.routes().get("3330"));
    }

    private static JsonNode parseDecimal(
        byte[] bytes,
        String code
    ) {
        try {
            return DECIMAL_READER.readTree(bytes);
        } catch (RuntimeException e) {
            throw new MigrationException(code, e);
        }
    }

    private static BigDecimal decimal(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isNumber()) {
            throw new MigrationException("SEED_ROW_TYPE_INVALID");
        }
        return value.decimalValue();
    }

    private static Set<String> fields(
        JsonNode node
    ) {
        LinkedHashSet<String> names = new LinkedHashSet<>();
        names.addAll(node.propertyNames());
        return Set.copyOf(names);
    }

    private static Authority productionAuthority() {
        return new Authority(
            SOURCE_SEED_SHA256,
            SOURCE_RECEIPT_SHA256,
            SOURCE_CANONICAL_SHA256,
            SOURCE_ROWS_SHA256,
            SOURCE_PRIMARY_KEY_SHA256,
            723_046,
            9_450_157,
            45_224,
            new SeedRows.AggregateSet(
                Map.of(
                    "1650", totals(20_390, 509_435, "134018.996217274785257864", "-3567.0", "22641833.0"),
                    "3330", totals(24_834, 521_792, "123564.766238173588115856", "-119362.0", "25141232.0")),
                totals(45_224, 1_031_227, "257583.762455448373373720", "-122929.0", "47783065.0")),
            true);
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

    private static byte[] decompress(
        byte[] compressed,
        long expectedBytes
    ) {
        try (InputStream input = new GZIPInputStream(new java.io.ByteArrayInputStream(compressed));
            ByteArrayOutputStream output = new ByteArrayOutputStream((int) expectedBytes)) {
            input.transferTo(new BoundedOutputStream(output, MAX_UNCOMPRESSED_BYTES));
            byte[] bytes = output.toByteArray();
            require(bytes.length == expectedBytes, "SEED_UNCOMPRESSED_SIZE_MISMATCH");
            return bytes;
        } catch (IOException e) {
            throw new MigrationException("SEED_DECOMPRESSION_FAILED", e);
        }
    }

    private static byte[] readBytes(
        Path path,
        long expectedBytes,
        long maximumBytes,
        String code
    ) {
        try {
            long size = Files.size(path);
            if (expectedBytes >= 0 && size != expectedBytes) {
                throw new MigrationException("SEED_FILE_SIZE_MISMATCH");
            }
            if (size > maximumBytes) {
                throw new MigrationException("SEED_FILE_SIZE_LIMIT_EXCEEDED");
            }
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new MigrationException(code, e);
        }
    }

    private static void requireDigest(
        Path path,
        String expected,
        String code
    ) {
        require(expected.equals(Sha256.of(path)), code);
    }

    private static String text(
        JsonNode node,
        String field
    ) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString() || value.stringValue().isBlank()) {
            throw new MigrationException("SEED_FIELD_INVALID");
        }
        return value.stringValue();
    }

    private static void require(
        boolean condition,
        String code
    ) {
        if (!condition) {
            throw new MigrationException(code);
        }
    }

    record Authority(
        String compressedSha256,
        String receiptSha256,
        String canonicalSha256,
        String rowsSha256,
        String primaryKeySha256,
        long compressedBytes,
        long uncompressedBytes,
        int rowCount,
        SeedRows.AggregateSet aggregates,
        boolean productionReceipt
    ) {
    }

    private record RowKey(
        String modelRoute,
        int stopOrder,
        Instant hour
    ) implements Comparable<RowKey> {

        @Override
        public int compareTo(
            RowKey other
        ) {
            int byRoute = modelRoute.compareTo(other.modelRoute);
            if (byRoute != 0) {
                return byRoute;
            }
            int byHour = hour.compareTo(other.hour);
            return byHour == 0 ? Integer.compare(stopOrder, other.stopOrder) : byHour;
        }
    }

    private static final class BoundedOutputStream extends java.io.OutputStream {

        private final ByteArrayOutputStream delegate;
        private final int maximumBytes;
        private int written;

        private BoundedOutputStream(
            ByteArrayOutputStream delegate,
            int maximumBytes
        ) {
            this.delegate = delegate;
            this.maximumBytes = maximumBytes;
        }

        @Override
        public void write(int value) {
            requireCapacity(1);
            delegate.write(value);
            written++;
        }

        @Override
        public void write(
            byte[] values,
            int offset,
            int length
        ) {
            requireCapacity(length);
            delegate.write(values, offset, length);
            written += length;
        }

        private void requireCapacity(
            int length
        ) {
            if (written + length > maximumBytes) {
                throw new MigrationException("SEED_UNCOMPRESSED_SIZE_LIMIT_EXCEEDED");
            }
        }
    }
}
