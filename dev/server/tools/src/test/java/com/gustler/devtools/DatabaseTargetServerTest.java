package com.gustler.devtools;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

@Tag("linux-root")
class DatabaseTargetServerTest {
    @TempDir Path base;
    private Fixtures fixture;

    @BeforeEach
    void prepare() throws Exception {
        assertTrue(new Host().root());
        fixture = new Fixtures(base);
        fixture.settings();
    }

    private void enableRds() throws Exception {
        var directory = fixture.layout.config();
        var marker = Json.read(FileOps.privateText(directory.resolve("environment.json")).getBytes());
        ((tools.jackson.databind.node.ObjectNode) marker).put("accountId", "000000000000");
        FileOps.atomicFile(directory.resolve("environment.json"), Json.bytes(marker), 0600);
        var certificate = directory.resolve(DatabaseTarget.CERTIFICATE);
        FileOps.writeNew(certificate, "test certificate bytes\n".getBytes(), 0644);
        FileOps.writeNew(directory.resolve(DatabaseTarget.FILE), Json.bytes(Map.of(
                "environment", "dev",
                "dbInstanceIdentifier", "salmonbus-dev-db",
                "dbInstanceArn", "arn:aws:rds:ap-northeast-2:000000000000:db:salmonbus-dev-db",
                "endpoint", "salmonbus-dev-db.example.ap-northeast-2.rds.amazonaws.com",
                "port", 5432,
                "certificateSha256", FileOps.sha(certificate))), 0600);
        var url = DatabaseTarget.url(directory);
        for (var name : java.util.List.of("api", "worker")) {
            var file = directory.resolve(name + ".env");
            var values = new java.util.TreeMap<>(Environment.parse(
                    FileOps.privateText(file), Environment.KEYS.get(name)));
            values.put("DB_URL", url);
            FileOps.atomicFile(file, Environment.text(values).getBytes(), 0600);
        }
    }

    @Test
    void existingLocalSettingsNeedNoRdsFiles() throws Exception {
        assertEquals(Environment.DATABASE, DatabaseTarget.url(fixture.layout.config()));
        assertDoesNotThrow(() -> Environment.load(fixture.layout.config()));
    }

    @Test
    void rdsSettingsAndCertificateSurviveBasisSettingsReplacement() throws Exception {
        enableRds();
        var directory = fixture.layout.config();
        var before = Environment.load(directory);
        var next = base.resolve("next-config");
        new SettingsPreparation(fixture.layout).replacement(
                fixture.source, next,
                Json.read(Json.bytes(Map.of("accountId", "000000000000", "instanceId", "test-dev-instance"))),
                "test-volume", Fixtures.COMMIT, fixture.host.account().gid());
        assertEquals(before, Environment.load(next));
        assertEquals(FileOps.sha(directory.resolve(DatabaseTarget.FILE)),
                FileOps.sha(next.resolve(DatabaseTarget.FILE)));
        assertEquals(FileOps.sha(directory.resolve(DatabaseTarget.CERTIFICATE)),
                FileOps.sha(next.resolve(DatabaseTarget.CERTIFICATE)));
        assertEquals(0600, FileOps.attribute(next.resolve(DatabaseTarget.FILE), "mode") & 0777);
        assertEquals(0644, FileOps.attribute(next.resolve(DatabaseTarget.CERTIFICATE), "mode") & 0777);
    }

    @Test
    void deletingThePinDoesNotSilentlyAllowAnExistingRemoteUrl() throws Exception {
        enableRds();
        Files.delete(fixture.layout.config().resolve(DatabaseTarget.FILE));
        assertThrows(IllegalArgumentException.class, () -> Environment.load(fixture.layout.config()));
    }

    @Test
    void rejectsCertificateUnreadableByTheApplicationAccount() throws Exception {
        enableRds();
        var certificate = fixture.layout.config().resolve(DatabaseTarget.CERTIFICATE);
        FileOps.mode(certificate, 0600);
        assertEquals("DEV_RDS_CERTIFICATE",
                assertThrows(IllegalArgumentException.class,
                        () -> Environment.load(fixture.layout.config())).getMessage());
        FileOps.mode(certificate, 0644);
        assertDoesNotThrow(() -> Environment.load(fixture.layout.config()));
    }

    @Test
    void rejectsReadablePinSymlinksAndTamperedCertificates() throws Exception {
        enableRds();
        var directory = fixture.layout.config();
        var target = directory.resolve(DatabaseTarget.FILE);
        FileOps.mode(target, 0644);
        assertThrows(IllegalArgumentException.class, () -> Environment.load(directory));
        FileOps.mode(target, 0600);
        var outside = base.resolve("outside-target.json");
        Files.move(target, outside);
        Files.createSymbolicLink(target, outside);
        assertThrows(IllegalArgumentException.class, () -> Environment.load(directory));
        Files.delete(target);
        Files.move(outside, target);
        Files.writeString(directory.resolve(DatabaseTarget.CERTIFICATE), "changed certificate\n");
        assertEquals("DEV_RDS_CERTIFICATE",
                assertThrows(IllegalArgumentException.class, () -> Environment.load(directory)).getMessage());
    }
}
