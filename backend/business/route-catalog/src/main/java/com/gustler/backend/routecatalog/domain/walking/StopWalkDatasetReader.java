package com.gustler.backend.routecatalog.domain.walking;

import java.nio.file.Path;

/**
 * 도보 거리 파일과 메타데이터를 읽어 검증한 묶음으로 만든다.
 */
public interface StopWalkDatasetReader {
    StopWalkDataset read(Path distances, Path metadata);
}
