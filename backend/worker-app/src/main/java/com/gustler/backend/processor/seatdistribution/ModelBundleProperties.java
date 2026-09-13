package com.gustler.backend.processor.seatdistribution;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 계수 파일이 어디 있는지.
 *
 * <p>비워 두면 아무것도 안 읽는다. 계수가 없는 동안의 정상 상태이고, 그때 예보 배치는 batch 를
 * 하나도 안 연다. 반쯤 채운 예보를 남기는 것보다 안 여는 편이 낫다.
 *
 * <p>계수를 갈아 끼우는 것은 <b>사람이 정하는 일</b>이다. 파일만 바꿔 놓고 재기동했다고 배포가
 * 따라 바뀌면, 재기동한 사람이 배포를 바꾼 줄 모르는 채로 바뀐다. 그래서 도는 배포와 신원이 다른
 * 파일은 {@code promoteOnStart} 를 켤 때만 올린다.
 *
 * <p>A 에서 B 로 가는 것도, B 에서 A 로 되돌리는 것도 같은 자리를 쓴다. 되돌릴 때는 자리를 옛
 * 파일로 돌리고 같은 스위치를 켠다. <b>되돌리기가 곧 다시 올리기다.</b>
 *
 * @param directory {@code manifest.json} 과 {@code weights.safetensors} 가 든 디렉터리
 * @param promoteOnStart 도는 배포와 신원이 달라도 기동할 때 올릴지. 켠 채로 두면 재기동마다 올린다
 */
@ConfigurationProperties("model.bundle")
public record ModelBundleProperties(
    String directory,
    boolean promoteOnStart
) {

    public boolean configured() {
        return directory != null && !directory.isBlank();
    }
}
