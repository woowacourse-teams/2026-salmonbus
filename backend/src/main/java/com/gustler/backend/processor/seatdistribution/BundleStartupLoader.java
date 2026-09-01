package com.gustler.backend.processor.seatdistribution;

import com.gustler.backend.processor.ActiveModelDeployment;
import com.gustler.backend.processor.ModelDeploymentRepository;
import java.nio.file.Path;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 기동할 때 계수 파일을 읽어 예보가 돌 수 있게 만든다.
 *
 * <p>세 갈래로 갈린다.
 *
 * <ul>
 *   <li><b>도는 배포가 이 파일과 같은 신원이면</b> 계수만 메모리에 올린다. 다시 띄운 것뿐이라
 *       배포 행을 새로 만들지 않는다. 만들면 재기동할 때마다 배포가 하나씩 는다
 *   <li><b>도는 배포가 아예 없으면</b> 적재해서 올린다. 처음 켜는 자리다
 *   <li><b>도는 배포가 있는데 신원이 다르면</b> 올리지 않는다. 계수를 바꾸는 것은 재기동이 아니라
 *       사람이 하는 일이라, 파일을 갈아 끼우고 재기동했다고 자동으로 승격하면 배포가 조용히 바뀐다.
 *       이때 예보는 안 나간다. 신원이 안 맞아 아무것도 안 골라지기 때문이다
 * </ul>
 *
 * <p>트랜잭션 경계가 여기 있다. {@link BundleActivation} 은 빈이 아니라 자기 애너테이션이 안 먹는다.
 * 적재 영수증과 승격이 한 transaction 에서 끝나야 영수증만 남고 승격이 빠진 행이 안 생긴다.
 */
@Slf4j
@Component
public class BundleStartupLoader {

    private final ModelBundleProperties properties;
    private final ModelDeploymentRepository deployments;
    private final LoadedBundleHolder bundles;
    private final BundleActivation activation;

    public BundleStartupLoader(
        ModelBundleProperties properties,
        ModelDeploymentRepository deployments,
        LoadedBundleHolder bundles,
        BundleActivation activation
    ) {
        this.properties = properties;
        this.deployments = deployments;
        this.bundles = bundles;
        this.activation = activation;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void loadConfiguredBundle() {
        if (!properties.configured()) {
            log.info("계수 파일 자리가 안 잡혀 있다. 예보는 계수가 붙을 때까지 batch 를 안 연다");
            return;
        }
        try {
            load(BundleFiles.under(Path.of(properties.directory())));
        } catch (BundleRejectedException error) {
            log.error("계수 파일을 못 올렸다. 예보는 batch 를 안 연다: {}", error.getMessage());
        }
    }

    private void load(
        BundleFiles files
    ) {
        Optional<ActiveModelDeployment> active = deployments.findActive();
        if (active.isEmpty()) {
            log.info("도는 배포가 없어 계수 파일을 올린다: 배포 {}", activation.activate(files));
            return;
        }

        LoadedBundle bundle = LoadedBundle.from(files);
        if (active.get().bundleDigest().equals(bundle.bundleDigest())) {
            bundles.add(bundle);
            log.info("도는 배포의 계수를 다시 올렸다: {}", bundle.releaseId());
            return;
        }
        if (!properties.promoteOnStart()) {
            log.warn("도는 배포와 계수 파일의 신원이 다르다. model.bundle.promote-on-start 가 꺼져 있어 "
                + "안 올린다. 도는 배포 {}, 파일 {}", active.get().releaseId(), bundle.releaseId());
            return;
        }
        log.warn("사람이 켜 둔 스위치로 계수를 갈아 끼운다. {} 에서 {} 로, 배포 {}",
            active.get().releaseId(), bundle.releaseId(), activation.activate(files));
    }
}
