package com.gustler.backend.forecasting.infrastructure.bundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 검증할 설명 파일을 고정된 일반 파일에서 읽는다. */
record BundleFiles(Path manifest) {
    static BundleFiles under(Path directory) {
        if (Files.isSymbolicLink(directory)) {
            throw new BundleRejectedException("번들 디렉터리는 symlink일 수 없다");
        }
        return new BundleFiles(directory.resolve("manifest.json"));
    }

    byte[] readManifest() {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleRejectedException("설명 파일은 일반 파일이어야 한다: " + manifest);
        }
        try {
            if (Files.size(manifest) > 1_048_576) {
                throw new BundleRejectedException("설명 파일은 1MiB 이하여야 한다");
            }
            return Files.readAllBytes(manifest);
        } catch (IOException error) {
            throw new BundleRejectedException("설명 파일을 읽지 못했다", error);
        }
    }
}
