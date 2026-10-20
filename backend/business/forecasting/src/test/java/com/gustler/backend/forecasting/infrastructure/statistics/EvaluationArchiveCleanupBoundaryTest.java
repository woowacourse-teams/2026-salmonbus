package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.gustler.backend.forecasting.application.statistics.StatisticsArchiveReader.Reference;
import java.io.IOException;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EvaluationArchiveCleanupBoundaryTest {
    @TempDir
    Path directory;

    @Test
    void 다운로드_실패와_임시_정리_실패가_함께_나면_원래_실패에_정리_실패를_추가한다() {
        // given
        IllegalStateException primary = new IllegalStateException("injected download failure");
        EvaluationArchiveObjectStore store = new EvaluationArchiveObjectStore(directory, 1024, args -> {
            Path target = Path.of(args.getLast());
            try {
                Files.writeString(target.getParent().resolve("owned-by-test"), "cleanup failure");
            } catch (IOException exception) {
                throw new IllegalStateException(exception);
            }
            throw primary;
        });
        Reference reference = new Reference(UUID.fromString("00000000-0000-0000-0000-000000000223"),
            1, 1, 1, "a".repeat(64));

        // when
        Throwable failure = catchThrowable(() -> store.read(reference));

        // then
        assertThat(failure).isSameAs(primary);
        assertThat(failure.getSuppressed()).hasSize(1);
        assertThat(failure.getSuppressed()[0]).isInstanceOf(DirectoryNotEmptyException.class);
    }
}
