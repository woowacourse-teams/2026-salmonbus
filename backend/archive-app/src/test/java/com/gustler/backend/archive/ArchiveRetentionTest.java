package com.gustler.backend.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class ArchiveRetentionTest {
    @Test
    void 원문_필드와_NULL과_시각을_바꾸지_않는다() {
        List<String> rows = List.of("{\"id\":1,\"seats\":null,\"p\":0.7302}",
            "{\"time\":\"2026-11-07T23:59:59+09:00\"}");
        assertThat(ArchiveFiles.decode(ArchiveFiles.encode(rows))).isEqualTo(rows);
    }

    @Test
    void 손상된_압축파일은_거부한다() {
        assertThatThrownBy(() -> ArchiveFiles.decode(new byte[] { 1, 2, 3 })).isInstanceOf(IllegalStateException.class);
    }
}
