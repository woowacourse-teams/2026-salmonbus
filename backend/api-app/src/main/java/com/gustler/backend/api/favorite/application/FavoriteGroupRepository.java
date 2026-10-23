package com.gustler.backend.api.favorite.application;

import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;

/** 회원 공개 UUID를 경계로 즐겨찾기 그룹 전체를 읽고 원자적으로 변경한다. */
public interface FavoriteGroupRepository {
    Optional<MemberFavoriteGroups> findByMemberPublicId(UUID memberPublicId);

    /** 회원 행을 잠근 뒤 그룹이 하나도 없을 때만 기본 그룹을 만든다. 회원이 없으면 빈 값을 반환한다. */
    Optional<MemberFavoriteGroups> ensureDefault(UUID memberPublicId, FavoriteGroup defaultGroup);

    /** 회원 행을 잠근 뒤 최신 집합에 변경을 적용한다. 회원이 없으면 변경하지 않고 빈 값을 반환한다. */
    Optional<MemberFavoriteGroups> update(
        UUID memberPublicId,
        UnaryOperator<MemberFavoriteGroups> change
    );
}
