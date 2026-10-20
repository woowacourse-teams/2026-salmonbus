package com.gustler.backend.forecasting.infrastructure.bundle;

/**
 * 계수 파일에서 읽은 배열 하나.
 *
 * <p>값을 일차원 배열로 보관하고 각 값의 위치는 배열 크기로 계산한다. 다차원 배열로 구성하면
 * 노선 두 개 · 거리 열두 개 · 방향 두 개 · 묶음 아홉 개마다 객체가 생긴다.
 * <p>번들 파서가 새로 만든 shape·values 배열의 소유권을 넘겨받는 내부 계산 버퍼다.
 * 이 패키지에서만 읽고 적재 이후에는 수정하지 않으며 외부 입출력 DTO로 공개하지 않는다.
 */
record Tensor(
    String name,
    TensorDataType dataType,
    int[] shape,
    double[] values
) {
    Tensor {
        final int expected = valueCountOf(shape);
        if (values.length != expected) {
            throw new BundleRejectedException(
                "%s 의 값 개수가 크기와 안 맞는다: %d, %d".formatted(name, values.length, expected));
        }
    }

    /**
     * 배열 크기를 곱해 값의 개수를 계산한다.
     *
     * <p>{@code long} 으로 곱한다. {@code int} 로 곱하면 계수 파일에 명시된 크기가 클 때 오버플로로
     * 실제보다 작은 개수가 계산되며, 그 개수로 파일을 읽게 된다.
     */
    static int valueCountOf(
        int[] shape
    ) {
        long count = 1;
        for (final int length : shape) {
            count *= length;
            if (count > Integer.MAX_VALUE) {
                throw new BundleRejectedException("배열이 " + count + " 칸이라 못 읽는다");
            }
        }
        return (int) count;
    }

    /** 앞쪽 축의 인덱스를 지정해 마지막 축의 값 전체를 반환한다. */
    double[] rowAt(
        int... axes
    ) {
        if (axes.length != shape.length - 1) {
            throw new IllegalArgumentException(
                "%s 는 축이 %d 개라 %d 개를 짚어야 한 줄이 남는다: %d 개를 짚었다"
                .formatted(name, shape.length, shape.length - 1, axes.length)
            );
        }
        final int length = shape[shape.length - 1];
        double[] row = new double[length];
        System.arraycopy(values, offsetOf(axes) * length, row, 0, length);
        return row;
    }

    double valueAt(
        int... axes
    ) {
        if (axes.length != shape.length) {
            throw new IllegalArgumentException(
                "%s 는 축이 %d 개다: %d 개를 짚었다".formatted(name, shape.length, axes.length));
        }
        return values[offsetOf(axes)];
    }

    private int offsetOf(
        int[] axes
    ) {
        int offset = 0;
        for (int index = 0; index < axes.length; index++) {
            if (axes[index] < 0 || axes[index] >= shape[index]) {
                throw new IllegalArgumentException(
                    "%s 의 %d 번째 축은 %d 칸인데 %d 을 짚었다"
                    .formatted(name, index, shape[index], axes[index])
                );
            }
            offset = offset * shape[index] + axes[index];
        }
        return offset;
    }
}
