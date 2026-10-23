package com.gustler.backend.member.infrastructure.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.member.api.LoginProvider;
import com.gustler.backend.member.domain.MemberRepository;
import com.gustler.backend.member.domain.NewMember;
import com.gustler.backend.member.domain.Nickname;
import com.gustler.backend.member.support.MemberIntegrationTest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@MemberIntegrationTest
class JdbcMemberStatisticsTest {
    @Autowired
    private JdbcMemberStatistics statistics;

    @Autowired
    private MemberRepository members;

    @Test
    void 현재_존재하는_회원수를_센다() {
        // given
        long before = statistics.currentMembers();
        members.register(new NewMember(
            UUID.randomUUID(), new Nickname("출근연어"), LoginProvider.KAKAO, "statistics-1", OffsetDateTime.now()));
        NewMember withdrawn = new NewMember(
            UUID.randomUUID(), new Nickname("퇴근연어"), LoginProvider.APPLE, "statistics-2", OffsetDateTime.now());
        members.register(withdrawn);
        members.delete(withdrawn.publicId());

        // when & then
        assertThat(statistics.currentMembers()).isEqualTo(before + 1);
    }
}
