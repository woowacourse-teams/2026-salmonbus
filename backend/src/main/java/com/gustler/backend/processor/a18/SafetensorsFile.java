package com.gustler.backend.processor.a18;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * safetensors 형식 파일에서 배열을 꺼낸다.
 *
 * <p>파일 생김새는 셋으로 나뉜다. 앞 8바이트가 머리말의 길이이고(작은 끝 먼저), 그다음이 그
 * 길이만큼의 JSON 머리말이고, 그 뒤가 값이다. 머리말은 배열 이름마다 자료형 · 크기 ·
 * 값이 놓인 구간을 적는다.
 *
 * <p>이 파일이 계수를 담는 형식이라 <b>믿고 읽지 않는다.</b> 머리말이 적은 구간이 파일 밖을
 * 가리키거나 크기와 안 맞으면 거기서 멈춘다.
 */
final class SafetensorsFile {

    /** 머리말 길이를 적는 앞자리 크기. */
    private static final int HEADER_LENGTH_BYTES = 8;

    /** 머리말이 자기 정보를 담는 자리. 배열이 아니라서 건너뛴다. */
    private static final String METADATA_KEY = "__metadata__";

    private static final long LARGEST_HEADER_LENGTH = 100L * 1024 * 1024;

    private final Map<String, Tensor> tensors;

    private SafetensorsFile(
        Map<String, Tensor> tensors
    ) {
        this.tensors = tensors;
    }

    static SafetensorsFile readFrom(
        Path path
    ) {
        try {
            return of(Files.readAllBytes(path));
        } catch (IOException error) {
            throw new BundleRejectedException("계수 파일을 읽지 못했다: " + path, error);
        }
    }

    static SafetensorsFile of(
        byte[] content
    ) {
        if (content.length < HEADER_LENGTH_BYTES) {
            throw new BundleRejectedException(
                "계수 파일이 머리말 길이도 못 담을 만큼 짧다: " + content.length + "바이트");
        }
        final long headerLength = ByteBuffer.wrap(content, 0, HEADER_LENGTH_BYTES)
            .order(ByteOrder.LITTLE_ENDIAN)
            .getLong();
        if (headerLength < 0 || headerLength > LARGEST_HEADER_LENGTH
            || HEADER_LENGTH_BYTES + headerLength > content.length) {
            throw new BundleRejectedException(
                "계수 파일의 머리말 길이가 파일 크기를 넘는다: %d, %d"
                    .formatted(headerLength, content.length)
            );
        }
        return new SafetensorsFile(
            tensorsOf(content, (int) headerLength, HEADER_LENGTH_BYTES + (int) headerLength));
    }

    private static Map<String, Tensor> tensorsOf(
        byte[] content,
        final int headerLength,
        final int valuesFrom
    ) {
        JsonNode header = headerOf(content, headerLength);
        Map<String, Tensor> tensors = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : header.properties()) {
            if (METADATA_KEY.equals(entry.getKey())) {
                continue;
            }
            tensors.put(entry.getKey(), tensorOf(entry.getKey(), entry.getValue(), content, valuesFrom));
        }
        return tensors;
    }

    private static JsonNode headerOf(
        byte[] content,
        final int headerLength
    ) {
        try {
            return new ObjectMapper().readTree(
                new String(content, HEADER_LENGTH_BYTES, headerLength, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new BundleRejectedException("계수 파일의 머리말이 JSON 이 아니다", error);
        }
    }

    private static Tensor tensorOf(
        String name,
        JsonNode declaration,
        byte[] content,
        final int valuesFrom
    ) {
        TensorDataType dataType = TensorDataType.of(declaration.get("dtype").stringValue());
        int[] shape = shapeOf(declaration.get("shape"));
        JsonNode offsets = declaration.get("data_offsets");
        final long from = offsets.get(0).asLong();
        final long until = offsets.get(1).asLong();
        if (from < 0 || until < from || valuesFrom + until > content.length) {
            throw new BundleRejectedException(
                "%s 의 값 구간이 파일 밖을 가리킨다: %d, %d".formatted(name, from, until));
        }
        final long declared = (long) Tensor.valueCountOf(shape) * dataType.byteCount();
        if (until - from != declared) {
            throw new BundleRejectedException(
                "%s 의 값 구간 길이가 크기와 자료형에 안 맞는다: %d 바이트, %d 바이트"
                    .formatted(name, until - from, declared)
            );
        }
        return new Tensor(name, dataType, shape,
            valuesOf(content, valuesFrom + (int) from, Tensor.valueCountOf(shape), dataType));
    }

    private static int[] shapeOf(
        JsonNode node
    ) {
        int[] shape = new int[node.size()];
        for (int index = 0; index < shape.length; index++) {
            shape[index] = node.get(index).asInt();
            if (shape[index] <= 0) {
                throw new BundleRejectedException("배열의 크기는 1 이상이다: " + shape[index]);
            }
        }
        return shape;
    }

    private static double[] valuesOf(
        byte[] content,
        final int from,
        final int count,
        TensorDataType dataType
    ) {
        ByteBuffer buffer = ByteBuffer.wrap(content, from, count * dataType.byteCount())
            .order(ByteOrder.LITTLE_ENDIAN);
        double[] values = new double[count];
        for (int index = 0; index < count; index++) {
            values[index] = dataType == TensorDataType.FLOAT64
                ? buffer.getDouble()
                : Byte.toUnsignedInt(buffer.get());
        }
        return values;
    }

    boolean has(
        String name
    ) {
        return tensors.containsKey(name);
    }

    Tensor get(
        String name
    ) {
        Tensor tensor = tensors.get(name);
        if (tensor == null) {
            throw new BundleRejectedException("계수 파일에 그 배열이 없다: " + name);
        }
        return tensor;
    }

    Set<String> names() {
        return tensors.keySet();
    }
}
