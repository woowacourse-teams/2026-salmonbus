package com.gustler.backend.member.infrastructure.jdbc;

import com.gustler.backend.member.api.MemberStatistics;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcMemberStatistics implements MemberStatistics {
    private final JdbcClient jdbcClient;

    public JdbcMemberStatistics(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    public long currentMembers() {
        return jdbcClient.sql("SELECT /* salmonbus:member.monitoring */ count(*) FROM member")
            .query(Long.class).single();
    }
}
