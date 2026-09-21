package com.gustler.backend.processor;

/** 모델이 지원하는 좌석 입력 범위를 벗어났다. 차량의 실제 제원 오류를 뜻하지는 않는다. */
public final class SeatRangeException extends IllegalArgumentException {

    private final String inputField;
    private final int inputValue;
    private final int minimum;
    private final int maximum;

    public SeatRangeException(String inputField, int inputValue, int minimum, int maximum) {
        super("모델 좌석 입력 범위 초과: %s=%d, 허용 범위=%d..%d"
            .formatted(inputField, inputValue, minimum, maximum));
        this.inputField = inputField;
        this.inputValue = inputValue;
        this.minimum = minimum;
        this.maximum = maximum;
    }

    public String inputField() {
        return inputField;
    }

    public int inputValue() {
        return inputValue;
    }

    public int minimum() {
        return minimum;
    }

    public int maximum() {
        return maximum;
    }
}
