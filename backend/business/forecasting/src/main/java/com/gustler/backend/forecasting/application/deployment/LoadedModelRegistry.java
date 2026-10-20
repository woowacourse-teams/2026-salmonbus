package com.gustler.backend.forecasting.application.deployment;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리에 적재된 계수 묶음을 <b>식별 정보별로</b> 보관한다.
 *
 * <p>단일 저장 위치의 묶음을 교체하지 않는다. 교체하면 <b>실패한 적재가 활성 묶음을
 * 덮어쓴다.</b> B를 적재하고 승격에 실패하면 DB는 A인데 메모리에는 B가 남아 식별 정보가 일치하지
 * 않으므로 예보가 모두 중단된다. 그 사이 A를 사용하던 batch도 함께 중단된다.
 *
 * <p>계수 묶음 요약값을 조회 키로 쓴다. 따라서 <b>활성 배포가 가리키는 요약값으로 조회하면</b> 실패한
 * 적재가 남아 있어도 선택하지 않는다. 복구 시 선택은 코드가 아니라 조회 키로 결정한다.
 *
 * <p>이미 조회한 묶음은 여기서 지워도 사용하는 batch가 예측기 참조를 유지하므로 사라지지 않는다.
 * 따라서 <b>진행 중 batch가 쓰던 계수가 승격 도중에 사라지지 않는다.</b>
 *
 * <p>적재한 묶음은 삭제하지 않는다. 사람이 수동으로 적재하므로 자주 늘지 않고 하나가 235KB 쯤이다.
 * 삭제 가능한 시점은 batch가 언제 끝나는지 알아야 정할 수 있지만, 예보 경로에 이를 확인할 연결이
 * 아직 없어 지금은 알 수 없다. 연결하는 PR에서 정한다.
 */
public final class LoadedModelRegistry {
    private final Map<String, ModelRelease> byBundleDigest = new ConcurrentHashMap<>();

    /** 활성 배포가 가리키는 요약값으로 조회한다. 없으면 빈 결과를 반환한다. */
    public Optional<ModelRelease> find(
        String bundleDigest
    ) {
        return Optional.ofNullable(byBundleDigest.get(bundleDigest));
    }

    public void add(
        ModelRelease bundle
    ) {
        byBundleDigest.put(bundle.bundleDigest(), bundle);
    }

    /** 실패한 적재를 제거한다. 조회 키가 달라 선택되지 않지만 불필요하게 남겨 두지 않는다. */
    public void remove(
        String bundleDigest
    ) {
        byBundleDigest.remove(bundleDigest);
    }

    public int size() {
        return byBundleDigest.size();
    }
}
