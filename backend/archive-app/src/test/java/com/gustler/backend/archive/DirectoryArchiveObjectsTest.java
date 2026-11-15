package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryArchiveObjectsTest {
    static final String KEY = "salmonbus/test/lake-v3/forecast_run/source_date=2026-11-12/000000-abc.jsonl.gz";
    static final byte[] BYTES = "line\n".getBytes(StandardCharsets.UTF_8);

    @TempDir
    Path root;

    @Test
    void 쓴_파일을_키의_경로에_두고_그대로_읽는다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);

        // when
        objects.putVerified(KEY, BYTES);

        // then
        assertSoftly(softly -> {
            softly.assertThat(root.resolve(KEY)).hasBinaryContent(BYTES);
            softly.assertThat(objects.read(KEY, BYTES.length)).isEqualTo(BYTES);
            softly.assertThat(filesUnder(root)).containsExactly(root.resolve(KEY));
        });
    }

    @Test
    void 같은_키에_같은_내용을_다시_쓰면_그대로_둔다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);
        objects.putVerified(KEY, BYTES);

        // when
        objects.putVerified(KEY, BYTES);

        // then
        assertThat(objects.read(KEY, BYTES.length)).isEqualTo(BYTES);
    }

    @Test
    void 같은_키에_다른_내용이_이미_있으면_거부하고_먼저_쓴_내용을_남긴다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);
        objects.putVerified(KEY, BYTES);
        byte[] other = "other\n".getBytes(StandardCharsets.UTF_8);

        // when, then
        assertThatThrownBy(() -> objects.putVerified(KEY, other)).isInstanceOf(IllegalStateException.class);
        assertThat(root.resolve(KEY)).hasBinaryContent(BYTES);
    }

    @Test
    void 폴더_밖을_가리키는_키는_쓰기도_읽기도_치우기도_거부한다() throws IOException {
        // given
        Path outside = Files.createFile(root.resolveSibling(root.getFileName() + "-outside"));
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);
        List<String> keys = List.of("../" + outside.getFileName(), outside.toString(), "a/../../" + outside.getFileName(),
            "", ".", "a/..");

        // then
        try {
            assertSoftly(softly -> {
                for (String key : keys) {
                    softly.assertThatThrownBy(() -> objects.putVerified(key, BYTES))
                        .isInstanceOf(IllegalArgumentException.class);
                    softly.assertThatThrownBy(() -> objects.read(key, 1)).isInstanceOf(IllegalArgumentException.class);
                    softly.assertThatThrownBy(() -> objects.release(key)).isInstanceOf(IllegalArgumentException.class);
                }
                softly.assertThat(outside).exists();
                softly.assertThat(root).exists();
            });
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void 읽기_한도가_범위를_벗어나거나_파일이_한도보다_크면_거부한다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);
        objects.putVerified(KEY, BYTES);

        // then
        assertSoftly(softly -> {
            softly.assertThatThrownBy(() -> objects.read(KEY, 0)).isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> objects.read(KEY, ArchiveFiles.MAX_BYTES + 1))
                .isInstanceOf(IllegalArgumentException.class);
            softly.assertThatThrownBy(() -> objects.read(KEY, BYTES.length - 1))
                .isInstanceOf(IllegalStateException.class);
            softly.assertThat(objects.read(KEY, ArchiveFiles.MAX_BYTES)).isEqualTo(BYTES);
        });
    }

    @Test
    void 없는_파일을_읽으면_예외를_던진다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);

        // then
        assertThatThrownBy(() -> objects.read(KEY, 1)).isInstanceOf(UncheckedIOException.class);
    }

    @Test
    void 치우기는_그_접두_아래만_지우고_없는_접두는_지나간다() {
        // given
        DirectoryArchiveObjects objects = new DirectoryArchiveObjects(root);
        String kept = "salmonbus/test/lake-v3/forecast_run/source_date=2026-11-13/manifest-def.json";
        objects.putVerified(KEY, BYTES);
        objects.putVerified(KEY.replace("000000-abc.jsonl.gz", "manifest-abc.json"), BYTES);
        objects.putVerified(kept, BYTES);

        // when
        objects.release("salmonbus/test/lake-v3/forecast_run/source_date=2026-11-12/");
        objects.release("salmonbus/test/lake-v3/forecast_run/source_date=2026-11-01/");

        // then
        assertSoftly(softly -> {
            softly.assertThat(root.resolve("salmonbus/test/lake-v3/forecast_run/source_date=2026-11-12"))
                .doesNotExist();
            softly.assertThat(filesUnder(root)).containsExactly(root.resolve(kept));
        });
    }

    static List<Path> filesUnder(Path directory) {
        try (Stream<Path> paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
