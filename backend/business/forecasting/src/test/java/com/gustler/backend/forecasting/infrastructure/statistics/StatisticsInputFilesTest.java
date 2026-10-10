package com.gustler.backend.forecasting.infrastructure.statistics;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StatisticsInputFilesTest {

    private static final String ABC_SHA256 =
        "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";

    @TempDir
    Path directory;

    @Test
    void 저장한_크기와_내용이_같으면_파일을_반복해서_검사할_수_있다() throws Exception {
        // given
        Files.writeString(directory.resolve("samples.bin"), "abc", StandardCharsets.UTF_8);
        var files = List.of(declaration());

        // when & then
        assertThatCode(() -> {
            StatisticsInputFiles.verify(directory, files);
            StatisticsInputFiles.verify(directory, files);
        }).doesNotThrowAnyException();
    }

    @Test
    void 목록에_있는_파일이_없으면_검사에_실패한다() {
        // given
        var files = List.of(declaration());

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, files))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("일반 파일");
    }

    @Test
    void 저장한_크기와_실제_파일_크기가_다르면_검사에_실패한다() throws Exception {
        // given
        Files.writeString(directory.resolve("samples.bin"), "abcd", StandardCharsets.UTF_8);

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, List.of(declaration())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("크기가 다르다");
    }

    @Test
    void 파일_크기가_같아도_내용이_바뀌면_검사에_실패한다() throws Exception {
        // given
        Files.writeString(directory.resolve("samples.bin"), "abd", StandardCharsets.UTF_8);

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, List.of(declaration())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256이 다르다");
    }

    @Test
    void 검사할_목록에_같은_파일이_중복되면_검사에_실패한다() {
        // given
        var files = List.of(declaration(), declaration());

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, files))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("중복");
    }

    @ParameterizedTest
    @ValueSource(strings = {"../samples.bin", "/tmp/samples.bin", "part/samples.bin", "..", ""})
    void 파일_이름이_비어_있거나_경로를_포함하면_등록할_수_없다(String name) {
        // given: 잘못된 입력을 사용한다.

        // when & then
        assertThatThrownBy(() -> new StatisticsInputFile(name, 3, ABC_SHA256))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("이름");
    }

    @Test
    void 파일_크기가_음수이거나_해시_형식이_잘못되면_등록할_수_없다() {
        // given
        long negativeSize = -1;
        String invalidHash = "invalid";

        // when & then
        assertThatThrownBy(() -> new StatisticsInputFile("samples.bin", negativeSize, ABC_SHA256))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new StatisticsInputFile("samples.bin", 3, invalidHash))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 검사할_파일_목록이_비어_있으면_검사에_실패한다() {
        // given
        List<StatisticsInputFile> files = List.of();

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, files))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("목록");
    }

    @Test
    void 파일이나_디렉터리가_심볼릭_링크이면_검사에_실패한다() throws Exception {
        // given
        Path source = directory.resolve("source.bin");
        Files.writeString(source, "abc", StandardCharsets.UTF_8);
        Files.createSymbolicLink(directory.resolve("samples.bin"), source);

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, List.of(declaration())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("일반 파일");

        // given
        Path linked = directory.resolve("linked");
        Files.createSymbolicLink(linked, directory);

        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(linked, List.of(declaration())))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("디렉터리");
    }

    @Test
    void 파일을_나누어_읽더라도_마지막_부분의_변경을_찾아낸다() throws Exception {
        // given
        byte[] content = new byte[64 * 1024 + 17];
        content[content.length - 1] = 1;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path file = directory.resolve("large.bin");
        Files.write(file, content);
        var files = List.of(new StatisticsInputFile("large.bin", content.length, hash));

        // when & then
        assertThatCode(() -> StatisticsInputFiles.verify(directory, files)).doesNotThrowAnyException();

        // given
        content[content.length - 1] = 2;
        Files.write(file, content);
        // when & then
        assertThatThrownBy(() -> StatisticsInputFiles.verify(directory, files))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("SHA-256이 다르다");
    }

    @Test
    void 빈_파일도_저장한_크기와_내용이_같으면_검사를_통과한다() throws Exception {
        // given
        Files.createFile(directory.resolve("empty.bin"));
        var files = List.of(new StatisticsInputFile("empty.bin", 0,
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"));

        // when & then
        assertThatCode(() -> StatisticsInputFiles.verify(directory, files)).doesNotThrowAnyException();
    }

    private StatisticsInputFile declaration() {
        return new StatisticsInputFile("samples.bin", 3, ABC_SHA256);
    }
}
