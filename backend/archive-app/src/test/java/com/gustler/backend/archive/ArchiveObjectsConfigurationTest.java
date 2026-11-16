package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class ArchiveObjectsConfigurationTest {
    final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(ArchiveObjectsConfiguration.class)
        .withPropertyValues("archive.bucket=test-bucket");

    @Test
    void 폴더를_정하지_않으면_S3에_쓴다() {
        runner.run(context -> assertThat(context.getBean(ArchiveObjects.class)).isInstanceOf(S3ArchiveObjects.class));
    }

    @Test
    void 폴더를_정하고_보관하지_않는_접두를_쓰면_폴더에_쓴다(@TempDir Path directory) {
        runner.withPropertyValues("archive.directory=" + directory,
                "archive.partition-export.prefix=salmonbus/dev/lake-v3/")
            .run(context -> assertThat(context.getBean(ArchiveObjects.class))
                .isInstanceOf(DirectoryArchiveObjects.class));
    }

    @Test
    void 폴더를_정하고_운영_접두를_그대로_두면_기동을_막는다(@TempDir Path directory) {
        runner.withPropertyValues("archive.directory=" + directory)
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessage("ARCHIVE_DIRECTORY_WITH_RETAINED_PREFIX"));
    }

    @Test
    void 폴더를_정하고_옛_테이블_내보내기를_운영_접두로_켜면_기동을_막는다(@TempDir Path directory) {
        runner.withPropertyValues("archive.directory=" + directory,
                "archive.partition-export.prefix=salmonbus/dev/lake-v3/", "archive.legacy-export.enabled=true")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessage("ARCHIVE_DIRECTORY_WITH_RETAINED_PREFIX"));
    }
}
