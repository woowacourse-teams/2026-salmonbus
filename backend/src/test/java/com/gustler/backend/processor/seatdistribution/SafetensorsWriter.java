package com.gustler.backend.processor.seatdistribution;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 테스트가 safetensors 파일을 만든다.
 *
 * <p>읽는 쪽을 재려면 쓰는 쪽이 있어야 한다. 여기서 일부러 망가뜨린 파일도 만든다.
 */
final class SafetensorsWriter {

    private final Map<String, Written> tensors = new LinkedHashMap<>();

    private record Written(
        TensorDataType dataType,
        int[] shape,
        double[] values
    ) {
    }

    SafetensorsWriter with(
        String name,
        TensorDataType dataType,
        int[] shape,
        double[] values
    ) {
        tensors.put(name, new Written(dataType, shape, values));
        return this;
    }

    SafetensorsWriter without(
        String name
    ) {
        tensors.remove(name);
        return this;
    }

    byte[] toBytes() {
        List<String> entries = new ArrayList<>();
        ByteArrayOutputStream values = new ByteArrayOutputStream();
        int offset = 0;
        for (Map.Entry<String, Written> entry : tensors.entrySet()) {
            Written written = entry.getValue();
            byte[] encoded = encode(written);
            entries.add("\"%s\":{\"dtype\":\"%s\",\"shape\":%s,\"data_offsets\":[%d,%d]}".formatted(
                entry.getKey(),
                written.dataType().tensorName(),
                shapeText(written.shape()),
                offset,
                offset + encoded.length));
            values.writeBytes(encoded);
            offset += encoded.length;
        }
        byte[] header = ("{" + String.join(",", entries) + "}").getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.writeBytes(ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(header.length).array());
        file.writeBytes(header);
        file.writeBytes(values.toByteArray());
        return file.toByteArray();
    }

    private static byte[] encode(
        Written written
    ) {
        ByteBuffer buffer = ByteBuffer
            .allocate(written.values().length * written.dataType().byteCount())
            .order(ByteOrder.LITTLE_ENDIAN);
        for (final double value : written.values()) {
            if (written.dataType() == TensorDataType.FLOAT64) {
                buffer.putDouble(value);
            } else {
                buffer.put((byte) value);
            }
        }
        return buffer.array();
    }

    private static String shapeText(
        int[] shape
    ) {
        List<String> axes = new ArrayList<>();
        for (final int length : shape) {
            axes.add(String.valueOf(length));
        }
        return "[" + String.join(",", axes) + "]";
    }
}
