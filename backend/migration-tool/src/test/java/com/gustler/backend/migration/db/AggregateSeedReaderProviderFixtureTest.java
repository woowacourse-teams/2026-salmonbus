package com.gustler.backend.migration.db;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AggregateSeedReaderProviderFixtureTest {

    @Test
    void readsTheExactProviderFixtureWithDecimalTotalsAndCanonicalOrder(
        @TempDir Path directory
    ) throws Exception {
        ProviderSeedFixture.Files files = ProviderSeedFixture.writeTo(directory);

        AggregateSeedPayload payload = files.reader().read(files.seed(), files.receipt());

        assertThat(payload.rows()).hasSize(100);
        assertThat(payload.rows()).filteredOn(row -> "1650".equals(row.modelRoute())).hasSize(50);
        assertThat(payload.rows()).filteredOn(row -> "3330".equals(row.modelRoute())).hasSize(50);
        assertThat(payload.canonicalRowsSha256()).isEqualTo(ProviderSeedFixture.ROWS_SHA256);
        assertThat(payload.primaryKeySha256()).isEqualTo(ProviderSeedFixture.PRIMARY_KEY_SHA256);
        assertThat(payload.aggregates().global().fillRateTotal().toPlainString())
            .isEqualTo("826.695249498400949544");
    }
}
