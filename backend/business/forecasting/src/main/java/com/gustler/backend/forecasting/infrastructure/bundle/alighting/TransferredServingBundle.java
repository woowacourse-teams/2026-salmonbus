package com.gustler.backend.forecasting.infrastructure.bundle.alighting;

import com.gustler.backend.forecasting.domain.deployment.ModelRelease;
import com.gustler.backend.forecasting.domain.deployment.SupportedForecastScope;
import com.gustler.backend.forecasting.domain.model.Sha256;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class TransferredServingBundle {
    public static final String SCHEMA = "alighting-transfer-serving-v1";
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final List<String> SHARED_FILES = List.of("base.json", "gate.json", "pool.json", "constants.json",
        "feature-contract.json", "golden.json", "boundaries.json");

    private TransferredServingBundle() {
    }

    public static ModelRelease load(Path directory, byte[] descriptor, JsonNode manifest) throws IOException {
        require(manifest.path("serving_ready").isBoolean() && manifest.path("serving_ready").booleanValue(),
            "전이 모델이 서빙 준비 상태가 아니다");
        Path base = directory.resolve("base");
        Path next = directory.resolve("candidate");
        VerifiedBase parent = verifiedBase(base, manifest.path("base_sha256").asString(""));
        AlightingAwareInferenceBundle candidate = AlightingAwareInferenceBundle.load(next);
        require(candidate.digest().equals(manifest.path("candidate_sha256").asString(""))
            && parent.digest().equals(candidate.manifest().path("parent_manifest_sha256").asString("")),
            "검증한 운영 번들과 후보의 연결이 다르다");
        Set<String> additions = unchangedBase(parent, candidate, next);
        byte[] bytes = read(directory.resolve("transfer-evidence.json"), 4_194_304);
        require(Sha256.of(bytes).equals(manifest.path("evidence_sha256").asString("")), "전이 모델 검증 파일이 바뀌었다");
        String releaseId = manifest.path("release_id").asString("");
        require(releaseId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,78}"), "전이 모델 출시 식별자가 잘못됐다");
        JsonNode evidence = JSON.readTree(bytes);
        TransferReplayEvidence.verify(evidence, additions, candidate, releaseId);
        Instant through = Instant.parse(manifest.path("data_through").asString(""));
        require(through.equals(OffsetDateTime.parse(evidence.path("runtime").path("data_through").asString("")).toInstant()),
            "전이 모델 학습 기준 시각이 런타임 검사와 다르다");
        for (String route : candidate.routes()) {
            JsonNode stats = candidate.statistics(route);
            for (String key : List.of("events_known_through", "observations_through")) {
                Instant last = OffsetDateTime.parse(stats.path(key).asString("").replace(' ', 'T')).toInstant();
                require(!through.isBefore(last), "학습 기준 이후의 통계가 있다");
            }
        }
        String digest = Sha256.of(descriptor);
        return ModelRelease.alighting(releaseId, digest, through.toString(), new SupportedForecastScope(candidate.routes()),
            AlightingAwareServingBundle.readModel(candidate, digest));
    }

    private record VerifiedBase(
        String digest, List<String> routes, Set<LocalDate> holidays, JsonNode hashes, String full, JsonNode features,
        Map<String, String> reference, Map<String, String> extension
    ) {
    }

    private static VerifiedBase verifiedBase(Path directory, String expectedDigest) throws IOException {
        AlightingAwareInferenceBundle parent = AlightingAwareInferenceBundle.loadForServing(directory);
        require(parent.digest().equals(expectedDigest), "기존 서빙 번들의 해시가 다르다");
        JsonNode hours = parent.extendedHours();
        require(hours != null, "기존 번들에 시간 확장 모델이 없다");
        JsonNode hashes = parent.manifest().path("files");
        Map<String, JsonNode> references = references(directory, hashes);
        Map<String, String> reference = new HashMap<>();
        Map<String, String> extension = new HashMap<>();
        for (String route : parent.routes()) {
            reference.put(route, fingerprint(references.get(route)));
            extension.put(route, fingerprint(hours.path("routes").path(route)));
        }
        return new VerifiedBase(parent.digest(), parent.routes(), parent.holidays(), hashes, fingerprint(hours.path("full")),
            hours.path("features"), Map.copyOf(reference), Map.copyOf(extension));
    }

    private static String fingerprint(JsonNode value) {
        return Sha256.of(JSON.writeValueAsBytes(value));
    }

    private static Set<String> unchangedBase(
        VerifiedBase parent,
        AlightingAwareInferenceBundle candidate,
        Path next
    ) throws IOException {
        Set<String> additions = new HashSet<>(candidate.routes());
        require(additions.containsAll(parent.routes()) && additions.removeAll(parent.routes()) && !additions.isEmpty(),
            "전이 모델은 기존 노선을 유지하고 새 노선을 추가해야 한다");
        JsonNode before = parent.hashes();
        JsonNode after = candidate.manifest().path("files");
        for (String filename : SHARED_FILES) {
            require(before.has(filename) && before.get(filename).equals(after.path(filename)), "공통 모델 변경: " + filename);
        }
        require(parent.holidays().equals(candidate.holidays()), "공휴일 달력이 바뀌었다");
        JsonNode newHours = candidate.extendedHours();
        require(newHours != null && parent.full().equals(fingerprint(newHours.path("full")))
            && parent.features().equals(newHours.path("features")), "공통 시간 확장 모델이 바뀌었다");
        Map<String, JsonNode> newRef = references(next, after);
        for (String route : parent.routes()) {
            String name = "statistics-" + route + ".json";
            require(before.get(name).equals(after.path(name)) && parent.reference().get(route).equals(fingerprint(newRef.get(route)))
                && parent.extension().get(route).equals(fingerprint(newHours.path("routes").path(route))),
                "기존 노선 참조·가중치 변경: " + route);
        }
        JsonNode receipt = checkedJson(next, "transfer-receipt.json", after);
        require(parent.digest().equals(receipt.path("parent_manifest_sha256").asString(""))
            && Set.copyOf(receipt.path("routes").propertyNames()).equals(additions), "전이 출처의 노선 범위가 다르다");
        for (String route : additions) {
            JsonNode donors = receipt.path("routes").path(route).path("donors");
            require(donors.isArray() && !donors.isEmpty(), "전이 원본 노선 누락");
            Set<String> unique = new HashSet<>();
            for (JsonNode donor : donors) {
                require(parent.routes().contains(donor.asString("")) && unique.add(donor.asString()), "잘못된 전이 원본 노선");
            }
        }
        return Set.copyOf(additions);
    }

    private static Map<String, JsonNode> references(Path directory, JsonNode hashes) throws IOException {
        Map<String, JsonNode> result = new HashMap<>();
        for (JsonNode route : checkedJson(directory, "route-reference.json", hashes).path("routes")) {
            require(result.put(route.path("modelRoute").asString(""), route) == null, "중복 노선 참조");
        }
        return result;
    }

    private static JsonNode checkedJson(Path directory, String name, JsonNode hashes) throws IOException {
        byte[] bytes = read(directory.resolve(name), 32 * 1024 * 1024);
        require(Sha256.of(bytes).equals(hashes.path(name).asString("")), "모델 파일 해시 불일치: " + name);
        return JSON.readTree(bytes);
    }

    private static byte[] read(Path file, final int limit) throws IOException {
        require(Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= limit, "잘못된 전이 모델 파일");
        return Files.readAllBytes(file);
    }

    static void require(final boolean valid, String reason) {
        if (!valid) {
            throw new IllegalArgumentException(reason);
        }
    }
}
