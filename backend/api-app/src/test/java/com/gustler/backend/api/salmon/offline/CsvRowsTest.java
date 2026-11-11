package com.gustler.backend.api.salmon.offline;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvRowsTest {
    @TempDir
    Path folder;

    @Test
    void 따옴표_없는_빈_칸은_null로_따옴표_안의_빈_칸은_빈_문자열로_읽는다() throws Exception {
        Path file = Files.writeString(folder.resolve("rows.csv"), "a,,\"\"\n");

        try (CsvRows rows = CsvRows.open(file)) {
            assertThat(rows.next()).isEqualTo(Arrays.asList("a", null, ""));
            assertThat(rows.next()).isNull();
        }
    }

    @Test
    void 따옴표_안의_쉼표와_줄바꿈과_겹따옴표를_값으로_읽는다() throws Exception {
        Path file = Files.writeString(folder.resolve("rows.csv"),
            "id,payload\r\n1,\"{\"\"name\"\": \"\"a,b\"\",\n\"\"count\"\": 2}\"\n2,plain");

        try (CsvRows rows = CsvRows.open(file)) {
            assertThat(rows.next()).containsExactly("id", "payload");
            assertThat(rows.next()).containsExactly("1", "{\"name\": \"a,b\",\n\"count\": 2}");
            assertThat(rows.next()).containsExactly("2", "plain");
            assertThat(rows.next()).isNull();
        }
    }
}
