package com.gustler.backend.observations.api;

import java.util.List;

/** 이전 회차의 수집이 모두 끝난 뒤, 남은 미완료 상태만 종료하고 기록은 보존한다. */
public interface CloseUnfinishedCollections {
    /** 단일 수집자의 회차 경계에서만 호출한다. 다른 수집이 진행 중이면 호출하지 않는다. */
    List<Long> closePreviousRound();
}
