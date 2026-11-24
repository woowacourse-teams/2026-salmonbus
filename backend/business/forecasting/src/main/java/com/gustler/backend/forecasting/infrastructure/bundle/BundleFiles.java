package com.gustler.backend.forecasting.infrastructure.bundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** 번들 manifest를 검증할 때 심볼릭 링크가 아닌 일반 파일만 읽는다. */
record BundleFiles(Path manifest) {

    static BundleFiles under(Path directory) {
        if (Files.isSymbolicLink(directory)) {
            throw new BundleRejectedException("번들 디렉터리는 심볼릭 링크일 수 없다");
        }
        return new BundleFiles(directory.resolve("manifest.json"));
    }

    byte[] readManifest() {
        if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
            throw new BundleRejectedException("manifest는 일반 파일이어야 한다: " + manifest);
        }
        try {
            if (Files.size(manifest) > 1_048_576) {
                throw new BundleRejectedException("manifest는 1MiB 이하여야 한다");
            }
            return Files.readAllBytes(manifest);
        } catch (IOException error) {
            throw new BundleRejectedException("manifest를 읽을 수 없다", error);
        }
    }
}
