package com.gustler.backend.worker.station;

import com.gustler.backend.stations.api.ImportStationSeed;
import com.gustler.backend.stations.configuration.StationSeedConfiguration;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 지정한 로컬 공식 파일만 설치한다. 앱 기동 때 외부 파일을 자동 다운로드하지 않는다.
 *
 * <p>설치에 실패하거나 다른 인스턴스가 설치 중이어도 worker 기동은 멈추지 않는다. 기존 명부를 계속 쓴다.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "stations.seed", name = "enabled", havingValue = "true")
@Import(StationSeedConfiguration.class)
public class StationSeedRuntimeConfiguration {

    private static final Logger LOGGER = LoggerFactory.getLogger(StationSeedRuntimeConfiguration.class);

    @Bean
    ApplicationRunner stationSeedLoader(
        ImportStationSeed importer,
        @Value("${stations.seed.path}") Path path,
        @Value("${stations.seed.encoding:UTF-8}") String encoding
    ) {
        return args -> install(importer, path, encoding);
    }

    private static void install(ImportStationSeed importer, Path path, String encoding) {
        Optional<ImportStationSeed.Result> installed;
        try {
            installed = importer.importFile(path, Charset.forName(encoding));
        } catch (RuntimeException failure) {
            // 예외 메시지에 파일 경로·DB 접속 정보가 섞일 수 있어 예외 형식만 남긴다.
            LOGGER.warn("event=station_seed_install_failed exceptionType={}", failure.getClass().getSimpleName());
            return;
        }
        if (installed.isEmpty()) {
            LOGGER.info("event=station_seed_skipped reason=LOCKED_BY_OTHER_INSTANCE");
            return;
        }
        ImportStationSeed.Result result = installed.get();
        LOGGER.info(
            "event=station_seed_installed dataDate={} stations={} changed={}",
            result.dataDate(),
            result.stationCount(),
            result.changed()
        );
    }
}
