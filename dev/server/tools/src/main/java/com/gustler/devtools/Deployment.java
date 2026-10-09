package com.gustler.devtools;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

final class Deployment {
    private final Layout layout;
    private final Host host;

    Deployment(Layout layout, Host host) {
        this.layout = layout;
        this.host = host;
    }

    static Path current(Path link, Path releases) throws Exception {
        if (!FileOps.exists(link)) {
            return null;
        }
        Checks.require(Files.isSymbolicLink(link), "DEV_CURRENT_LINK");
        var target = link.toRealPath();
        Checks.require(
                target.getParent().equals(releases)
                        && target.getFileName().toString().matches(RevisionStore.HASH),
                "DEV_CURRENT_LINK");
        return target;
    }

    static void setLink(Path link, Path target) throws Exception {
        var temporary = link.resolveSibling("." + link.getFileName() + "." + UUID.randomUUID());
        try {
            Files.createSymbolicLink(temporary, link.getParent().relativize(target));
            Files.move(
                    temporary,
                    link,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            FileOps.sync(link.getParent());
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Map<String, Object> install(Path incoming, Map<String, String> environment) throws Exception {
        var metadata = RevisionStore.component(incoming, layout, host, environment);
        var base = layout.root().resolve(metadata.kind());
        var releases = base.resolve("releases");
        var staging = base.resolve("staging");
        Checks.require(
                RevisionStore.verify(staging, true).equals(metadata), "DEV_STAGING_MISMATCH");
        var release = releases.resolve(metadata.releaseId());
        var current = current(base.resolve("current"), releases);
        var previous = current(base.resolve("previous"), releases);
        var old = current == null ? null : RevisionStore.verify(current, true);
        Checks.require(old == null || old.kind().equals(metadata.kind()), "DEV_CURRENT_COMPONENT");
        if (FileOps.exists(release)) {
            Checks.require(
                    RevisionStore.verify(release, true).equals(metadata), "DEV_RELEASE_CHANGED");
            FileOps.removeTree(staging);
        } else {
            FileOps.moveNew(staging, release);
        }
        var account = host.account();
        Checks.require(account.uid() > 0, "DEV_SERVICE_ACCOUNT");
        try (var paths = Files.walk(release)) {
            for (var path : paths.toList()) {
                FileOps.owner(path, 0, account.gid());
                FileOps.mode(
                        path,
                        Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)
                                        || path.toString().endsWith(".sh")
                                ? 0755
                                : 0644);
            }
        }
        var unit = "salmonbus-" + metadata.kind() + ".service";
        var target = layout.units().resolve(unit);
        var source = release.resolve("systemd/" + unit);
        if (FileOps.exists(target)) {
            FileOps.protectedFile(target, "DEV_UNIT_FILE");
        }
        final boolean changedUnit = !FileOps.exists(target) || Files.mismatch(source, target) != -1;
        if (changedUnit) {
            FileOps.atomicFile(target, Files.readAllBytes(source), 0644);
            host.run("systemctl", "daemon-reload");
        }
        host.run("systemctl", "enable", unit);
        if (!release.equals(current)) {
            if (current != null) {
                setLink(base.resolve("previous"), current);
            }
            setLink(base.resolve("current"), release);
        }
        final boolean changed =
                old == null || !old.sourceDigest().equals(metadata.sourceDigest()) || changedUnit;
        FileOps.atomicFile(
                base.resolve(".changed"),
                ("changed=" + (changed ? "yes" : "no") + "\nreason=dev 배포 버전 비교\n").getBytes(),
                0600);
        return Map.of(
                "status",
                "ok",
                "component",
                metadata.kind(),
                "restartRequired",
                changed,
                "previousAvailable",
                current != null || previous != null);
    }
}
