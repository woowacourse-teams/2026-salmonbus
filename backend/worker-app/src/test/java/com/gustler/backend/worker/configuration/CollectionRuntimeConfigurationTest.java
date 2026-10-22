package com.gustler.backend.worker.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.gbis.api.GbisClientOptions;
import com.gustler.backend.gbis.api.GbisKey;
import com.gustler.backend.quota.api.CallQuota;
import com.gustler.backend.quota.api.CallQuotaPolicy;
import java.util.List;
import java.util.Map;
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
        List<String> clientAliases = configuration.gbisClientOptions(properties)
            .keys()
            .stream()
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

    @Test
    void 키를_다섯_개_이상_설정하면_호출_한도와_GBIS_클라이언트가_모두_같은_별칭으로_돌려_쓴다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, "fake-key-a", null, null, null,
            Map.of("b", "fake-key-b", "c", "fake-key-c", "d", "fake-key-d", "e", "fake-key-e", "f10", "fake-key-f10"),
            DAILY_LIMIT);
        CollectionRuntimeConfiguration configuration = new CollectionRuntimeConfiguration();

        // when
        List<String> quotaAliases = configuration.callQuotaPolicy(properties).locationKeyAliases();
        GbisClientOptions client = configuration.gbisClientOptions(properties);

        // then
        assertThat(quotaAliases).containsExactly(GbisKey.PRIMARY, "b", "c", "d", "e", "f10")
            .isEqualTo(client.keys()
                .stream()
                .map(GbisKey::alias)
                .toList());
        assertThat(client.serviceKeyOf("f10")).isEqualTo("fake-key-f10");
    }

    @Test
    void 키별_한도를_주면_호출_한도가_그_키만_그_한도로_센다() {
        // given
        GbisProperties properties = new GbisProperties(BASE_URL, "fake-key-a", null, null, null,
            Map.of("e", "fake-key-e"), DAILY_LIMIT, Map.of("e", 1_000));
        CollectionRuntimeConfiguration configuration = new CollectionRuntimeConfiguration();

        // when
        CallQuotaPolicy policy = configuration.callQuotaPolicy(properties);

        // then
        assertThat(policy.limitOf(CallQuota.BUS_LOCATION, "e")).isEqualTo(1_000);
        assertThat(policy.limitOf(CallQuota.BUS_LOCATION, GbisKey.PRIMARY))
            .isEqualTo(DAILY_LIMIT);
    }
}
