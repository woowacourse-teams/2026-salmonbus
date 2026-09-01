package com.gustler.backend.processor.seatdistribution;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리에 올라온 계수 묶음을 <b>신원별로</b> 든다.
 *
 * <p>자리 하나를 두고 갈아 끼우지 않는다. 갈아 끼우면 <b>올리다 실패한 묶음이 도는 묶음을
 * 밀어낸다.</b> B 를 적재하고 승격에 실패하면 DB 는 A 인데 이 자리에는 B 가 남아, 신원이 안 맞아
 * 예보가 통째로 멈춘다. 그 사이 A 로 잘 돌던 batch 까지 같이 멈춘다.
 *
 * <p>계수 묶음 요약값을 열쇠로 쓴다. 그래서 <b>도는 배포가 가리키는 요약값으로 찾으면</b> 실패한
 * 적재가 아무리 남아 있어도 안 골라진다. 되돌리는 자리가 코드가 아니라 열쇠에 있다.
 *
 * <p>이미 꺼내 간 묶음은 여기서 지워도 안 사라진다. 그것을 쓰는 batch 가 예측기를 붙들고 있어서다.
 * 그래서 <b>진행 중 batch 가 쓰던 계수가 승격 도중에 사라지지 않는다.</b>
 *
 * <p>올린 묶음은 안 지운다. 적재는 사람이 한 번씩 하는 일이라 자주 늘지 않고, 하나가 235KB 쯤이다.
 * 언제 지워도 되는지는 batch 가 언제 끝나는지를 알아야 정할 수 있는데, 예보 경로에 잇는 자리가
 * 아직 없어서 지금은 모른다. 잇는 PR 에서 정한다.
 */
public final class LoadedBundleHolder {

    private final Map<String, LoadedBundle> byBundleDigest = new ConcurrentHashMap<>();

    /** 도는 배포가 가리키는 요약값으로 찾는다. 없으면 비어 있다. */
    public Optional<LoadedBundle> find(
        String bundleDigest
    ) {
        return Optional.ofNullable(byBundleDigest.get(bundleDigest));
    }

    public void add(
        LoadedBundle bundle
    ) {
        byBundleDigest.put(bundle.bundleDigest(), bundle);
    }

    /** 올리지 못한 적재를 걷어낸다. 안 걷어내도 열쇠 때문에 안 골라지지만 남겨 둘 이유가 없다. */
    public void remove(
        String bundleDigest
    ) {
        byBundleDigest.remove(bundleDigest);
    }

    public int size() {
        return byBundleDigest.size();
    }
}
