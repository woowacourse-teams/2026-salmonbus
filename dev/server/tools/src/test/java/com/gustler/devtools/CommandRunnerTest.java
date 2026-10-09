package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

class CommandRunnerTest {
    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr"})
    void outputLimitFailureRetainsItsErrorCode(String stream) {
        var command = "head -c 2097153 /dev/zero" + (stream.equals("stderr") ? " >&2" : "");
        var error =
                assertThrows(
                        IllegalArgumentException.class,
                        () ->
                                new CommandRunner()
                                        .run(
                                                Path.of("/"),
                                                null,
                                                Duration.ofSeconds(10),
                                                List.of("/bin/sh", "-c", command)));
        assertEquals("DEV_COMMAND_OUTPUT_TOO_LARGE", error.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdout", "stderr"})
    void outputAtTheLimitIsAccepted(String stream) throws Exception {
        var command = "head -c 2097152 /dev/zero" + (stream.equals("stderr") ? " >&2" : "");
        var result =
                new CommandRunner()
                        .run(
                                Path.of("/"),
                                null,
                                Duration.ofSeconds(10),
                                List.of("/bin/sh", "-c", command));
        assertEquals(0, result.exit());
        assertEquals(
                2097152,
                stream.equals("stdout") ? result.stdout().length() : result.stderr().length());
        assertEquals("", stream.equals("stdout") ? result.stderr() : result.stdout());
    }
}
