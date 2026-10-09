package com.gustler.devtools;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class CommandRunner {
    private static final int OUTPUT_LIMIT = 2 * 1024 * 1024;
    private static final long STOP_GRACE_MILLIS = 500;

    Host.Result run(
            Path cwd, Map<String, String> environment, Duration timeout, List<String> command)
            throws Exception {
        Checks.require(!timeout.isNegative() && !timeout.isZero(), "DEV_COMMAND_TIMEOUT");
        final boolean grouped = System.getProperty("os.name").equals("Linux");
        var invocation = new ArrayList<String>();
        if (grouped) {
            Checks.require(
                    Files.isExecutable(Path.of("/usr/bin/setsid")), "DEV_PROCESS_GROUP_REQUIRED");
            invocation.addAll(List.of("/usr/bin/setsid", "--wait", "--"));
        }
        invocation.addAll(command);
        var builder = new ProcessBuilder(invocation).directory(cwd.toFile());
        if (environment != null) {
            builder.environment().clear();
            builder.environment().putAll(environment);
        }
        final long deadline = System.nanoTime() + timeout.toNanos();
        var process = builder.start();
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        var children = new LinkedHashSet<ProcessHandle>();
        try {
            process.getOutputStream().close();
            var stdout = executor.submit(() -> read(process.getInputStream()));
            var stderr = executor.submit(() -> read(process.getErrorStream()));
            while (process.isAlive()) {
                process.descendants().forEach(children::add);
                process.waitFor(Math.min(50, remainingMillis(deadline)), TimeUnit.MILLISECONDS);
            }
            return new Host.Result(
                    process.exitValue(), result(stdout, deadline), result(stderr, deadline));
        } catch (TimeoutException error) {
            throw new IllegalArgumentException("DEV_COMMAND_TIMEOUT");
        } catch (ExecutionException error) {
            if (error.getCause() instanceof RuntimeException cause) {
                throw cause;
            }
            throw error;
        } finally {
            terminate(process, children, grouped);
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    private static long remainingMillis(final long deadline) throws TimeoutException {
        final long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException();
        }
        return Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining));
    }

    private static String result(Future<String> output, final long deadline) throws Exception {
        return output.get(remainingMillis(deadline), TimeUnit.MILLISECONDS);
    }

    private static String read(java.io.InputStream stream) throws Exception {
        try (stream) {
            byte[] bytes = stream.readNBytes(OUTPUT_LIMIT + 1);
            Checks.require(bytes.length <= OUTPUT_LIMIT, "DEV_COMMAND_OUTPUT_TOO_LARGE");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static void terminate(
            Process process, java.util.Set<ProcessHandle> children, final boolean grouped)
            throws Exception {
        process.descendants().forEach(children::add);
        if (grouped) {
            signal(process.pid(), "TERM");
        }
        children.forEach(ProcessHandle::destroy);
        process.destroy();
        process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS);
        for (var child : List.copyOf(children)) {
            child.descendants().forEach(children::add);
        }
        if (grouped) {
            signal(process.pid(), "KILL");
        }
        children.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
        process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static void signal(final long group, String signal) throws Exception {
        var process =
                new ProcessBuilder("/bin/kill", "-" + signal, "--", "-" + group)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
        if (!process.waitFor(STOP_GRACE_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
        }
    }
}
