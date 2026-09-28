package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.gbis.api.GbisKey;
import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionRuntimeConfigurationTest {

    private static final String BASE_URL = "https://gbis.test";
    private static final int DAILY_LIMIT = 10_000;

    @Test
    void 호출_한도가_돌려_쓰는_슬롯과_GBIS_클라이언트가_아는_슬롯이_같다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, "fake-key-a", "fake-key-b", null, "fake-key-d",
            DAILY_LIMIT);
        CollectionRuntimeConfiguration configuration = new CollectionRuntimeConfiguration();

        // when
        List<String> quotaAliases = configuration.callQuotaPolicy(properties).locationKeyAliases();
        List<String> clientAliases = configuration.gbisClientOptions(properties).keys().stream()
            .map(GbisKey::alias)
            .toList();

        // then
        assertThat(quotaAliases).containsExactly(GbisKey.PRIMARY, "b", "d").isEqualTo(clientAliases);
    }

    @Test
    void 인증키가_공백으로_풀려도_GBIS_클라이언트_설정을_만든다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, "%20", DAILY_LIMIT);
        CollectionRuntimeConfiguration configuration = new CollectionRuntimeConfiguration();

        // when
        String actual = configuration.gbisClientOptions(properties).serviceKeyOf(GbisKey.PRIMARY);

        // then
        assertThat(actual).isEqualTo(" ");
    }
}
