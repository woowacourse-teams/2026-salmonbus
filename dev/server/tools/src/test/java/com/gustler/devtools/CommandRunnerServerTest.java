package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@Tag("linux-root")
class CommandRunnerServerTest {
    @TempDir Path base;

    @Test
    void timeoutKillsChildrenIgnoringTermWithoutWaitingForTheirOutput() throws Exception {
        var script = base.resolve("child.sh");
        var written = base.resolve("late-write");
        Files.writeString(script, "trap '' TERM\nsleep 2\necho unexpected > \"$1\"\nsleep 10\n");
        final long started = System.nanoTime();
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CommandRunner()
                                        .run(
                                                base,
                                                null,
                                                Duration.ofMillis(300),
                                                List.of(
                                                        "/bin/sh",
                                                        "-c",
                                                        "/bin/sh \"$1\" \"$2\" & wait",
                                                        "test",
                                                        script.toString(),
                                                        written.toString())));
        assertEquals("DEV_COMMAND_TIMEOUT", error.getMessage());
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
        Thread.sleep(2200);
        assertFalse(Files.exists(written));
    }

    @Test
    void outputDrainSharesTheSameDeadlineAsTheParent() throws Exception {
        final long started = System.nanoTime();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new CommandRunner()
                                .run(
                                        base,
                                        null,
                                        Duration.ofMillis(300),
                                        List.of(
                                                "/bin/sh",
                                                "-c",
                                                "(trap '' TERM; sleep 12) & exit 0")));
        assertTrue(
                Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(3)) < 0);
    }

    @Test
    void successfulCommandsReturnBothStreamsAndExitStatus() throws Exception {
        var result =
                new CommandRunner()
                        .run(
                                base,
                                null,
                                Duration.ofSeconds(3),
                                List.of(
                                        "/bin/sh",
                                        "-c",
                                        "printf output; printf diagnostic >&2; exit 7"));
        assertEquals(7, result.exit());
        assertEquals("output", result.stdout());
        assertEquals("diagnostic", result.stderr());
    }
}
