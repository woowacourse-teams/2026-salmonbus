package com.gustler.backend.api.salmon.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/** 연어행동 조회가 화면에 넘기는 종료 상태다. 판정하지 못한 상태는 문장이 없다. 확률과 후보는 싣지 않는다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record SalmonActionResponse(Kind kind, String reasonCode, String guidance) {
    public enum Kind { DISABLED, DECIDED }
}
