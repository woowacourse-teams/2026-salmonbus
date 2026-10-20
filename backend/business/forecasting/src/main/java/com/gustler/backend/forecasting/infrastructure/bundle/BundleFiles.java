package com.gustler.backend.forecasting.infrastructure.bundle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/**
 * 계수 묶음을 구성하는 두 파일의 경로.
 *
 * <p>배포 경로에서 흔히 사용하는 {@code current} 같은 symlink 는 거절한다.
 * 연결이 도중에 바뀌면 요약값을 계산한 파일과 실제 읽는 파일이 달라질 수 있다.
 *
 * <p>파일뿐 아니라 계수 묶음의 디렉터리도 검사한다. {@code current} 는 파일보다
 * 디렉터리에 연결하는 경우가 흔하다. 설명 파일을 읽은 뒤 연결이 바뀌면 다른 계수 파일을 읽게 되어
 * 요약값 불일치로 적재가 실패한다.
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

    /**
     * 심볼릭 링크가 아닌 일반 참조 파일만 최대 1 MiB까지 읽는다. 누락·크기 초과·읽기 실패는 번들 검증 오류로 처리한다.
     */
    byte[] readRouteReference() {
        Path path = manifest.resolveSibling("route-reference.json");
        requireRegularFile(path, BundleCheck.ROUTE_REFERENCE);
        try {
            BundleCheck.ROUTE_REFERENCE.require(Files.size(path) <= 1_048_576, "정류장 기준 파일은 1MiB 이하여야 한다");
        } catch (IOException error) {
            throw new BundleRejectedException("정류장 기준 파일 크기를 읽지 못했다", error);
        }
        return read(path);
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
