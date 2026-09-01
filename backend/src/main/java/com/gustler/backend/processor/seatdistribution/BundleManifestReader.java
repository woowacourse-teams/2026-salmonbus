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
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

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

    /**
     * 항목을 이름순으로 다시 적는 mapper.
     *
     * <p>{@code SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS} 로는 안 된다. 그것은 {@code Map} 을
     * 쓸 때만 걸리고, 여기서 다시 쓰는 것은 {@code JsonNode} 라 원래 순서가 그대로 나온다.
     * 그러면 <b>빈칸 없이 항목 순서만 바꾼 설명 파일이 통과한다.</b>
     */
    private static final ObjectMapper CANONICAL_WRITER = JsonMapper.builder()
        .enable(JsonNodeFeature.WRITE_PROPERTIES_SORTED)
        .build();

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
        String text = textOf(content);
        JsonNode root = parse(text);
        BundleCheck.MANIFEST_IS_UTF8_JSON.require(root.isObject(), "최상위가 객체가 아니다");
        requireCanonical(root, text);
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

    /**
     * 설명 파일이 정규 모양인지 본다. 항목을 이름순으로 놓고 빈칸 없이 다시 적었을 때 원문과
     * 글자 그대로 같아야 한다.
     *
     * <p>정규 모양이 아니면 <b>같은 뜻인데 요약값이 다른 파일</b>이 여럿 생긴다. 빈칸 하나나
     * 항목 순서만 달라도 요약값이 달라져서, 같은 계수를 두 신원으로 올릴 수 있게 된다.
     */
    private static void requireCanonical(
        JsonNode root,
        String text
    ) {
        String canonical = CANONICAL_WRITER.writeValueAsString(root);
        BundleCheck.MANIFEST_IS_CANONICAL_JSON.require(
            canonical.equals(text.strip()),
            "항목을 이름순으로 빈칸 없이 다시 적은 것과 다르다");
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
