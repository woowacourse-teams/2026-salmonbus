package com.gustler.backend.migration;

import com.gustler.backend.migration.archive.ArchiveVerifier;
import com.gustler.backend.migration.db.ArchiveImportValidator;
import com.gustler.backend.migration.db.ArchiveMerger;
import com.gustler.backend.migration.db.ArchiveStager;
import com.gustler.backend.migration.db.AggregateSeedCutover;
import com.gustler.backend.migration.db.DatabasePreflight;
import com.gustler.backend.migration.db.HistoricalSchema;
import com.gustler.backend.migration.db.ImportReconciler;
import com.gustler.backend.migration.db.ImportRollback;
import com.gustler.backend.migration.db.ImportSettings;
import com.gustler.backend.migration.db.SeedCutoverVerifier;
import com.gustler.backend.migration.db.TemporaryReleaseMaintenance;
import com.gustler.backend.migration.source.AwsSourceObjectStore;
import com.gustler.backend.migration.source.SourceArchiveExporter;
import com.gustler.backend.migration.source.SourceInventory;
import com.gustler.backend.migration.source.SourceInventoryBuilder;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MigrationCli {

    private MigrationCli() {
    }

    public static void main(String[] rawArguments) {
        int exit;
        try {
            Object result = run(CliArguments.parse(rawArguments));
            System.out.println(CanonicalJson.stringOf(Map.of("status", "succeeded", "result", result)));
            exit = 0;
        } catch (MigrationException e) {
            System.err.println(CanonicalJson.stringOf(Map.of("status", "failed", "code", e.code())));
            exit = 1;
        } catch (RuntimeException e) {
            System.err.println(CanonicalJson.stringOf(
                Map.of("status", "failed", "code", "UNEXPECTED_SANITIZED_FAILURE")));
            exit = 1;
        }
        System.exit(exit);
    }

    static Object run(
        CliArguments arguments
    ) {
        return switch (arguments.command()) {
            case "inventory" -> inventory(arguments);
            case "archive-build" -> archiveBuild(arguments);
            case "archive-verify" -> archiveVerify(arguments.requiredPath("archive"));
            case "schema" -> schema(arguments);
            case "preflight" -> preflight(arguments, false);
            case "stage" -> stage(arguments);
            case "validate" -> validate(arguments);
            case "merge" -> merge(arguments);
            case "reconcile" -> reconcile(arguments);
            case "rollback" -> rollback(arguments);
            case "temp-pause" -> tempPause(arguments);
            case "temp-freeze" -> tempFreeze(arguments);
            case "temp-cleanup" -> tempCleanup(arguments);
            case "temp-unpause" -> tempUnpause(arguments);
            case "seed-dry-run" -> seedDryRun(arguments);
            case "seed-apply" -> seedApply(arguments);
            case "seed-rollback" -> seedRollback(arguments);
            case "seed-verify" -> seedVerify(arguments);
            default -> throw new MigrationException("UNKNOWN_COMMAND");
        };
    }

    private static Object inventory(
        CliArguments arguments
    ) {
        Configuration configuration = Configuration.load(arguments.requiredPath("config"));
        approval(arguments, ApprovalGate.SOURCE_EXPORT, null, null);
        try (AwsSourceObjectStore source = new AwsSourceObjectStore(configuration.required("source.region"))) {
            SourceInventory inventory = new SourceInventoryBuilder(source).build(configuration);
            inventory.writeTo(arguments.requiredPath("output"));
            return Map.of(
                "inventorySha256", inventory.inventorySha256(),
                "recordObjects", inventory.count(com.gustler.backend.migration.source.SourceObjectStore.Kind.RECORD),
                "rawObjects", inventory.count(com.gustler.backend.migration.source.SourceObjectStore.Kind.RAW));
        }
    }

    private static Object archiveBuild(
        CliArguments arguments
    ) {
        Configuration configuration = Configuration.load(arguments.requiredPath("config"));
        approval(arguments, ApprovalGate.SOURCE_EXPORT, null, null);
        try (AwsSourceObjectStore source = new AwsSourceObjectStore(configuration.required("source.region"))) {
            return SourceArchiveExporter.build(
                configuration, source, arguments.requiredPath("inventory"));
        }
    }

    private static Object archiveVerify(
        Path archive
    ) {
        ArchiveVerifier.Verification verification = new ArchiveVerifier().verify(archive);
        return Map.of(
            "manifestSha256", verification.manifestSha256(),
            "batches", verification.batchCount(),
            "observations", verification.observationCount(),
            "complete", verification.manifest().complete());
    }

    private static Object schema(
        CliArguments arguments
    ) {
        Configuration configuration = Configuration.load(arguments.requiredPath("config"));
        ImportSettings settings = ImportSettings.load(configuration);
        ArchiveVerifier.Verification archive = new ArchiveVerifier().verify(settings.archiveDirectory());
        new DatabasePreflight().check(settings, archive, false);
        requireWriteApproval(arguments, settings, ApprovalGate.ACADEMY_SCHEMA, null);
        return Map.of("migrationsExecuted", new HistoricalSchema().migrate(
            settings.database(), settings.lockTimeoutMillis(), settings.statementTimeoutSeconds()).migrationsExecuted);
    }

    private static Object preflight(
        CliArguments arguments,
        boolean writeRequired
    ) {
        ImportSettings settings = ImportSettings.load(Configuration.load(arguments.requiredPath("config")));
        ArchiveVerifier.Verification archive = new ArchiveVerifier().verify(settings.archiveDirectory());
        if (writeRequired) {
            new HistoricalSchema().requireCurrent(settings.database());
        }
        return new DatabasePreflight().check(settings, archive, writeRequired);
    }

    private static Object stage(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        ArchiveVerifier.Verification archive = verifiedForWrite(arguments, settings);
        return new ArchiveStager().stage(settings, archive);
    }

    private static Object validate(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        ArchiveVerifier.Verification archive = verifiedForWrite(arguments, settings);
        return new ArchiveImportValidator().validate(settings, archive);
    }

    private static Object merge(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        ArchiveVerifier.Verification archive = verifiedForWrite(arguments, settings);
        return new ArchiveMerger().merge(settings, archive);
    }

    private static Object reconcile(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        ArchiveVerifier.Verification archive = verifiedForWrite(arguments, settings);
        return new ImportReconciler().reconcile(settings, archive);
    }

    private static Object rollback(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        boolean execute = booleanOption(arguments, "execute", false);
        if (execute) {
            requireWriteApproval(
                arguments, settings, ApprovalGate.ACADEMY_ROLLBACK, arguments.required("manifest-sha256"));
        }
        return new ImportRollback().run(settings, arguments.required("manifest-sha256"), execute);
    }

    private static Object tempFreeze(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        boolean execute = booleanOption(arguments, "execute", false);
        if (execute) {
            requireWriteApproval(arguments, settings, ApprovalGate.ACADEMY_TEMP_CLEANUP, null);
        }
        return new TemporaryReleaseMaintenance().freeze(
            settings,
            execute,
            settings.receiptOutput());
    }

    private static Object tempPause(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        requireWriteApproval(arguments, settings, ApprovalGate.ACADEMY_TEMP_CLEANUP, null);
        return new TemporaryReleaseMaintenance().pause(settings);
    }

    private static Object tempCleanup(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        boolean execute = booleanOption(arguments, "execute", false);
        Path dryRunReceipt = null;
        if (execute) {
            dryRunReceipt = arguments.requiredPath("dry-run-receipt");
            requireWriteApproval(
                arguments, settings, ApprovalGate.ACADEMY_TEMP_CLEANUP, Sha256.of(dryRunReceipt));
        }
        int batchRows;
        try {
            batchRows = Integer.parseInt(arguments.optional("delete-batch-rows") == null
                ? "1000" : arguments.optional("delete-batch-rows"));
        } catch (NumberFormatException e) {
            throw new MigrationException("TEMP_CLEANUP_BATCH_LIMIT_INVALID", e);
        }
        return new TemporaryReleaseMaintenance().cleanup(
            settings, execute, batchRows, settings.receiptOutput(), dryRunReceipt);
    }

    private static Object tempUnpause(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        requireWriteApproval(arguments, settings, ApprovalGate.ACADEMY_TEMP_CLEANUP, null);
        boolean recovery = booleanOption(arguments, "recovery", false);
        if (recovery) {
            new TemporaryReleaseMaintenance().recoverTemporaryRelease(settings);
        } else {
            new TemporaryReleaseMaintenance().unpause(settings);
        }
        return Map.of("writesPaused", false, "mode", recovery ? "TEMPORARY_RECOVERY" : "FORMAL_CUTOVER");
    }

    private static Object seedDryRun(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        new HistoricalSchema().requireCurrent(settings.database());
        return new AggregateSeedCutover().dryRun(
            settings,
            arguments.requiredPath("seed"),
            arguments.requiredPath("seed-receipt"),
            arguments.requiredPath("output"));
    }

    private static Object seedApply(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        new HistoricalSchema().requireCurrent(settings.database());
        Path plan = arguments.requiredPath("plan");
        requireWriteApproval(
            arguments, settings, ApprovalGate.ACADEMY_SEED_APPLY, Sha256.of(plan));
        return new AggregateSeedCutover().apply(
            settings,
            arguments.requiredPath("seed"),
            arguments.requiredPath("seed-receipt"),
            plan,
            settings.receiptOutput());
    }

    private static Object seedRollback(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        new HistoricalSchema().requireCurrent(settings.database());
        String planSha256 = arguments.required("plan-sha256");
        boolean execute = booleanOption(arguments, "execute", false);
        if (execute) {
            requireWriteApproval(
                arguments, settings, ApprovalGate.ACADEMY_SEED_ROLLBACK, planSha256);
        }
        return new AggregateSeedCutover().rollback(
            settings, planSha256, execute, settings.receiptOutput());
    }

    private static Object seedVerify(
        CliArguments arguments
    ) {
        ImportSettings settings = settings(arguments);
        new HistoricalSchema().requireCurrent(settings.database());
        return new SeedCutoverVerifier().verify(
            settings,
            arguments.requiredPath("plan"),
            arguments.requiredPath("cleanup-receipt"),
            arguments.requiredPath("cleanup-noop-receipt"),
            arguments.requiredPath("cleanup-approval"),
            arguments.requiredPath("seed-apply-approval"),
            arguments.requiredPath("provisional-approval"),
            arguments.requiredPath("promotion-approval"),
            arguments.requiredPath("promotion-receipt"),
            arguments.requiredPath("output"));
    }

    private static ImportSettings settings(
        CliArguments arguments
    ) {
        return ImportSettings.load(Configuration.load(arguments.requiredPath("config")));
    }

    private static ArchiveVerifier.Verification verifiedForWrite(
        CliArguments arguments,
        ImportSettings settings
    ) {
        ArchiveVerifier.Verification archive = new ArchiveVerifier().verify(settings.archiveDirectory());
        new HistoricalSchema().requireCurrent(settings.database());
        requireWriteApproval(arguments, settings, ApprovalGate.ACADEMY_IMPORT, archive.manifestSha256());
        new DatabasePreflight().check(settings, archive, true);
        return archive;
    }

    private static void requireWriteApproval(
        CliArguments arguments,
        ImportSettings settings,
        String action,
        String artifactSha256
    ) {
        if (settings.database().targetKind() == DatabaseEnvironment.TargetKind.LOCAL) {
            return;
        }
        approval(arguments, action, artifactSha256, settings.database().identitySha256());
    }

    private static void approval(
        CliArguments arguments,
        String action,
        String artifactSha256,
        String databaseIdentitySha256
    ) {
        new ApprovalGate(Clock.systemUTC()).require(
            arguments.requiredPath("approval"), action, artifactSha256, databaseIdentitySha256);
    }

    private static boolean booleanOption(
        CliArguments arguments,
        String name,
        boolean defaultValue
    ) {
        String value = arguments.optional(name);
        if (value == null) {
            return defaultValue;
        }
        if (!"true".equals(value) && !"false".equals(value)) {
            throw new MigrationException("INVALID_BOOLEAN_ARGUMENT");
        }
        return Boolean.parseBoolean(value);
    }
}
