package com.gustler.backend.forecasting.api.evaluation;

import java.nio.file.Path;

/** 이관 객체의 조건부 쓰기와 크기가 제한된 읽기 경계다. */
public interface EvaluationArchiveObjects {
    /** 인코딩한 원본의 Base64 SHA-256과 전송 본문이 일치하고 조건부 저장에 성공한 경우에만 정상 반환한다. */
    void putIfAbsent(String bucket, String key, Path source, String checksumSha256);

    void download(String bucket, String key, Path destination, long maxBytes);
}
