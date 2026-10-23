package com.gustler.backend.worker.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계수 파일의 위치와 기동 시 적재 설정.
 *
 * <p>비워 두면 아무것도 읽지 않는다. 계수가 없는 동안은 정상 상태이며 이때 예보 배치는 batch 를
 * 하나도 열지 않는다. 일부만 채운 예보를 남기는 것보다 열지 않는 편이 낫다.
 *
 * <p>계수 교체는 <b>사람이 정하는 일</b>이다. 파일을 바꾼 뒤 재기동만으로 배포까지 바뀌면
 * 재기동한 사람이 배포 변경을 인지하지 못한다. 그래서 활성 배포와 식별 정보가 다른
 * 파일은 {@code promoteOnStart} 를 켤 때만 적재한다.
 *
 * <p>A 에서 B 로 전환할 때와 B 에서 A 로 되돌릴 때 모두 같은 위치를 쓴다. 되돌릴 때는 해당 위치의 파일을
 * 이전 파일로 바꾸고 같은 스위치를 켠다. <b>되돌리기도 다시 적재하는 방식이다.</b>
 *
 * @param directory {@code manifest.json} 과 {@code weights.safetensors} 가 든 디렉터리
 * @param promoteOnStart 활성 배포와 식별 정보가 달라도 기동할 때 적재할지. 켠 채로 두면 재기동마다 적재한다
 */
@ConfigurationProperties("model.bundle")
public record ModelBundleProperties(
    String directory,
    boolean promoteOnStart
) {
}
