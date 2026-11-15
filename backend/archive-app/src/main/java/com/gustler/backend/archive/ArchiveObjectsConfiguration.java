package com.gustler.backend.archive;

import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 내보낸 파일을 둘 곳을 정한다. {@code archive.directory}가 비어 있으면 S3 버킷이고, 채워져 있으면 그 폴더다.
 *
 * <p>폴더는 파일을 보관하지 않고 DB에서 지운 줄의 파일을 지우므로, 보관해야 하는 운영 접두({@value #RETAINED_PREFIX})와 함께 쓰면
 * 기동을 막는다.
 */
@Configuration(proxyBeanMethods = false)
public class ArchiveObjectsConfiguration {
    static final String RETAINED_PREFIX = "salmonbus/prod/";

    @Bean
    ArchiveObjects archiveObjects(
        @Value("${archive.directory:}") String directory,
        @Value("${archive.bucket}") String bucket,
        @Value("${archive.partition-export.prefix:salmonbus/prod/lake-v3/}") String partitionPrefix,
        @Value("${archive.legacy-export.enabled:false}") final boolean legacyEnabled,
        @Value("${archive.legacy-export.prefix:salmonbus/prod/lake-v3-legacy/}") String legacyPrefix
    ) {
        if (directory.isBlank()) {
            return new S3ArchiveObjects(bucket);
        }
        if (partitionPrefix.startsWith(RETAINED_PREFIX) || isRetainedLegacy(legacyEnabled, legacyPrefix)) {
            throw new IllegalArgumentException("ARCHIVE_DIRECTORY_WITH_RETAINED_PREFIX");
        }
        return new DirectoryArchiveObjects(Path.of(directory));
    }

    private static boolean isRetainedLegacy(final boolean legacyEnabled, String legacyPrefix) {
        return legacyEnabled && legacyPrefix.startsWith(RETAINED_PREFIX);
    }
}
