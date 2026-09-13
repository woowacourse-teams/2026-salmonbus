package com.gustler.backend.migration;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

final class CliArguments {

    private final String command;
    private final Map<String, String> options;

    private CliArguments(
        String command,
        Map<String, String> options
    ) {
        this.command = command;
        this.options = Map.copyOf(options);
    }

    static CliArguments parse(
        String[] arguments
    ) {
        if (arguments.length == 0 || arguments[0].startsWith("--")) {
            throw new MigrationException("COMMAND_REQUIRED");
        }
        Map<String, String> options = new LinkedHashMap<>();
        for (int index = 1; index < arguments.length; index += 2) {
            if (!arguments[index].startsWith("--") || index + 1 >= arguments.length) {
                throw new MigrationException("INVALID_COMMAND_ARGUMENTS");
            }
            String name = arguments[index].substring(2);
            if (name.isBlank() || options.put(name, arguments[index + 1]) != null) {
                throw new MigrationException("DUPLICATE_COMMAND_ARGUMENT");
            }
        }
        return new CliArguments(arguments[0], options);
    }

    String command() {
        return command;
    }

    String required(
        String name
    ) {
        String value = options.get(name);
        if (value == null || value.isBlank()) {
            throw new MigrationException("MISSING_ARGUMENT_" + name.replace('-', '_').toUpperCase());
        }
        return value;
    }

    Path requiredPath(
        String name
    ) {
        return Path.of(required(name)).toAbsolutePath().normalize();
    }

    String optional(
        String name
    ) {
        return options.get(name);
    }
}
