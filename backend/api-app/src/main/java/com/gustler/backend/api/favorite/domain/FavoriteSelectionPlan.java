package com.gustler.backend.api.favorite.domain;

import java.util.List;
import java.util.Objects;

/** 체크박스의 최종 선택 상태와 현재 저장 상태 사이에서 필요한 변경이다. */
public record FavoriteSelectionPlan(
    List<FavoriteGroupId> additions,
    List<FavoriteGroupId> removals
) {
    public FavoriteSelectionPlan {
        additions = List.copyOf(Objects.requireNonNull(additions, "등록 목록은 null일 수 없습니다."));
        removals = List.copyOf(Objects.requireNonNull(removals, "해제 목록은 null일 수 없습니다."));
    }

    public boolean unchanged() {
        return additions.isEmpty() && removals.isEmpty();
    }
}
