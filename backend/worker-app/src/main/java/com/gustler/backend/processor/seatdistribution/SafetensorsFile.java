package com.gustler.backend.processor.seatdistribution;

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
 *
 * <p>머리말에서 값을 꺼내기 전에 항목이 있는지와 자료형을 본다. 안 보고 꺼내면 항목이 빠졌을 때
 * {@link NullPointerException} 이 나서 {@link BundleRejectedException} 을 안 거친다. 그러면 적재가
 * "거절했다" 가 아니라 "터졌다" 로 끝나서, 기동이 통째로 멈춘다.
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
        require(declaration.isObject(), name + " 의 선언이 객체가 아니다");
        TensorDataType dataType = TensorDataType.of(textAt(declaration, "dtype", name));
        int[] shape = shapeOf(arrayAt(declaration, "shape", name), name);
        JsonNode offsets = arrayAt(declaration, "data_offsets", name);
        require(offsets.size() == 2,
            "%s 의 값 구간은 시작과 끝 둘이다: %d개".formatted(name, offsets.size()));
        final long from = wholeNumberOf(offsets.get(0), name + " 의 값 구간 시작");
        final long until = wholeNumberOf(offsets.get(1), name + " 의 값 구간 끝");
        if (from < 0 || until < from || until > (long) content.length - valuesFrom) {
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
        JsonNode node,
        String name
    ) {
        int[] shape = new int[node.size()];
        for (int index = 0; index < shape.length; index++) {
            final long axis = wholeNumberOf(node.get(index), name + " 의 크기");
            if (axis <= 0 || axis > Integer.MAX_VALUE) {
                throw new BundleRejectedException(
                    "%s 의 크기는 1 이상이다: %d".formatted(name, axis));
            }
            shape[index] = (int) axis;
        }
        return shape;
    }

    private static JsonNode fieldAt(
        JsonNode declaration,
        String field,
        String name
    ) {
        JsonNode value = declaration.get(field);
        require(value != null, "%s 의 선언에 %s 가 없다".formatted(name, field));
        return value;
    }

    private static String textAt(
        JsonNode declaration,
        String field,
        String name
    ) {
        JsonNode value = fieldAt(declaration, field, name);
        require(value.isString(), "%s 의 %s 가 문자열이 아니다".formatted(name, field));
        return value.stringValue();
    }

    private static JsonNode arrayAt(
        JsonNode declaration,
        String field,
        String name
    ) {
        JsonNode value = fieldAt(declaration, field, name);
        require(value.isArray(), "%s 의 %s 가 목록이 아니다".formatted(name, field));
        return value;
    }

    /** 정수만 받는다. 문자열이나 소수를 정수로 바꿔 읽지 않는다. */
    private static long wholeNumberOf(
        JsonNode value,
        String where
    ) {
        require(value.isIntegralNumber() && value.canConvertToLong(), where + " 가 정수가 아니다");
        return value.longValue();
    }

    private static void require(
        final boolean satisfied,
        String detail
    ) {
        if (!satisfied) {
            throw new BundleRejectedException(detail);
        }
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
