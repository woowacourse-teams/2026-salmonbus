package com.gustler.backend.forecasting.infrastructure.bundle;

import static org.assertj.core.api.Assertions.assertThat;

import com.gustler.backend.forecasting.infrastructure.bundle.BundleManifest.TensorDeclaration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.stream.Stream;
import org.assertj.core.api.AutoCloseableSoftAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class TensorDeclarationTest {
    @TempDir
    Path directory;

    @ParameterizedTest
    @MethodSource("components")
    void 컴포넌트로_재구성하면_동등성과_해시와_문자열을_유지한다(
        TensorDataType dataType,
        int[] shape
    ) {
        // given
        TensorDeclaration original = new TensorDeclaration(dataType, shape);

        // when
        TensorDeclaration copy = new TensorDeclaration(original.dataType(), original.shape());

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(copy).isEqualTo(original);
            softly.assertThat(original).isEqualTo(copy);
            softly.assertThat(copy.hashCode()).isEqualTo(original.hashCode());
            softly.assertThat(copy.toString()).isEqualTo(original.toString());
            softly.assertThat(Set.of(original)).contains(copy);
            softly.assertThat(Map.of(original, "stored")).containsEntry(copy, "stored");
        }
    }

    @Test
    void 입력과_반환_배열을_바꿔도_내부_값과_해시_키는_유지된다() {
        // given
        int[] input = {2, 3};
        TensorDeclaration declaration = new TensorDeclaration(TensorDataType.FLOAT64, input);
        final int originalHash = declaration.hashCode();
        Map<TensorDeclaration, String> values = Map.of(declaration, "stored");

        // when
        input[0] = 99;
        int[] returned = declaration.shape();
        returned[1] = 88;

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(declaration.shape()).containsExactly(2, 3);
            softly.assertThat(declaration.shape()).isNotSameAs(input).isNotSameAs(returned);
            softly.assertThat(declaration.hashCode()).isEqualTo(originalHash);
            softly.assertThat(values).containsEntry(
                new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3}), "stored");
        }
    }

    @Test
    void 같은_자료형과_크기는_서로_다른_배열이어도_한_집합_값이다() {
        // given
        TensorDeclaration first = new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3});
        TensorDeclaration second = new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3});

        // when
        Set<TensorDeclaration> declarations = new HashSet<>(List.of(first, second));

        // then
        assertThat(declarations).containsExactly(first);
    }

    @ParameterizedTest
    @MethodSource("differentComponents")
    void 자료형이나_크기가_다르면_서로_다른_값이다(
        TensorDeclaration first,
        TensorDeclaration second
    ) {
        // when
        final boolean forward = first.equals(second);
        final boolean backward = second.equals(first);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(forward).isFalse();
            softly.assertThat(backward).isFalse();
        }
    }

    @Test
    void 같은_인스턴스와는_같고_null이나_다른_타입과는_다르다() {
        // given
        TensorDeclaration declaration = new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3});

        // when
        final boolean same = declaration.equals(declaration);
        final boolean nullValue = declaration.equals(null);
        final boolean differentType = declaration.equals(new int[] {2, 3});

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(same).isTrue();
            softly.assertThat(nullValue).isFalse();
            softly.assertThat(differentType).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("components")
    void JSON_왕복은_필드와_null과_배열_내용을_유지한다(
        TensorDataType dataType,
        int[] shape
    ) {
        // given
        ObjectMapper mapper = new ObjectMapper();
        TensorDeclaration original = new TensorDeclaration(dataType, shape);

        // when
        String encoded = mapper.writeValueAsString(original);
        TensorDeclaration decoded = mapper.readValue(encoded, TensorDeclaration.class);
        JsonNode tree = mapper.readTree(encoded);

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(tree.size()).isEqualTo(2);
            softly.assertThat(tree.has("dataType")).isTrue();
            softly.assertThat(tree.has("shape")).isTrue();
            softly.assertThat(decoded.dataType()).isEqualTo(dataType);
            softly.assertThat(decoded.shape()).isEqualTo(shape);
            softly.assertThat(decoded).isEqualTo(original);
            softly.assertThat(decoded.hashCode()).isEqualTo(original.hashCode());
        }
    }

    @Test
    void 파일에서_읽은_선언은_외부_배열_변경과_재구성에도_같은_내용을_유지한다() throws Exception {
        // given
        BundleFiles files = DummyBundle.valid().writeTo(directory);
        byte[] originalBytes = Files.readAllBytes(files.manifest());
        BundleManifest manifest = BundleLoader.load(files).manifest();
        Map<String, TensorDeclaration> reconstructed = new LinkedHashMap<>();

        // when
        for (Entry<String, TensorDeclaration> entry : manifest.tensorDeclarations().entrySet()) {
            TensorDeclaration declaration = entry.getValue();
            int[] returned = declaration.shape();
            returned[0] = -1;
            reconstructed.put(entry.getKey(), new TensorDeclaration(declaration.dataType(), declaration.shape()));
        }
        BundleManifest loadedAgain = BundleLoader.load(files).manifest();

        // then
        try (AutoCloseableSoftAssertions softly = new AutoCloseableSoftAssertions()) {
            softly.assertThat(reconstructed).isEqualTo(manifest.tensorDeclarations());
            softly.assertThat(loadedAgain).isEqualTo(manifest);
            softly.assertThat(Files.readAllBytes(files.manifest())).isEqualTo(originalBytes);
        }
    }

    private static Stream<Arguments> components() {
        return Stream.of(
            Arguments.of(TensorDataType.FLOAT64, null),
            Arguments.of(TensorDataType.UINT8, null),
            Arguments.of(null, null),
            Arguments.of(TensorDataType.FLOAT64, new int[0]),
            Arguments.of(TensorDataType.UINT8, new int[] {1}),
            Arguments.of(null, new int[] {1, 2}),
            Arguments.of(TensorDataType.FLOAT64, new int[] {2, 3}),
            Arguments.of(TensorDataType.UINT8, new int[] {2, 3}),
            Arguments.of(TensorDataType.FLOAT64, new int[] {3, 2}),
            Arguments.of(TensorDataType.FLOAT64, new int[] {0, -1, Integer.MIN_VALUE, Integer.MAX_VALUE})
        );
    }

    private static Stream<Arguments> differentComponents() {
        return Stream.of(
            Arguments.of(new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3}),
                new TensorDeclaration(TensorDataType.UINT8, new int[] {2, 3})),
            Arguments.of(new TensorDeclaration(null, new int[] {2, 3}),
                new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3})),
            Arguments.of(new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3}),
                new TensorDeclaration(TensorDataType.FLOAT64, new int[] {3, 2})),
            Arguments.of(new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3}),
                new TensorDeclaration(TensorDataType.FLOAT64, new int[] {2, 3, 1})),
            Arguments.of(new TensorDeclaration(TensorDataType.FLOAT64, null),
                new TensorDeclaration(TensorDataType.FLOAT64, new int[0]))
        );
    }
}
