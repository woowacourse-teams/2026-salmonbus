package com.gustler.devtools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

public final class DevTools {
    private DevTools() {}

    public static void main(String[] args) {
        try {
            execute(args, Layout.SERVER, new Host(), System.getenv());
        } catch (Exception error) {
            var code =
                    error instanceof IllegalArgumentException
                                    && error.getMessage() != null
                                    && error.getMessage().matches("[A-Z_]+")
                            ? error.getMessage()
                            : "DEV_TOOLS_FAILED";
            System.err.println(
                    Json.MAPPER.writeValueAsString(Map.of("status", "failed", "code", code)));
            System.exit(1);
        }
    }

    static Map<String, String> options(String[] args, Set<String> values, Set<String> flags) {
        var result = new HashMap<String, String>();
        for (int index = 1; index < args.length; index++) {
            var key = args[index];
            Checks.require(!result.containsKey(key), "DUPLICATE_ARGUMENT");
            if (flags.contains(key)) {
                result.put(key, "true");
            } else {
                Checks.require(
                        values.contains(key)
                                && index + 1 < args.length
                                && !args[index + 1].startsWith("--"),
                        "ARGUMENTS");
                result.put(key, args[++index]);
            }
        }
        return result;
    }

    static String required(Map<String, String> options, String name) {
        Checks.require(options.containsKey(name), "ARGUMENT_REQUIRED");
        return options.get(name);
    }

    static void execute(String[] args, Layout layout, Host host, Map<String, String> environment)
            throws Exception {
        Checks.require(args.length > 0, "COMMAND_REQUIRED");
        switch (args[0]) {
            case "preflight" -> {
                Checks.require(args.length == 2, "COMPONENT");
                new Preflight(layout, host).check(args[1], environment);
            }
            case "verify-target", "install" -> {
                var options = options(args, Set.of("--bundle"), Set.of());
                var incoming = Path.of(required(options, "--bundle")).toAbsolutePath();
                if (args[0].equals("verify-target")) {
                    RevisionStore.component(incoming, layout, host, environment);
                } else {
                    Json.output(new Deployment(layout, host).install(incoming, environment));
                }
            }
            case "prepare-data" -> {
                Checks.require(args.length == 1, "ARGUMENTS");
                Json.output(new DataPreparation(layout, host).prepare());
            }
            case "configure" -> {
                var options = options(args, Set.of("--frontend-origin", "--revision"), Set.of());
                Checks.require(host.root(), "ROOT_REQUIRED");
                try (var lease =
                        FileOps.lock(layout.locks().resolve("salmonbus-dev-config.lock"))) {
                    var identity = host.identity();
                    host.verifyInstance(identity);
                    var mount = host.mounted(layout.data());
                    var result =
                            new SettingsPreparation(layout)
                                    .initialize(
                                            layout.tools(),
                                            identity,
                                            mount.path("uuid").asText(),
                                            required(options, "--frontend-origin"),
                                            required(options, "--revision"),
                                            host.account().gid());
                    Json.output(Map.of("status", "ok", "result", result));
                }
            }
            case "bootstrap" -> {
                var options =
                        options(args, Set.of("--bundle", "--frontend-origin"), Set.of("--apply"));
                Json.output(
                        new Bootstrap(layout, host)
                                .prepare(
                                        Path.of(required(options, "--bundle")).toAbsolutePath(),
                                        required(options, "--frontend-origin"),
                                        options.containsKey("--apply")));
            }
            case "update-basis", "restore-basis" -> {
                var values =
                        args[0].equals("restore-basis")
                                ? Set.of("--release-id")
                                : Set.of("--bundle");
                var options = options(args, values, Set.of("--apply"));
                Path incoming;
                if (args[0].equals("restore-basis")) {
                    var release = required(options, "--release-id");
                    Checks.require(release.matches(RevisionStore.HASH), "DEV_BASIS_PATH");
                    incoming = layout.root().resolve("basis/releases/" + release);
                } else {
                    incoming = Path.of(required(options, "--bundle")).toAbsolutePath();
                }
                Json.output(
                        new BasisLifecycle(layout, host)
                                .change(incoming, options.containsKey("--apply")));
            }
            case "recover-basis" -> {
                var options = options(args, Set.of(), Set.of("--apply"));
                Json.output(
                        new BasisLifecycle(layout, host).recover(options.containsKey("--apply")));
            }
            case "check-build", "image-context", "package" -> {
                var options =
                        options(
                                args,
                                Set.of("--repo", "--output", "--wiremock-archive"),
                                Set.of("--codebuild", "--review"));
                var repo = Path.of(required(options, "--repo")).toAbsolutePath();
                if (args[0].equals("image-context")) {
                    new Packager(repo, host).imageContext(Path.of(required(options, "--output")));
                    return;
                }
                final boolean codebuild =
                        args[0].equals("check-build") || options.containsKey("--codebuild");
                var commit =
                        BuildSource.commit(
                                repo,
                                host,
                                environment,
                                codebuild,
                                options.containsKey("--review"));
                if (args[0].equals("check-build")) {
                    Json.output(
                            Map.of("status", "ok", "environment", "dev", "source", "codepipeline"));
                    return;
                }
                Checks.require(
                        !codebuild || "1".equals(environment.get("CODEBUILD_BUILD_SUCCEEDING")),
                        "CODEBUILD_FAILED");
                new Packager(repo, host)
                        .assemble(
                                Path.of(required(options, "--output")),
                                Path.of(required(options, "--wiremock-archive")),
                                commit,
                                options.containsKey("--review"));
                Json.output(
                        Map.of(
                                "status",
                                "ok",
                                "reviewOnly",
                                options.containsKey("--review"),
                                "artifacts",
                                java.util.List.of("basis", "api", "worker")));
            }
            default -> throw new IllegalArgumentException("UNKNOWN_COMMAND");
        }
    }
}
