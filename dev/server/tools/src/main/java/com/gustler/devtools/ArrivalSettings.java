package com.gustler.devtools;

import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/** Shared only by the two dev application units; existing database secrets stay untouched. */
final class ArrivalSettings {
    static final String FILE = "arrivals.env";
    static final String WORKER = "http://127.0.0.1:8081";
    static final Set<String> KEYS =
            Set.of("ARRIVALS_ENABLED", "ARRIVAL_INTERNAL_TOKEN", "ARRIVAL_WORKER_BASE_URL");

    private ArrivalSettings() {}

    static Map<String, String> validate(Map<String, String> values) {
        Checks.require(values.keySet().equals(KEYS)
                        && "true".equals(values.get("ARRIVALS_ENABLED"))
                        && WORKER.equals(values.get("ARRIVAL_WORKER_BASE_URL"))
                        && values.get("ARRIVAL_INTERNAL_TOKEN").matches("[0-9a-f]{48,128}"),
                "DEV_ARRIVALS_SETTINGS");
        return values;
    }

    static Map<String, String> load(Path directory) throws Exception {
        return validate(Environment.parse(FileOps.privateText(directory.resolve(FILE)), KEYS));
    }

    static void validateIfPresent(Path directory) throws Exception {
        if (FileOps.exists(directory.resolve(FILE))) {
            load(directory);
        }
    }

    static boolean ensure(Path directory) throws Exception {
        if (FileOps.exists(directory.resolve(FILE))) {
            load(directory);
            return false;
        }
        byte[] token = new byte[32];
        new SecureRandom().nextBytes(token);
        var values = Map.of("ARRIVALS_ENABLED", "true",
                "ARRIVAL_INTERNAL_TOKEN", HexFormat.of().formatHex(token),
                "ARRIVAL_WORKER_BASE_URL", WORKER);
        FileOps.writeNew(directory.resolve(FILE), Environment.text(values).getBytes(), 0600);
        FileOps.sync(directory);
        return true;
    }

    static void preserve(Path source, Path destination, SettingsPreparation.Writer writer)
            throws Exception {
        if (FileOps.exists(source.resolve(FILE))) {
            load(source);
            writer.write(destination.resolve(FILE), FileOps.privateText(source.resolve(FILE)).getBytes(), 0600);
        }
    }
}
