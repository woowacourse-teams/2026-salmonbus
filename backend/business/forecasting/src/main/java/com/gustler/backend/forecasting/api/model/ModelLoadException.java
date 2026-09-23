package com.gustler.backend.forecasting.api.model;

/** 모델 파일을 읽거나 검증하지 못해 계산에 사용할 수 없는 경우다. */
public class ModelLoadException extends RuntimeException {
    public ModelLoadException(String message) { super(message); }
    public ModelLoadException(String message, Throwable cause) { super(message, cause); }
}
