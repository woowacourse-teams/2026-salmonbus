package com.gustler.backend.observations.configuration;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.persistence.Table;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.orm.jpa.persistenceunit.PersistenceManagedTypesScanner;

class ObservationSchemaMappingTest {
    @Test
    void 기동_시_배치와_관측_테이블을_스키마_검증_대상에_포함한다() {
        // given
        String[] packages = ObservationStorageConfiguration.class.getAnnotation(EntityScan.class).value();
        PersistenceManagedTypesScanner scanner = new PersistenceManagedTypesScanner(new DefaultResourceLoader());

        // when
        Set<String> tables = scanner.scan(packages).getManagedClassNames().stream()
            .map(this::tableOf)
            .collect(Collectors.toSet());

        // then
        assertThat(tables).contains("observation_batch", "vehicle_observation");
    }

    private String tableOf(String className) {
        try {
            return Class.forName(className).getAnnotation(Table.class).name();
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("스캔한 엔티티 클래스를 읽지 못했다", failure);
        }
    }
}
