package com.gustler.backend.processor.a18;

/**
 * 계수 파일에서 꺼낸 배열 하나.
 *
 * <p>값은 평평하게 한 줄로 들고, 몇 번째 자리인지는 크기로 계산한다. 다차원 배열로 펴 두면
 * 노선 두 개 · 거리 열두 개 · 방향 두 개 · 묶음 아홉 개마다 객체가 생긴다.
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

    static int valueCountOf(
        int[] shape
    ) {
        int count = 1;
        for (final int length : shape) {
            count *= length;
        }
        return count;
    }

    /** 앞쪽 축을 하나씩 짚어 들어간 뒤 남은 축 하나를 통째로 꺼낸다. */
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
