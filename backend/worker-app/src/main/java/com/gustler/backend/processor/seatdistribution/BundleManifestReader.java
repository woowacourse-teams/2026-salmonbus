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
 *
 * <p>항목을 꺼내기 전에 자료형을 본다. 안 보고 꺼내면 항목이 빠졌을 때 {@link NullPointerException}
 * 이 나서 {@link BundleRejectedException} 을 안 거치고, 값이 문자열이면 숫자로 바꿔 읽어 준다.
 * 둘 다 <b>안 올리기로 한 묶음이 올라가는 길</b>이다.
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
        JsonNode routeReference = objectAt(root, "routeReference");
        return new BundleManifest(
            textAt(root, "bundleSchemaVersion"),
            textAt(root, "modelVersion"),
            textAt(root, "releaseId"),
            textAt(root, "featureContractVersion"),
            textAt(root, "sourceCommit"),
            textAt(routeReference, "version"),
            textAt(routeReference, "digest"),
            textsAt(root, "routes"),
            wholeNumbersAt(root, "horizonStops"),
            textsAt(root, "featureNames"),
            constantsAt(root, "normalizationConstants"),
            textAt(root, "timeSlotSource"),
            textAt(root, "capacityPolicy"),
            textAt(root, "cellStatisticsPolicy"),
            declarationsAt(root, "tensors"),
            textAt(root, "weightsDigest"),
            textAt(root, "identityDigest"),
            textAt(root, "goldenVectorDigest"),
            goldenVectorAt(root, "goldenVector"),
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

    /** 객체에서 항목 하나를 꺼낸다. 담은 것이 객체가 아니거나 항목이 없으면 거기서 멈춘다. */
    private static JsonNode fieldAt(
        JsonNode node,
        String field
    ) {
        BundleCheck.MANIFEST_FIELD_TYPE.require(
            node != null && node.isObject(), "객체가 아닌 것에서 항목을 찾았다: " + field);
        JsonNode value = node.get(field);
        BundleCheck.MANIFEST_FIELD_TYPE.require(value != null, "항목이 없다: " + field);
        return value;
    }

    private static JsonNode objectAt(
        JsonNode node,
        String field
    ) {
        JsonNode value = fieldAt(node, field);
        BundleCheck.MANIFEST_FIELD_TYPE.require(value.isObject(), "객체가 아니다: " + field);
        return value;
    }

    private static JsonNode arrayAt(
        JsonNode node,
        String field
    ) {
        JsonNode value = fieldAt(node, field);
        BundleCheck.MANIFEST_FIELD_TYPE.require(value.isArray(), "목록이 아니다: " + field);
        return value;
    }

    private static String textAt(
        JsonNode node,
        String field
    ) {
        return textOf(fieldAt(node, field), field);
    }

    private static int wholeNumberAt(
        JsonNode node,
        String field
    ) {
        return wholeNumberOf(fieldAt(node, field), field);
    }

    private static double numberAt(
        JsonNode node,
        String field
    ) {
        return numberOf(fieldAt(node, field), field);
    }

    private static String textOf(
        JsonNode value,
        String field
    ) {
        BundleCheck.MANIFEST_FIELD_TYPE.require(value.isString(), "문자열이 아니다: " + field);
        return value.stringValue();
    }

    /** 정수만 받는다. {@code "3"} 이나 {@code 3.5} 를 3 으로 바꿔 읽지 않는다. */
    private static int wholeNumberOf(
        JsonNode value,
        String field
    ) {
        BundleCheck.MANIFEST_FIELD_TYPE.require(
            value.isIntegralNumber() && value.canConvertToInt(), "정수가 아니다: " + field);
        return value.intValue();
    }

    /** 유한한 수만 받는다. 문자열로 적힌 수도 안 받는다. */
    private static double numberOf(
        JsonNode value,
        String field
    ) {
        BundleCheck.MANIFEST_FIELD_TYPE.require(value.isNumber(), "수가 아니다: " + field);
        final double number = value.doubleValue();
        BundleCheck.MANIFEST_FIELD_TYPE.require(
            Double.isFinite(number), "유한한 수가 아니다: %s, %s".formatted(field, number));
        return number;
    }

    private static List<String> textsAt(
        JsonNode node,
        String field
    ) {
        List<String> texts = new ArrayList<>();
        for (JsonNode item : arrayAt(node, field)) {
            texts.add(textOf(item, field + " 의 값"));
        }
        return List.copyOf(texts);
    }

    private static List<Integer> wholeNumbersAt(
        JsonNode node,
        String field
    ) {
        List<Integer> numbers = new ArrayList<>();
        for (JsonNode item : arrayAt(node, field)) {
            numbers.add(wholeNumberOf(item, field + " 의 값"));
        }
        return List.copyOf(numbers);
    }

    private static List<Double> numbersAt(
        JsonNode node,
        String field
    ) {
        List<Double> numbers = new ArrayList<>();
        for (JsonNode item : arrayAt(node, field)) {
            numbers.add(numberOf(item, field + " 의 값"));
        }
        return List.copyOf(numbers);
    }

    private static Map<String, Double> constantsAt(
        JsonNode node,
        String field
    ) {
        Map<String, Double> constants = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> constant : objectAt(node, field).properties()) {
            constants.put(
                constant.getKey(), numberOf(constant.getValue(), field + "." + constant.getKey()));
        }
        return Map.copyOf(constants);
    }

    private static BundleManifest.GoldenVector goldenVectorAt(
        JsonNode node,
        String field
    ) {
        JsonNode golden = objectAt(node, field);
        return new BundleManifest.GoldenVector(
            numbersAt(golden, "featureVector"),
            textAt(golden, "modelRoute"),
            wholeNumberAt(golden, "stopsAhead"),
            wholeNumberAt(golden, "currentSeats"),
            wholeNumberAt(golden, "capacity"),
            numberAt(golden, "expectedFullChance"),
            numberAt(golden, "expectedSeats"));
    }

    private static Map<String, BundleManifest.TensorDeclaration> declarationsAt(
        JsonNode node,
        String field
    ) {
        Map<String, BundleManifest.TensorDeclaration> declarations = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> declared : objectAt(node, field).properties()) {
            BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD.require(
                seen.add(declared.getKey()), "배열 선언이 겹친다: " + declared.getKey());
            declarations.put(declared.getKey(), declarationOf(declared.getValue(), declared.getKey()));
        }
        return declarations;
    }

    private static BundleManifest.TensorDeclaration declarationOf(
        JsonNode declared,
        String name
    ) {
        BundleCheck.MANIFEST_FIELD_TYPE.require(
            declared.isObject(), "배열 선언이 객체가 아니다: " + name);
        List<Integer> axes = wholeNumbersAt(declared, "shape");
        int[] shape = new int[axes.size()];
        for (int index = 0; index < shape.length; index++) {
            shape[index] = axes.get(index);
        }
        return new BundleManifest.TensorDeclaration(
            TensorDataType.of(textAt(declared, "dtype")), shape);
    }
}
