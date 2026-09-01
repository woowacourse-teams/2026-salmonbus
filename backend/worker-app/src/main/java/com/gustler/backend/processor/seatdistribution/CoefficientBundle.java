package com.gustler.backend.processor.seatdistribution;

import java.util.List;
import java.util.Map;

/**
 * 검사를 통과한 계수 묶음. 노선과 예보 거리로 계수를 꺼낸다.
 *
 * <p>노선 목록의 <b>순서가 계수 배열의 첫 축</b>이다. 목록을 뒤집으면 1650 계수로 3330 을
 * 예보하게 되고, 값은 정상으로 보인다. 그래서 순서까지 검사한다.
 *
 * <p>바뀌지 않는다. 승격으로 계수가 바뀌면 새 묶음이 생기지 이 묶음의 값이 갈리지 않는다.
 */
final class CoefficientBundle implements CoefficientLookup {

    private final BundleManifest manifest;
    private final Map<BundleTensor, Tensor> tensors;

    CoefficientBundle(
        BundleManifest manifest,
        Map<BundleTensor, Tensor> tensors
    ) {
        this.manifest = manifest;
        this.tensors = Map.copyOf(tensors);
    }

    BundleManifest manifest() {
        return manifest;
    }

    @Override
    public HorizonCoefficients at(
        String modelRoute,
        final int stopsAhead
    ) {
        final int route = routeAxisOf(modelRoute);
        final int horizon = horizonAxisOf(stopsAhead);
        return new HorizonCoefficients(
            tensors.get(BundleTensor.FULL_CHANCE).rowAt(route, horizon),
            tensors.get(BundleTensor.ANCHOR).rowAt(route, horizon),
            tensors.get(BundleTensor.RESIDUAL_SIGN)
                .rowAt(route, horizon, ResidualSignHead.SAME_SEATS.ordinal()),
            tensors.get(BundleTensor.RESIDUAL_SIGN)
                .rowAt(route, horizon, ResidualSignHead.BELOW_ANCHOR.ordinal()),
            binsAt(route, horizon),
            binFittedAt(route, horizon));
    }

    private int routeAxisOf(
        String modelRoute
    ) {
        final int axis = manifest.routes().indexOf(modelRoute);
        if (axis < 0) {
            throw new IllegalArgumentException(
                "이 계수 묶음이 안 담는 노선이다: %s, 담는 노선은 %s"
                    .formatted(modelRoute, manifest.routes())
            );
        }
        return axis;
    }

    private int horizonAxisOf(
        final int stopsAhead
    ) {
        final int axis = manifest.horizonStops().indexOf(stopsAhead);
        if (axis < 0) {
            throw new IllegalArgumentException(
                "이 계수 묶음이 안 담는 예보 거리다: %d정류장 앞, 담는 거리는 %s"
                    .formatted(stopsAhead, manifest.horizonStops())
            );
        }
        return axis;
    }

    private double[][][] binsAt(
        final int route,
        final int horizon
    ) {
        Tensor tensor = tensors.get(BundleTensor.RESIDUAL_BIN);
        double[][][] bins = new double[ResidualDirection.values().length][][];
        for (ResidualDirection direction : ResidualDirection.values()) {
            bins[direction.ordinal()] = new double[HorizonCoefficients.BIN_COUNT][];
            for (int bin = 0; bin < HorizonCoefficients.BIN_COUNT; bin++) {
                bins[direction.ordinal()][bin] =
                    tensor.rowAt(route, horizon, direction.ordinal(), bin);
            }
        }
        return bins;
    }

    private boolean[][] binFittedAt(
        final int route,
        final int horizon
    ) {
        Tensor tensor = tensors.get(BundleTensor.RESIDUAL_BIN_FITTED);
        boolean[][] fitted = new boolean[ResidualDirection.values().length][];
        for (ResidualDirection direction : ResidualDirection.values()) {
            fitted[direction.ordinal()] = new boolean[HorizonCoefficients.BIN_COUNT];
            for (int bin = 0; bin < HorizonCoefficients.BIN_COUNT; bin++) {
                fitted[direction.ordinal()][bin] =
                    tensor.valueAt(route, horizon, direction.ordinal(), bin) == 1.0;
            }
        }
        return fitted;
    }

    List<String> routes() {
        return manifest.routes();
    }
}
