package com.gustler.backend.api.salmon.application;

/** 확정된 판정을 남기는 포트. 저장 형식은 구현이 숨긴다. */
public interface DecisionLog {
    void append(SalmonDecisionRecord record);
}
