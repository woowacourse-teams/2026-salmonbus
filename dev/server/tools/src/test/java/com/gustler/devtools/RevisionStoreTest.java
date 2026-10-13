package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

class RevisionStoreTest {
    @TempDir Path root;
    private Path directory;

    @BeforeEach
    void prepare() throws Exception {
        directory = Files.createDirectory(root.resolve("basis"));
        Files.writeString(directory.resolve("public.txt"), "public development fixture\n");
    }

    private RevisionStore.Revision seal(final boolean review) throws Exception {
        return RevisionStore.seal(
                directory, "basis", "a".repeat(40), "b".repeat(64), "b".repeat(64), review);
    }

    @Test
    void verifiesExactRevisionAndRejectsReviewDeployment() throws Exception {
        var expected = seal(false);
        assertEquals(expected, RevisionStore.verify(directory, true));
        Files.delete(directory.resolve("revision.json"));
        seal(true);
        RevisionStore.verify(directory, false);
        assertEquals(
                "REVIEW_REVISION_CANNOT_DEPLOY",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> RevisionStore.verify(directory, true))
                        .getMessage());
    }

    @Test
    void rejectsChangedPayloadAndUnexpectedFiles() throws Exception {
        seal(false);
        Files.writeString(directory.resolve("public.txt"), "changed\n");
        assertEquals(
                "REVISION_FILE_CHANGED",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> RevisionStore.verify(directory, false))
                        .getMessage());
        Files.writeString(directory.resolve("public.txt"), "public development fixture\n");
        Files.writeString(directory.resolve("extra.txt"), "unexpected\n");
        assertEquals(
                "REVISION_FILE_SET_CHANGED",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> RevisionStore.verify(directory, false))
                        .getMessage());
    }

    @Test
    void rejectsSymlinksAndHardlinks() throws Exception {
        seal(false);
        Files.createSymbolicLink(directory.resolve("alias"), Path.of("public.txt"));
        assertThrows(IllegalArgumentException.class, () -> RevisionStore.verify(directory, false));
        Files.delete(directory.resolve("alias"));
        Files.createLink(root.resolve("outside"), directory.resolve("public.txt"));
        assertEquals(
                "REVISION_HARDLINK",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> RevisionStore.verify(directory, false))
                        .getMessage());
    }

    @Test
    void rejectsPrivateFilesEvenWithValidManifest() throws Exception {
        Files.writeString(directory.resolve("postgres.env"), "private fixture\n");
        seal(false);
        assertEquals(
                "PRIVATE_FILE_IN_REVISION",
                assertThrows(
                                IllegalArgumentException.class,
                                () -> RevisionStore.verify(directory, false))
                        .getMessage());
    }

    @Test
    void rejectsMetadataTamperingAndDuplicateJsonKeys() throws Exception {
        seal(false);
        var file = directory.resolve("revision.json");
        var original = Files.readString(file);
        Files.writeString(
                file, original.replace("\"environment\":\"dev\"", "\"environment\":\"prod\""));
        assertThrows(IllegalArgumentException.class, () -> RevisionStore.verify(directory, false));
        Files.writeString(
                file,
                original.replace(
                        "\"environment\":\"dev\"",
                        "\"environment\":\"dev\",\"environment\":\"dev\""));
        assertThrows(Exception.class, () -> RevisionStore.verify(directory, false));
    }

    @Test
    void onlyMatchingComponentGroupsAndBasisAreAccepted() throws Exception {
        var basis = seal(false);
        for (var kind : java.util.List.of("api", "worker")) {
            var app =
                    new RevisionStore.Revision(
                            1,
                            "dev",
                            kind,
                            "a".repeat(40),
                            "c".repeat(64),
                            basis.sourceDigest(),
                            false,
                            Map.of(),
                            "d".repeat(64));
            var environment =
                    Map.of(
                            "APPLICATION_NAME",
                            RevisionStore.APPLICATION,
                            "DEPLOYMENT_GROUP_NAME",
                            RevisionStore.APPLICATION + "-" + kind);
            RevisionStore.routing(app, environment, basis);
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RevisionStore.routing(
                                    app,
                                    Environment.combined(
                                            environment,
                                            Map.of("APPLICATION_NAME", "salmonbus-backend")),
                                    basis));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            RevisionStore.routing(
                                    app,
                                    Environment.combined(
                                            environment,
                                            Map.of(
                                                    "DEPLOYMENT_GROUP_NAME",
                                                    RevisionStore.APPLICATION
                                                            + "-"
                                                            + (kind.equals("api")
                                                                    ? "worker"
                                                                    : "api"))),
                                    basis));
            var wrongBasis =
                    new RevisionStore.Revision(
                            1,
                            "dev",
                            "basis",
                            basis.commit(),
                            basis.sourceDigest(),
                            "e".repeat(64),
                            false,
                            basis.files(),
                            basis.releaseId());
            assertThrows(
                    IllegalArgumentException.class,
                    () -> RevisionStore.routing(app, environment, wrongBasis));
        }
    }

    @Test
    void helperOnlyContentChangesDoNotInvalidateTheRuntimeContract() throws Exception {
        var basis = seal(false);
        var app =
                new RevisionStore.Revision(
                        1,
                        "dev",
                        "api",
                        "a".repeat(40),
                        "c".repeat(64),
                        basis.basisDigest(),
                        false,
                        Map.of(),
                        "d".repeat(64));
        var updatedHelper =
                new RevisionStore.Revision(
                        1,
                        "dev",
                        "basis",
                        basis.commit(),
                        "e".repeat(64),
                        basis.basisDigest(),
                        false,
                        basis.files(),
                        basis.releaseId());
        assertDoesNotThrow(
                () ->
                        RevisionStore.routing(
                                app,
                                Map.of(
                                        "APPLICATION_NAME",
                                        RevisionStore.APPLICATION,
                                        "DEPLOYMENT_GROUP_NAME",
                                        RevisionStore.APPLICATION + "-api"),
                                updatedHelper));
    }
}
