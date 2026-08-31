package com.gustler.backend.processor.a18;

import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리에 올라온 계수 묶음을 든다.
 *
 * <p>바꿔 끼울 때 통째로 바꾼다. 이미 꺼내 간 묶음은 안 건드린다. 그래서 승격 도중에도
 * <b>진행 중 batch 가 쓰던 계수가 사라지지 않는다.</b> 그 batch 가 끝나면 참조가 끊기고
 * 그때 회수된다.
 */
public final class LoadedBundleHolder {

    private final AtomicReference<LoadedBundle> current = new AtomicReference<>();

    public Optional<LoadedBundle> current() {
        return Optional.ofNullable(current.get());
    }

    public void replaceWith(
        LoadedBundle bundle
    ) {
        current.set(bundle);
    }
}
