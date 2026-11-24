package com.gustler.backend.forecasting.api.model;

import java.util.List;

/** 파일 검증만 수행한다. 배포 저장이나 활성화는 하지 않는다. */
public interface InspectModelBundle {

    Inspection inspect(String directory);

    record Inspection(
        String releaseId,
        String bundleDigest,
        String featureContractVersion,
        String dataThrough,
        List<String> routes
    ) {

        public Inspection {
            routes = List.copyOf(routes);
        }
    }
}
