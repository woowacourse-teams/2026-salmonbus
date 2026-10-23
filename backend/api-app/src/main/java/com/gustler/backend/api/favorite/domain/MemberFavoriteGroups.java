package com.gustler.backend.api.favorite.domain;

import com.gustler.backend.api.favorite.FavoriteGroupLimitExceededException;
import com.gustler.backend.api.favorite.RepresentativeFavoriteGroupDeletionException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** 현재 회원이 소유한 즐겨찾기 그룹의 순서와 개수 규칙이다. */
public final class MemberFavoriteGroups {
    public static final int MAX_GROUPS = 3;

    private final List<FavoriteGroup> values;

    public MemberFavoriteGroups(List<FavoriteGroup> values) {
        List<FavoriteGroup> requiredValues = Objects.requireNonNull(
            values,
            "즐겨찾기 그룹 목록은 null일 수 없습니다."
        );
        this.values = List.copyOf(requiredValues);
        requireWithinLimit();
        requireUniqueIds();
        requireUniqueNames();
    }

    public List<FavoriteGroup> values() {
        return values;
    }

    public MemberFavoriteGroups append(FavoriteGroup group) {
        Objects.requireNonNull(group, "즐겨찾기 그룹은 null일 수 없습니다.");
        if (values.size() == MAX_GROUPS) {
            throw new FavoriteGroupLimitExceededException();
        }
        return new MemberFavoriteGroups(Stream.concat(values.stream(), Stream.of(group)).toList());
    }

    public Optional<FavoriteGroup> find(FavoriteGroupId id) {
        return values.stream()
            .filter(group -> group.id().equals(id))
            .findFirst();
    }

    public MemberFavoriteGroups update(FavoriteGroupId id, UnaryOperator<FavoriteGroup> change) {
        Objects.requireNonNull(id, "즐겨찾기 그룹 ID는 null일 수 없습니다.");
        Objects.requireNonNull(change, "즐겨찾기 그룹 변경은 null일 수 없습니다.");
        return new MemberFavoriteGroups(values.stream()
            .map(group -> update(group, id, change))
            .toList());
    }

    private static FavoriteGroup update(FavoriteGroup group, FavoriteGroupId id,
        UnaryOperator<FavoriteGroup> change) {
        if (group.id().equals(id)) {
            return change.apply(group);
        }
        return group;
    }

    public MemberFavoriteGroups remove(FavoriteGroupId id) {
        Objects.requireNonNull(id, "즐겨찾기 그룹 ID는 null일 수 없습니다.");
        if (!values.isEmpty() && values.getFirst().id().equals(id)) {
            throw new RepresentativeFavoriteGroupDeletionException();
        }
        return new MemberFavoriteGroups(values.stream()
            .filter(group -> !group.id().equals(id))
            .toList());
    }

    public MemberFavoriteGroups reorder(List<FavoriteGroupId> orderedIds) {
        Objects.requireNonNull(orderedIds, "즐겨찾기 그룹 순서는 null일 수 없습니다.");
        if (!hasSameIds(orderedIds)) {
            throw new IllegalArgumentException(
                "현재 즐겨찾기 그룹 ID를 빠짐없이 한 번씩 보내야 합니다."
            );
        }
        return new MemberFavoriteGroups(orderedIds.stream()
            .map(id -> find(id).orElseThrow())
            .toList());
    }

    public MemberFavoriteGroups reorderAll(List<FavoriteGroupOrder> orders) {
        Objects.requireNonNull(orders, "즐겨찾기 전체 순서는 null일 수 없습니다.");
        MemberFavoriteGroups reordered = reorder(orders.stream()
            .map(FavoriteGroupOrder::groupId)
            .toList());
        for (FavoriteGroupOrder order : orders) {
            reordered = reordered.update(order.groupId(), group -> group.reorder(order.favoriteIds()));
        }
        return reordered;
    }

    private boolean hasSameIds(List<FavoriteGroupId> orderedIds) {
        if (orderedIds.size() != values.size()) {
            return false;
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            return false;
        }
        return new HashSet<>(orderedIds).equals(values.stream()
            .map(FavoriteGroup::id)
            .collect(Collectors.toSet()));
    }

    private void requireWithinLimit() {
        if (values.size() > MAX_GROUPS) {
            throw new FavoriteGroupLimitExceededException();
        }
    }

    private void requireUniqueIds() {
        List<FavoriteGroupId> ids = values.stream()
            .map(FavoriteGroup::id)
            .toList();
        if (new HashSet<>(ids).size() != ids.size()) {
            throw new IllegalArgumentException("즐겨찾기 그룹 ID는 중복될 수 없습니다.");
        }
    }

    private void requireUniqueNames() {
        List<FavoriteGroupName> names = values.stream()
            .map(FavoriteGroup::name)
            .toList();
        if (new HashSet<>(names).size() != names.size()) {
            throw new IllegalArgumentException(
                "같은 회원의 즐겨찾기 그룹 이름은 중복될 수 없습니다."
            );
        }
    }
}
