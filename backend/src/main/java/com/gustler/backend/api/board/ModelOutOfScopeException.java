package com.gustler.backend.api.board;

public class ModelOutOfScopeException extends RuntimeException {

    public ModelOutOfScopeException() {
        super("활성 모델 번들이 지원하지 않는 노선 판본입니다.");
    }
}
