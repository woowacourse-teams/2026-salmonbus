package com.gustler.backend.api.favorite.domain;

import com.gustler.backend.api.favorite.FavoriteLimitExceededException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/** 회원이 이름과 순서를 정해 관리하는 즐겨찾기 묶음이다. */
public final class FavoriteGroup {
    public static final int MAX_FAVORITES = 2;

    private final FavoriteGroupId id;
    private final FavoriteGroupName name;
    private final List<Favorite> favorites;

    public FavoriteGroup(FavoriteGroupId id, FavoriteGroupName name, List<Favorite> favorites) {
        this.id = Objects.requireNonNull(id, "즐겨찾기 그룹 ID는 null일 수 없습니다.");
        this.name = Objects.requireNonNull(name, "즐겨찾기 그룹 이름은 null일 수 없습니다.");
        List<Favorite> requiredFavorites = Objects.requireNonNull(
            favorites,
            "즐겨찾기 목록은 null일 수 없습니다."
        );
        this.favorites = List.copyOf(requiredFavorites);
        requireWithinLimit();
        requireUniqueTargets();
    }

    public FavoriteGroupId id() {
        return id;
    }

    public FavoriteGroupName name() {
        return name;
    }

    public List<Favorite> favorites() {
        return favorites;
    }

    public boolean contains(FavoriteTarget target) {
        return favorites.stream()
            .map(Favorite::target)
            .anyMatch(target::equals);
    }

    public boolean isFull() {
        return favorites.size() == MAX_FAVORITES;
    }

    public Optional<Favorite> find(FavoriteId favoriteId) {
        return favorites.stream().filter(favorite -> favorite.id().equals(favoriteId)).findFirst();
    }

    /** 같은 대상이면 기존 값을 유지하고 새 대상일 때만 한도를 검사한다. */
    public FavoriteGroup add(Favorite favorite) {
        Objects.requireNonNull(favorite, "즐겨찾기는 null일 수 없습니다.");
        if (contains(favorite.target())) {
            return this;
        }
        if (isFull()) {
            throw new FavoriteLimitExceededException();
        }
        return new FavoriteGroup(id, name, append(favorite));
    }

    public FavoriteGroup remove(FavoriteTarget target) {
        Objects.requireNonNull(target, "즐겨찾기 대상은 null일 수 없습니다.");
        List<Favorite> remaining = favorites.stream()
            .filter(favorite -> !favorite.target().equals(target))
            .toList();
        if (remaining.size() == favorites.size()) {
            return this;
        }
        return new FavoriteGroup(id, name, remaining);
    }

    public FavoriteGroup remove(FavoriteId favoriteId) {
        Objects.requireNonNull(favoriteId, "즐겨찾기 ID는 null일 수 없습니다.");
        return new FavoriteGroup(id, name, favorites.stream()
            .filter(favorite -> !favorite.id().equals(favoriteId))
            .toList());
    }

    public FavoriteGroup reorder(List<FavoriteId> orderedIds) {
        Objects.requireNonNull(orderedIds, "즐겨찾기 순서는 null일 수 없습니다.");
        List<FavoriteId> currentIds = favorites.stream().map(Favorite::id).toList();
        if (!hasSameIds(orderedIds, currentIds)) {
            throw new IllegalArgumentException(
                "현재 즐겨찾기 ID를 빠짐없이 한 번씩 보내야 합니다."
            );
        }
        return new FavoriteGroup(id, name, orderedIds.stream()
            .map(favoriteId -> find(favoriteId).orElseThrow())
            .toList());
    }

    public FavoriteGroup rename(FavoriteGroupName changedName) {
        FavoriteGroupName requiredName = Objects.requireNonNull(
            changedName,
            "변경할 이름은 null일 수 없습니다."
        );
        return new FavoriteGroup(id, requiredName, favorites);
    }

    private List<Favorite> append(Favorite favorite) {
        return Stream.concat(favorites.stream(), Stream.of(favorite)).toList();
    }

    private static boolean hasSameIds(List<FavoriteId> orderedIds, List<FavoriteId> currentIds) {
        if (orderedIds.size() != currentIds.size()) {
            return false;
        }
        if (new HashSet<>(orderedIds).size() != orderedIds.size()) {
            return false;
        }
        return new HashSet<>(orderedIds).equals(new HashSet<>(currentIds));
    }

    private void requireWithinLimit() {
        if (favorites.size() > MAX_FAVORITES) {
            throw new FavoriteLimitExceededException();
        }
    }

    private void requireUniqueTargets() {
        List<FavoriteTarget> targets = favorites.stream()
            .map(Favorite::target)
            .toList();
        if (new HashSet<>(targets).size() != targets.size()) {
            throw new IllegalArgumentException(
                "같은 즐겨찾기 그룹에 같은 즐겨찾기 대상을 중복할 수 없습니다."
            );
        }
    }
}
