package com.gustler.backend.forecasting.infrastructure.bundle;

import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.GoldenVector;
import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.TensorDeclaration;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.cfg.JsonNodeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * 설명 파일을 읽는다.
 *
 * <p>모르는 항목이 하나라도 있으면 거절한다. 새 항목은 아직 검사하지 않는 규칙이 번들에
 * 추가되었다는 뜻이므로 무시하면 해당 규칙을 지키지 않은 예보가 생성된다.
 *
 * <p>바이트를 엄격하게 UTF-8 로 읽는다. 느슨하게 읽으면 깨진 바이트가 물음표로 바뀌어
 * 특징 이름이 달라진다.
 *
 * <p>항목을 읽기 전에 자료형을 확인한다. 확인하지 않으면 항목 누락 시 {@link NullPointerException}
 * 이 발생해 {@link BundleRejectedException} 을 거치지 않고, 문자열 값은 숫자로 변환해 읽게 된다.
 * 두 경우 모두 거절해야 할 묶음이 적재될 수 있다.
 */
final class BundleManifestReader {
    /**
     * 항목을 이름순으로 다시 쓰는 mapper.
     *
     * <p>{@code SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS} 는 {@code Map} 을
     * 쓸 때만 적용된다. 여기서 다시 쓰는 {@code JsonNode} 는 원래 순서를 유지하므로 이 설정만으로는
     * 빈칸 없이 항목 순서만 바꾼 설명 파일이 통과한다.
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

    static String textOf(
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
     * 설명 파일의 정규 형식을 확인한다. 항목을 이름순으로 놓고 빈칸 없이 다시 쓴 결과가
     * 원문과 글자 그대로 같아야 한다.
     *
     * <p>정규 형식이 아니면 의미는 같지만 요약값이 다른 파일이 여러 개 생긴다. 빈칸 하나나
     * 항목 순서만 달라도 요약값이 달라져 같은 계수가 서로 다른 식별 정보로 적재될 수 있다.
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
        for (Entry<String, JsonNode> field : root.properties()) {
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
        List<String> texts = StreamSupport.stream(arrayAt(node, field).spliterator(), false)
            .map(item -> textOf(item, field + " 의 값"))
            .collect(Collectors.toCollection(ArrayList::new));
        return List.copyOf(texts);
    }

    private static List<Integer> wholeNumbersAt(
        JsonNode node,
        String field
    ) {
        List<Integer> numbers = StreamSupport.stream(arrayAt(node, field).spliterator(), false)
            .map(item -> wholeNumberOf(item, field + " 의 값"))
            .collect(Collectors.toCollection(ArrayList::new));
        return List.copyOf(numbers);
    }

    private static List<Double> numbersAt(
        JsonNode node,
        String field
    ) {
        List<Double> numbers = StreamSupport.stream(arrayAt(node, field).spliterator(), false)
            .map(item -> numberOf(item, field + " 의 값"))
            .collect(Collectors.toCollection(ArrayList::new));
        return List.copyOf(numbers);
    }

    private static Map<String, Double> constantsAt(
        JsonNode node,
        String field
    ) {
        Map<String, Double> constants = StreamSupport.stream(objectAt(node, field).properties().spliterator(), false)
            .collect(LinkedHashMap::new,
                (byName, constant) -> byName.put(constant.getKey(),
                    numberOf(constant.getValue(), field + "." + constant.getKey())),
                Map::putAll);
        return Map.copyOf(constants);
    }

    private static GoldenVector goldenVectorAt(
        JsonNode node,
        String field
    ) {
        JsonNode golden = objectAt(node, field);
        return new GoldenVector(
            numbersAt(golden, "featureVector"),
            textAt(golden, "modelRoute"),
            wholeNumberAt(golden, "stopsAhead"),
            wholeNumberAt(golden, "currentSeats"),
            wholeNumberAt(golden, "capacity"),
            numberAt(golden, "expectedFullChance"),
            numberAt(golden, "expectedSeats"));
    }

    private static Map<String, TensorDeclaration> declarationsAt(
        JsonNode node,
        String field
    ) {
        Map<String, TensorDeclaration> declarations = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        for (Entry<String, JsonNode> declared : objectAt(node, field).properties()) {
            BundleCheck.MANIFEST_HAS_NO_UNKNOWN_FIELD.require(
                seen.add(declared.getKey()), "배열 선언이 겹친다: " + declared.getKey());
            declarations.put(declared.getKey(), declarationOf(declared.getValue(), declared.getKey()));
        }
        return declarations;
    }

    private static TensorDeclaration declarationOf(
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
        return new TensorDeclaration(
            TensorDataType.of(textAt(declared, "dtype")), shape);
    }
}
