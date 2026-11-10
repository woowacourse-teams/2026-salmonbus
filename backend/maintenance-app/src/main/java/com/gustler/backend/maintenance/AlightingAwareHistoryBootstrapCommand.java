package com.gustler.backend.maintenance;

import com.gustler.backend.forecasting.api.alighting.BootstrapAlightingAwareHistory;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/** 옵션 검증은 DB 연결을 만들기 전에 끝낸다. */
final class AlightingAwareHistoryBootstrapCommand {
    private AlightingAwareHistoryBootstrapCommand() {
    }

    record Options(Path config, BootstrapAlightingAwareHistory.Request request) {
    }

    static Options parse(CliArguments arguments) {
        arguments.requireOnly(Set.of("config", "directory", "route-version", "source-origin-batch", "through-batch",
            "checkpoint-directory", "batch-limit", "maximum-history-rows"));
        Path config = arguments.requiredPath("config");
        BootstrapAlightingAwareHistory.Request request = new BootstrapAlightingAwareHistory.Request(
            arguments.requiredPath("directory").toString(),
            positive(arguments, "route-version", Long.MAX_VALUE),
            positive(arguments, "source-origin-batch", Long.MAX_VALUE),
            positive(arguments, "through-batch", Long.MAX_VALUE),
            arguments.requiredPath("checkpoint-directory").toString(),
            (int) positive(arguments, "batch-limit", 1000),
            (int) positive(arguments, "maximum-history-rows", Integer.MAX_VALUE - 1L));
        return new Options(config, request);
    }

    static Object invoke(Options options, BootstrapAlightingAwareHistory bootstrap) {
        return Map.of("databaseReadOnly", true, "activated", false, "forecastsPublished", false,
            "checkpoint", bootstrap.rebuild(options.request()));
    }

    private static long positive(CliArguments arguments, String name, final long maximum) {
        String value = arguments.required(name);
        try {
            final long parsed = Long.parseLong(value);
            if (parsed < 1 || parsed > maximum) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException failure) {
            throw new MaintenanceException("INVALID_ARGUMENT_" + name.replace('-', '_').toUpperCase());
        }
    }
}
