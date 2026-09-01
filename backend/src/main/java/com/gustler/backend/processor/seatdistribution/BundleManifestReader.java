package com.gustler.backend.processor.seatdistribution;

import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * 설명 파일을 읽는다.
 *
 * <p>모르는 항목이 하나라도 있으면 거절한다. 새 항목이 늘었다는 것은 우리가 안 보는 규칙이
 * 번들에 들어갔다는 뜻이라, 지나치면 그 규칙이 안 지켜진 채로 예보가 나간다.
 *
 * <p>바이트를 엄격하게 UTF-8 로 읽는다. 느슨하게 읽으면 깨진 바이트가 물음표로 바뀌어
 * 특징 이름이 조용히 달라진다.
 */
final class BundleManifestReader {

    private static final Set<String> KNOWN_FIELDS = Set.of(
        "bundleSchemaVersion", "modelVersion", "releaseId", "featureContractVersion",
        "sourceCommit", "routeReference", "routes", "horizonStops",
        "featureNames", "normalizationConstants", "timeSlotSource", "capacityPolicy",
        "cellStatisticsPolicy", "tensors", "weightsDigest", "identityDigest", "goldenVectorDigest",
        "goldenVector", "dataThrough");

    private BundleManifestReader() {
    }

    static BundleManifest read(
        byte[] content
    ) {
        JsonNode root = parse(textOf(content));
        BundleCheck.MANIFEST_IS_UTF8_JSON.require(root.isObject(), "최상위가 객체가 아니다");
        rejectUnknownFields(root);
        return new BundleManifest(
            textAt(root, "bundleSchemaVersion"),
            textAt(root, "modelVersion"),
            textAt(root, "releaseId"),
            textAt(root, "featureContractVersion"),
            textAt(root, "sourceCommit"),
            textAt(root.get("routeReference"), "version"),
            textAt(root.get("routeReference"), "digest"),
            textsOf(root.get("routes")),
            numbersOf(root.get("horizonStops")),
            textsOf(root.get("featureNames")),
            constantsOf(root.get("normalizationConstants")),
            textAt(root, "timeSlotSource"),
            textAt(root, "capacityPolicy"),
            textAt(root, "cellStatisticsPolicy"),
            declarationsOf(root.get("tensors")),
            textAt(root, "weightsDigest"),
            textAt(root, "identityDigest"),
            textAt(root, "goldenVectorDigest"),
            goldenVectorOf(root.get("goldenVector")),
            textAt(root, "dataThrough"));
    }

    private static String textOf(
        byte[] content
    ) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(content))
                .toString();
        } catch (CharacterCodingException error) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("UTF-8 로 못 읽는 바이트가 있다");
        }
    }

    private static JsonNode parse(
        String text
    ) {
        try {
            return new ObjectMapper().readTree(text);
        } catch (RuntimeException error) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject(error.getMessage());
        }
    }

    private static void rejectUnknownFields(
        JsonNode root
    ) {
        for (Map.Entry<String, JsonNode> field : root.properties()) {
            BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD.require(
                KNOWN_FIELDS.contains(field.getKey()), field.getKey());
        }
    }

    private static String textAt(
        JsonNode node,
        String field
    ) {
        if (node == null || node.get(field) == null || !node.get(field).isString()) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("문자열 항목이 없다: " + field);
        }
        return node.get(field).stringValue();
    }

    private static List<String> textsOf(
        JsonNode node
    ) {
        if (node == null || !node.isArray()) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("목록이어야 하는 항목이 목록이 아니다");
        }
        List<String> texts = new ArrayList<>();
        for (JsonNode item : node) {
            texts.add(item.stringValue());
        }
        return List.copyOf(texts);
    }

    private static List<Integer> numbersOf(
        JsonNode node
    ) {
        if (node == null || !node.isArray()) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("목록이어야 하는 항목이 목록이 아니다");
        }
        List<Integer> numbers = new ArrayList<>();
        for (JsonNode item : node) {
            numbers.add(item.asInt());
        }
        return List.copyOf(numbers);
    }

    private static Map<String, Double> constantsOf(
        JsonNode node
    ) {
        if (node == null || !node.isObject()) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("정규화 상수가 객체가 아니다");
        }
        Map<String, Double> constants = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            constants.put(field.getKey(), field.getValue().asDouble());
        }
        return Map.copyOf(constants);
    }

    private static BundleManifest.GoldenVector goldenVectorOf(
        JsonNode node
    ) {
        if (node == null || !node.isObject()) {
            throw BundleCheck.GOLDEN_VECTOR.reject("대조 사례가 없다");
        }
        List<Double> featureVector = new ArrayList<>();
        for (JsonNode value : node.get("featureVector")) {
            featureVector.add(value.asDouble());
        }
        return new BundleManifest.GoldenVector(
            List.copyOf(featureVector),
            textAt(node, "modelRoute"),
            node.get("stopsAhead").asInt(),
            node.get("currentSeats").asInt(),
            node.get("capacity").asInt(),
            node.get("expectedFullChance").asDouble(),
            node.get("expectedSeats").asDouble());
    }

    private static Map<String, BundleManifest.TensorDeclaration> declarationsOf(
        JsonNode node
    ) {
        if (node == null || !node.isObject()) {
            throw BundleCheck.MANIFEST_IS_UTF8_JSON.reject("배열 선언이 객체가 아니다");
        }
        Map<String, BundleManifest.TensorDeclaration> declarations = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD.require(
                seen.add(field.getKey()), "배열 선언이 겹친다: " + field.getKey());
            JsonNode shape = field.getValue().get("shape");
            int[] axes = new int[shape.size()];
            for (int index = 0; index < axes.length; index++) {
                axes[index] = shape.get(index).asInt();
            }
            declarations.put(field.getKey(), new BundleManifest.TensorDeclaration(
                TensorDataType.of(field.getValue().get("dtype").stringValue()), axes));
        }
        return declarations;
    }
}
