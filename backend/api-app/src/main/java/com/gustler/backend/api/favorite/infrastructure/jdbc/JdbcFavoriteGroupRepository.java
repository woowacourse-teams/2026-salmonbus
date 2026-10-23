package com.gustler.backend.api.favorite.infrastructure.jdbc;

import com.gustler.backend.api.favorite.application.FavoriteGroupRepository;
import com.gustler.backend.api.favorite.domain.Favorite;
import com.gustler.backend.api.favorite.domain.FavoriteDirection;
import com.gustler.backend.api.favorite.domain.FavoriteGroup;
import com.gustler.backend.api.favorite.domain.FavoriteGroupId;
import com.gustler.backend.api.favorite.domain.FavoriteGroupName;
import com.gustler.backend.api.favorite.domain.FavoriteId;
import com.gustler.backend.api.favorite.domain.FavoriteTarget;
import com.gustler.backend.api.favorite.domain.MemberFavoriteGroups;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class JdbcFavoriteGroupRepository implements FavoriteGroupRepository {
    private static final String FIND_MEMBER_WITH_FAVORITES = """
        SELECT m.id AS member_id,
               fg.id AS group_id, fg.name AS group_name, fg.position AS group_position,
               f.id AS favorite_id, f.route_id, f.stop_id, f.direction, f.position AS favorite_position
        FROM member m
        LEFT JOIN favorite_group fg ON fg.member_id = m.id
        LEFT JOIN favorite f ON f.favorite_group_id = fg.id
        WHERE m.public_id = ?
        ORDER BY fg.position, f.position
        """;

    private static final String FIND_FAVORITES = """
        SELECT fg.id AS group_id, fg.name AS group_name, fg.position AS group_position,
               f.id AS favorite_id, f.route_id, f.stop_id, f.direction, f.position AS favorite_position
        FROM favorite_group fg
        LEFT JOIN favorite f ON f.favorite_group_id = fg.id
        WHERE fg.member_id = ?
        ORDER BY fg.position, f.position
        """;

    private static final String INSERT_GROUP = """
        INSERT INTO favorite_group (id, member_id, name, position)
        VALUES (?, ?, ?, ?)
        """;

    private static final String INSERT_FAVORITE = """
        INSERT INTO favorite (id, favorite_group_id, route_id, stop_id, direction, position)
        VALUES (?, ?, ?, ?, ?, ?)
        """;

    private final JdbcClient jdbcClient;

    public JdbcFavoriteGroupRepository(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<MemberFavoriteGroups> findByMemberPublicId(UUID memberPublicId) {
        Objects.requireNonNull(memberPublicId, "회원 공개 ID는 null일 수 없습니다.");
        List<FavoriteRow> rows = jdbcClient.sql(FIND_MEMBER_WITH_FAVORITES)
            .param(memberPublicId)
            .query(JdbcFavoriteGroupRepository::row)
            .list();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(groupsOf(rows));
    }

    @Override
    @Transactional
    public Optional<MemberFavoriteGroups> ensureDefault(UUID memberPublicId, FavoriteGroup defaultGroup) {
        Objects.requireNonNull(memberPublicId, "회원 공개 ID는 null일 수 없습니다.");
        Objects.requireNonNull(defaultGroup, "기본 즐겨찾기 그룹은 null일 수 없습니다.");
        Optional<Long> memberId = lockMember(memberPublicId);
        if (memberId.isEmpty()) {
            return Optional.empty();
        }

        MemberFavoriteGroups current = findByMemberId(memberId.get());
        if (!current.values().isEmpty()) {
            return Optional.of(current);
        }
        jdbcClient.sql(INSERT_GROUP)
            .params(defaultGroup.id().value(), memberId.get(), defaultGroup.name().value(), 0)
            .update();
        return Optional.of(new MemberFavoriteGroups(List.of(defaultGroup)));
    }

    @Override
    @Transactional
    public Optional<MemberFavoriteGroups> update(UUID memberPublicId, UnaryOperator<MemberFavoriteGroups> change) {
        Objects.requireNonNull(memberPublicId, "회원 공개 ID는 null일 수 없습니다.");
        Objects.requireNonNull(change, "즐겨찾기 그룹 변경은 null일 수 없습니다.");
        Optional<Long> memberId = lockMember(memberPublicId);
        if (memberId.isEmpty()) {
            return Optional.empty();
        }

        MemberFavoriteGroups current = findByMemberId(memberId.get());
        MemberFavoriteGroups changed = Objects.requireNonNull(change.apply(current),
            "변경된 즐겨찾기 그룹은 null일 수 없습니다.");
        replace(memberId.get(), changed);
        return Optional.of(changed);
    }

    private Optional<Long> lockMember(UUID memberPublicId) {
        return jdbcClient.sql("SELECT id FROM member WHERE public_id = ? FOR UPDATE")
            .param(memberPublicId)
            .query(Long.class)
            .optional();
    }

    private MemberFavoriteGroups findByMemberId(final long memberId) {
        return groupsOf(jdbcClient.sql(FIND_FAVORITES)
            .param(memberId)
            .query(JdbcFavoriteGroupRepository::row)
            .list());
    }

    private void replace(final long memberId, MemberFavoriteGroups groups) {
        jdbcClient.sql("DELETE FROM favorite_group WHERE member_id = ?")
            .param(memberId)
            .update();
        for (int groupPosition = 0; groupPosition < groups.values().size(); groupPosition++) {
            FavoriteGroup group = groups.values().get(groupPosition);
            jdbcClient.sql(INSERT_GROUP)
                .params(group.id().value(), memberId, group.name().value(), groupPosition)
                .update();
            insertFavorites(group);
        }
    }

    private void insertFavorites(FavoriteGroup group) {
        for (int favoritePosition = 0; favoritePosition < group.favorites().size(); favoritePosition++) {
            Favorite favorite = group.favorites().get(favoritePosition);
            jdbcClient.sql(INSERT_FAVORITE)
                .params(favorite.id().value(), group.id().value(), favorite.target().routeId(),
                    favorite.target().stopId(), favorite.target().direction().name(), favoritePosition)
                .update();
        }
    }

    private static MemberFavoriteGroups groupsOf(List<FavoriteRow> rows) {
        Map<UUID, GroupRows> groups = new LinkedHashMap<>();
        for (FavoriteRow row : rows) {
            if (row.groupId() == null) {
                continue;
            }
            GroupRows group = groups.computeIfAbsent(row.groupId(), ignored ->
                new GroupRows(row.groupId(), row.groupName(), new ArrayList<>()));
            if (row.favoriteId() != null) {
                group.favorites().add(new Favorite(new FavoriteId(row.favoriteId()),
                    new FavoriteTarget(row.routeId(), row.stopId(), row.direction())));
            }
        }
        return new MemberFavoriteGroups(groups.values().stream()
            .map(group -> new FavoriteGroup(new FavoriteGroupId(group.id()),
                new FavoriteGroupName(group.name()), group.favorites()))
            .toList());
    }

    private static FavoriteRow row(ResultSet rows, final int rowNumber) throws SQLException {
        return new FavoriteRow(
            rows.getObject("group_id", UUID.class),
            rows.getString("group_name"),
            rows.getObject("favorite_id", UUID.class),
            rows.getString("route_id"),
            rows.getString("stop_id"),
            direction(rows.getString("direction"))
        );
    }

    private static FavoriteDirection direction(String value) {
        if (value == null) {
            return null;
        }
        return FavoriteDirection.valueOf(value);
    }

    private record FavoriteRow(
        UUID groupId,
        String groupName,
        UUID favoriteId,
        String routeId,
        String stopId,
        FavoriteDirection direction
    ) {
    }

    private record GroupRows(
        UUID id,
        String name,
        List<Favorite> favorites
    ) {
    }
}
