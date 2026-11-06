package com.gustler.backend.routecatalog.application;

import com.gustler.backend.routecatalog.api.LoadStopWalkDistances;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDataset;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDatasetReader;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDistanceStore;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 도보 거리 파일을 DB와 맞춘다. 지금 적재된 묶음과 같으면 다시 쓰지 않고, 다르면 통째로 바꾼다.
 */
@Component
public class StopWalkDistanceLoader implements LoadStopWalkDistances {
    private final StopWalkDatasetReader reader;
    private final StopWalkDistanceStore store;

    public StopWalkDistanceLoader(StopWalkDatasetReader reader, StopWalkDistanceStore store) {
        this.reader = reader;
        this.store = store;
    }

    @Override
    @Transactional(readOnly = false)
    public Result load(Path distances, Path metadata, OffsetDateTime loadedAt) {
        StopWalkDataset dataset = reader.read(distances, metadata);
        if (store.isCurrent(dataset.checksum())) {
            return new Result(Status.UNCHANGED, dataset.checksum(), dataset.pairs().size());
        }
        store.replace(dataset, loadedAt);
        return new Result(Status.LOADED, dataset.checksum(), dataset.pairs().size());
    }
}
