package com.gustler.backend.quota.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.gustler.backend.quota.domain.CallQuota;
import java.util.EnumMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CallQuotaPolicyTest {

    private static final int LOCATION_LIMIT = 10_000;
    private static final int ROUTE_LIMIT = 500;
    private static final int SAME_LIMIT = 3;

    @Test
    void API마다_받은_한도를_그대로_돌려준다() {
        // given
        CallQuotaPolicy policy = new CallQuotaPolicy(limits(LOCATION_LIMIT, ROUTE_LIMIT));

        // then
        assertThat(policy.limitOf(CallQuota.BUS_LOCATION)).isEqualTo(LOCATION_LIMIT);
        assertThat(policy.limitOf(CallQuota.BUS_ROUTE)).isEqualTo(ROUTE_LIMIT);
    }

    @Test
    void 한도가_같은_설정은_모든_API에_같은_값을_준다() {
        // given
        CallQuotaPolicy policy = CallQuotaPolicy.sameForEveryApi(SAME_LIMIT);

        // then
        assertThat(policy.dailyLimits()).containsOnlyKeys(CallQuota.values());
        assertThat(policy.dailyLimits().values()).containsOnly(SAME_LIMIT);
    }

    @Test
    void 한도를_안_적은_API가_있으면_거절한다() {
        // given
        Map<CallQuota, Integer> onlyLocation = new EnumMap<>(CallQuota.class);
        onlyLocation.put(CallQuota.BUS_LOCATION, LOCATION_LIMIT);

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> new CallQuotaPolicy(onlyLocation));
    }

    @Test
    void 한도가_1보다_작으면_거절한다() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> new CallQuotaPolicy(limits(LOCATION_LIMIT, 0)));
        assertThatIllegalArgumentException().isThrownBy(() -> new CallQuotaPolicy(limits(-1, ROUTE_LIMIT)));
    }

    @Test
    void 만들고_난_뒤에는_한도를_바꿀_수_없다() {
        // given
        Map<CallQuota, Integer> given = limits(LOCATION_LIMIT, ROUTE_LIMIT);
        CallQuotaPolicy policy = new CallQuotaPolicy(given);

        // when
        given.put(CallQuota.BUS_ROUTE, LOCATION_LIMIT);

        // then
        assertThat(policy.limitOf(CallQuota.BUS_ROUTE)).isEqualTo(ROUTE_LIMIT);
        assertThatThrownBy(() -> policy.dailyLimits().put(CallQuota.BUS_ROUTE, LOCATION_LIMIT))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    private static Map<CallQuota, Integer> limits(final int location, final int route) {
        Map<CallQuota, Integer> limits = new EnumMap<>(CallQuota.class);
        limits.put(CallQuota.BUS_LOCATION, location);
        limits.put(CallQuota.BUS_ROUTE, route);
        return limits;
    }
}
