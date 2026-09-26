package com.gustler.backend.forecasting.api.model;

import java.util.Objects;
import java.util.UUID;

public record ActivateModelCommand(UUID requestId, long expectedActiveVersion, String directory) {
    public ActivateModelCommand {
        Objects.requireNonNull(requestId, "requestId");
        if (expectedActiveVersion < 0 || directory == null || directory.isBlank()) {
            throw new IllegalArgumentException("활성화 버전과 모델 디렉터리를 확인해 주세요");
        }
    }
}
