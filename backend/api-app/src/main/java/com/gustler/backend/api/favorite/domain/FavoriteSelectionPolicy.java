package com.gustler.backend.api.favorite.domain;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 여러 즐겨찾기 그룹의 최종 체크 상태를 부분 반영 없이 저장하기 위한 사전 계산이다. */
public class FavoriteSelectionPolicy {
    public FavoriteSelectionPlan plan(
        MemberFavoriteGroups ownedGroups,
        List<FavoriteGroupId> selectedGroupIds,
        FavoriteTarget target
    ) {
        Objects.requireNonNull(ownedGroups, "회원 즐겨찾기 그룹은 null일 수 없습니다.");
        Objects.requireNonNull(selectedGroupIds, "선택한 즐겨찾기 그룹 목록은 null일 수 없습니다.");
        Objects.requireNonNull(target, "즐겨찾기 대상은 null일 수 없습니다.");

        Set<FavoriteGroupId> selected = requireUniqueSelection(selectedGroupIds);
        requireOwnedGroups(ownedGroups, selected);

        List<FavoriteGroupId> additions = new ArrayList<>();
        List<FavoriteGroupId> removals = new ArrayList<>();
        for (FavoriteGroup group : ownedGroups.values()) {
            collectChange(group, selected.contains(group.id()), target, additions, removals);
        }
        return new FavoriteSelectionPlan(additions, removals);
    }

    private static Set<FavoriteGroupId> requireUniqueSelection(
        List<FavoriteGroupId> selectedGroupIds
    ) {
        Set<FavoriteGroupId> selected = new HashSet<>(selectedGroupIds);
        if (selected.size() != selectedGroupIds.size()) {
            throw new IllegalArgumentException("선택한 즐겨찾기 그룹 ID는 중복될 수 없습니다.");
        }
        return selected;
    }

    private static void requireOwnedGroups(
        MemberFavoriteGroups ownedGroups,
        Set<FavoriteGroupId> selected
    ) {
        boolean containsUnknown = selected.stream()
            .anyMatch(id -> ownedGroups.find(id).isEmpty());
        if (containsUnknown) {
            throw new IllegalArgumentException("현재 회원이 소유하지 않은 즐겨찾기 그룹이 포함되어 있습니다.");
        }
    }

    private static void collectChange(
        FavoriteGroup group,
        boolean selected,
        FavoriteTarget target,
        List<FavoriteGroupId> additions,
        List<FavoriteGroupId> removals
    ) {
        final boolean alreadyRegistered = group.contains(target);
        if (selected && !alreadyRegistered) {
            if (group.isFull()) {
                throw new FavoriteLimitExceededException();
            }
            additions.add(group.id());
        }
        if (!selected && alreadyRegistered) {
            removals.add(group.id());
        }
    }
}
