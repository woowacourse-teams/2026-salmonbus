package com.gustler.backend.worker.startup;

import com.gustler.backend.routecatalog.api.LoadStopWalkDistances;
import com.gustler.backend.routecatalog.api.LoadStopWalkDistances.Result;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Worker 기동 후 서버 디렉터리에 둔 정류장 쌍 도보 거리 파일을 DB에 맞춘다. 모델 계수 파일과 같이 사람이 갖다 놓는 파일이다.
 *
 * <p>디렉터리를 비워 두거나 파일이 없으면 건너뛰고, 노선 카탈로그 구성이 없는 실행에서도 건너뛴다.
 * 지금 적재된 묶음과 같으면 쓰지 않는다. 적재에 실패해도 수집·예보는 계속 돌고, 조회는 이전 거리로 응답한다.
 */
@Component
public class StopWalkDistanceStartupLoader {
    static final String DISTANCES = "stop-walk-distances.csv.gz";
    static final String METADATA = "stop-walk-distances.json";
    private static final Logger LOGGER = LoggerFactory.getLogger(StopWalkDistanceStartupLoader.class);
    private final ObjectProvider<LoadStopWalkDistances> loader;
    private final Clock clock;
    private final String directory;

    public StopWalkDistanceStartupLoader(
        ObjectProvider<LoadStopWalkDistances> loader,
        Clock clock,
        @Value("${walking.distances.directory:}") String directory
    ) {
        this.loader = loader;
        this.clock = clock;
        this.directory = directory;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadPlacedDistances() {
        LoadStopWalkDistances load = loader.getIfAvailable();
        if (load == null || directory.isBlank()) {
            return;
        }
        Path distances = Path.of(directory, DISTANCES);
        Path metadata = Path.of(directory, METADATA);
        if (!Files.isRegularFile(distances) || !Files.isRegularFile(metadata)) {
            LOGGER.warn("event=stop_walk_distances_missing directory={}", directory);
            return;
        }
        try {
            Result result = load.load(distances, metadata, OffsetDateTime.now(clock));
            LOGGER.info("event=stop_walk_distances_loaded result={} checksum={} pairCount={}", result.status(),
                result.checksum(), result.pairCount());
        } catch (RuntimeException failure) {
            LOGGER.error("event=stop_walk_distances_load_failed exceptionType={}", failure.getClass().getSimpleName(),
                failure);
        }
    }
}
