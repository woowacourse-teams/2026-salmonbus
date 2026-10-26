package com.gustler.backend.forecasting.infrastructure.statistics;

import java.nio.file.Path;

/** 이관 객체의 조건부 쓰기와 크기가 제한된 읽기 경계다. */
public interface EvaluationArchiveObjects {
    void putIfAbsent(String bucket, String key, Path source);

    void download(String bucket, String key, Path destination, long maxBytes);
}
