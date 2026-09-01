package com.gustler.backend.processor.seatdistribution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 계수 묶음을 이루는 파일 둘의 자리.
 *
 * <p>symlink 를 거절한다. 배포 자리에 흔히 걸리는 {@code current} 같은 연결을 그대로 읽으면,
 * 요약값을 잰 파일과 실제로 읽은 파일이 도중에 갈릴 수 있다.
 *
 * <p>파일 둘만 보는 것으로는 모자라서 <b>묶음이 놓인 자리도 본다.</b> {@code current} 는 파일보다
 * 디렉터리에 걸리는 쪽이 흔하다. 자리가 연결이면 설명 파일을 읽은 뒤 연결이 옮겨 갔을 때
 * 다른 계수 파일을 읽게 되고, 그때 요약값이 안 맞아 적재가 실패한다.
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
        BundleCheck.BUNDLE_DIRECTORY_IS_NOT_SYMBOLIC_LINK.require(
            !Files.isSymbolicLink(directory), "symlink 다: " + directory);
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
