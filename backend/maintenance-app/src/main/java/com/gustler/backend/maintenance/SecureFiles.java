package com.gustler.backend.maintenance;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Set;

/**
 * 유지보수 입력 파일의 일반 파일 여부와 허용된 접근 권한 조건을 확인한다.
 */
public final class SecureFiles {
    private static final Set<PosixFilePermission> OWNER_FILE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private SecureFiles() {
    }

    /**
     * 심볼릭 링크를 거부하고 일반 파일 및 소유자 전용 권한을 검증한다. POSIX 미지원 환경에서도 일반 파일 여부는 확인한다.
     */
    public static void requirePrivateRegularFile(
        Path path
    ) {
        try {
            if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MaintenanceException("PRIVATE_FILE_INVALID");
            }
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS);
            if (!OWNER_FILE.equals(permissions) && !Set.of(PosixFilePermission.OWNER_READ).equals(permissions)) {
                throw new MaintenanceException("PRIVATE_FILE_PERMISSIONS_INVALID");
            }
        } catch (UnsupportedOperationException e) {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new MaintenanceException("PRIVATE_FILE_INVALID", e);
            }
        } catch (IOException e) {
            throw new MaintenanceException("PRIVATE_FILE_INSPECTION_FAILED", e);
        }
    }
}
