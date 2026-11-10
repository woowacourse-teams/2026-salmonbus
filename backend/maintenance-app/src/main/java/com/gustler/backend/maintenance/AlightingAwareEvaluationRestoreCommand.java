package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.evaluation.AlightingAwareEvaluationArchive;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Map;
import java.util.Set;

final class AlightingAwareEvaluationRestoreCommand {
    private AlightingAwareEvaluationRestoreCommand() {
    }

    record Options(
        Path config, Path archiveFile, long observationId, int stopOrder,
        String target, String labelVersion, long qualityRevision
    ) {
    }

    static Options parse(CliArguments arguments) {
        arguments.requireOnly(Set.of("config", "archive-file", "observation-id", "target-stop-order",
            "target-kind", "label-version", "quality-revision", "approval"));
        Path config = arguments.requiredPath("config");
        String target = arguments.required("target-kind");
        if (!Set.of("AFTER_ALIGHTING_ESTIMATE", "DEPARTURE_ZERO").contains(target)) {
            throw new MaintenanceException("INVALID_ARGUMENT_TARGET_KIND");
        }
        String label = arguments.required("label-version");
        if (!label.matches("[a-zA-Z0-9._-]{1,80}")) {
            throw new MaintenanceException("INVALID_ARGUMENT_LABEL_VERSION");
        }
        return new Options(config, arguments.requiredPath("archive-file"),
            number(arguments, "observation-id", 1, Long.MAX_VALUE),
            (int) number(arguments, "target-stop-order", 1, Integer.MAX_VALUE), target, label,
            number(arguments, "quality-revision", 0, Long.MAX_VALUE));
    }

    static void authorize(
        CliArguments arguments,
        DatabaseEnvironment environment,
        LocalAlightingAwareArchive archive,
        Clock clock
    ) {
        if (environment.targetKind() != DatabaseEnvironment.TargetKind.LOCAL) {
            new ApprovalGate(clock).require(arguments.requiredPath("approval"),
                ApprovalGate.ACADEMY_ALIGHTING_AWARE_EVALUATION_RESTORE, archive.sha256(), environment.identitySha256());
        }
    }

    static Object invoke(Options options, AlightingAwareEvaluationArchive archive) {
        final int rows = archive.restore(options.observationId(), options.stopOrder(), options.target(),
            options.labelVersion(), options.qualityRevision());
        return Map.of("restoredRows", rows, "activated", false, "forecastsPublished", false);
    }

    private static long number(
        CliArguments arguments,
        String name,
        final long minimum,
        final long maximum
    ) {
        try {
            final long parsed = Long.parseLong(arguments.required(name));
            if (parsed < minimum || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new MaintenanceException("INVALID_ARGUMENT_" + name.replace('-', '_').toUpperCase());
        }
    }
}
