package com.gustler.backend.processor.seatdistribution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계수 파일이 어디 있는지.
 *
 * <p>비워 두면 아무것도 안 읽는다. 계수가 없는 동안의 정상 상태이고, 그때 예보 배치는 batch 를
 * 하나도 안 연다. 반쯤 채운 예보를 남기는 것보다 안 여는 편이 낫다.
 *
 * @param directory {@code manifest.json} 과 {@code weights.safetensors} 가 든 디렉터리
 */
@ConfigurationProperties("model.bundle")
public record ModelBundleProperties(
    String directory
) {

    public boolean configured() {
        return directory != null && !directory.isBlank();
    }
}
