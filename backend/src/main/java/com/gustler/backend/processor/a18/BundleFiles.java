package com.gustler.backend.processor.a18;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 계수 묶음을 이루는 파일 둘의 자리.
 *
 * <p>symlink 를 거절한다. 배포 자리에 흔히 걸리는 {@code current} 같은 연결을 그대로 읽으면,
 * 요약값을 잰 파일과 실제로 읽은 파일이 도중에 갈릴 수 있다.
 */
record BundleFiles(
    Path manifest,
    Path weights
) {

    static final String MANIFEST_NAME = "manifest.json";
    static final String WEIGHTS_NAME = "weights.safetensors";

    static BundleFiles under(
        Path directory
    ) {
        return new BundleFiles(directory.resolve(MANIFEST_NAME), directory.resolve(WEIGHTS_NAME));
    }

    byte[] readManifest() {
        requireRegularFile(manifest, BundleCheck.MANIFEST_IS_REGULAR_FILE);
        return read(manifest);
    }

    byte[] readWeights() {
        requireRegularFile(weights, BundleCheck.WEIGHTS_IS_REGULAR_FILE);
        return read(weights);
    }

    private static void requireRegularFile(
        Path path,
        BundleCheck check
    ) {
        check.require(Files.exists(path, LinkOption.NOFOLLOW_LINKS), "파일이 없다: " + path);
        check.require(!Files.isSymbolicLink(path), "symlink 다: " + path);
        check.require(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS), "일반 파일이 아니다: " + path);
    }

    private static byte[] read(
        Path path
    ) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException error) {
            throw new BundleRejectedException("파일을 읽지 못했다: " + path, error);
        }
    }
}
