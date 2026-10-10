package com.gustler.backend.forecasting.infrastructure.statistics;

record StatisticsInputFile(String name, long byteCount, String sha256) {

    StatisticsInputFile {
        if (name == null || !name.matches("[a-zA-Z0-9][a-zA-Z0-9._-]*")) {
            throw new IllegalArgumentException("입력 파일 이름은 하위 경로 없이 지정해야 한다");
        }
        if (byteCount < 0) {
            throw new IllegalArgumentException("입력 파일 크기는 0 이상이어야 한다");
        }
        if (sha256 == null || !sha256.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("입력 파일 SHA-256은 소문자 16진수 64자리여야 한다");
        }
    }
}
