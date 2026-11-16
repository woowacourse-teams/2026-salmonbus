package com.gustler.devtools;

import tools.jackson.databind.JsonNode;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class SettingsPreparation {
    /**
     * basis 교체가 설정 디렉터리를 새로 만든 뒤에도 남는 환경 파일이다. {@link #replacement}가 옮기는
     * {@link Environment#KEYS}의 설정과 {@link ArrivalSettings#FILE}에서 만든다. arrivals.env가 없으면 앱 설치가 만든다.
     */
    static final Set<String> REPLACED_ENVIRONMENT_FILES = Stream.concat(
                    Environment.KEYS.keySet().stream().map(name -> name + ".env"), Stream.of(ArrivalSettings.FILE))
            .collect(Collectors.toUnmodifiableSet());

    private static final String UNIT_PREFIX = "salmonbus-";

    /** systemd가 읽을 때 풀어 쓰는 와일드카드와 {@code %} specifier다. 이런 값은 경로만 보고 어느 파일인지 정할 수 없다. */
    private static final Pattern EXPANDED_BY_SYSTEMD = Pattern.compile("[*?\\[%]");

    interface Writer {
        void write(Path path, byte[] content, int mode) throws Exception;
    }

    private final Layout layout;
    private final Writer writer;

    SettingsPreparation(Layout layout) {
        this(layout, FileOps::writeNew);
    }

    SettingsPreparation(Layout layout, Writer writer) {
        this.layout = layout;
        this.writer = writer;
    }

    /**
     * 이름이 {@code salmonbus-}로 시작하는 서비스 unit과 그 drop-in의 {@code EnvironmentFile}이 basis 교체 뒤에도
     * 남는 파일만 가리키는지 확인한다. 대상은 목록 없이 unit 디렉터리에서 찾으므로 나중에 더한 unit과 수동
     * drop-in도 같은 검사를 받는다.
     *
     * <p>basis 교체는 설정 디렉터리를 통째로 바꾸고 관리하는 파일만 옮긴다. 수동 drop-in이 같은 디렉터리의 다른
     * 파일을 읽으면 교체 뒤 앱이 기동하지 못하므로 앱을 멈추기 전에 거절한다. 수동 설정은 설정 디렉터리 밖에 둔다.
     *
     * <p>줄은 systemd와 같은 방식으로 읽는다. 역슬래시로 끝난 줄은 다음 줄과 한 설정이다.
     */
    static void requireReplaceableEnvironment(Layout layout) throws Exception {
        Path config = layout.config().normalize();
        for (Path file : serviceFiles(layout.units())) {
            for (String line : joinedLines(Files.readAllLines(file))) {
                Optional<String> value = environmentFile(line);
                if (value.isEmpty()) {
                    continue;
                }
                Checks.require(survivesReplacement(value.get(), config), "DEV_UNMANAGED_ENVIRONMENT_FILE");
            }
        }
    }

    /** 역슬래시로 끝난 줄을 다음 줄과 잇는다. systemd는 그 역슬래시를 공백으로 바꿔 읽는다. */
    private static List<String> joinedLines(List<String> lines) {
        List<String> joined = new ArrayList<>();
        StringBuilder pending = new StringBuilder();
        for (String line : lines) {
            if (line.endsWith("\\")) {
                pending.append(line, 0, line.length() - 1).append(' ');
                continue;
            }
            joined.add(pending.append(line).toString());
            pending.setLength(0);
        }
        if (!pending.isEmpty()) {
            joined.add(pending.toString());
        }
        return joined;
    }

    private static List<Path> serviceFiles(Path units) throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path entry : sortedEntries(units)) {
            String name = entry.getFileName().toString();
            if (!name.startsWith(UNIT_PREFIX)) {
                continue;
            }
            if (name.endsWith(".service")) {
                files.add(entry);
            }
            if (name.endsWith(".service.d") && Files.isDirectory(entry)) {
                files.addAll(dropIns(entry));
            }
        }
        return files.stream().filter(Files::isRegularFile).toList();
    }

    private static List<Path> dropIns(Path directory) throws Exception {
        return sortedEntries(directory).stream()
                .filter(path -> path.getFileName().toString().endsWith(".conf"))
                .toList();
    }

    private static List<Path> sortedEntries(Path directory) throws Exception {
        try (Stream<Path> entries = Files.list(directory)) {
            return entries.sorted().toList();
        }
    }

    /** {@code EnvironmentFile=} 줄의 값이다. 다른 설정 줄이거나 값을 비운 줄이면 비어 있다. */
    private static Optional<String> environmentFile(String line) {
        final int separator = line.indexOf('=');
        if (separator < 0) {
            return Optional.empty();
        }
        if (!line.substring(0, separator).strip().equals("EnvironmentFile")) {
            return Optional.empty();
        }
        String value = line.substring(separator + 1).strip();
        if (value.startsWith("-")) {
            value = value.substring(1);
        }
        if (value.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(value);
    }

    /**
     * 설정 디렉터리 밖의 파일이거나, 설정 디렉터리 바로 아래에서 basis 교체가 다시 만드는 파일이다. 와일드카드나 specifier가 든
     * 값은 어느 파일을 읽을지 정할 수 없으므로 남지 않는 것으로 본다.
     */
    private static boolean survivesReplacement(String value, Path config) {
        if (EXPANDED_BY_SYSTEMD.matcher(value).find()) {
            return false;
        }
        Path file = Path.of(value).normalize();
        if (!file.startsWith(config)) {
            return true;
        }
        return config.equals(file.getParent()) && REPLACED_ENVIRONMENT_FILES.contains(file.getFileName().toString());
    }

    static Map<String, Map<String, String>> create(String origin, String revision) {
        if (origin != null) Environment.origin(origin);
        Checks.require(revision.matches("[0-9a-f]{40}"), "DEV_REVISION");
        var password = password();
        var common =
                Map.of(
                        "DB_URL",
                        Environment.DATABASE,
                        "DB_USERNAME",
                        "salmonbus_dev",
                        "DB_PASSWORD",
                        password);
        var values =
                Map.of(
                        "api", common,
                        "worker",
                                Environment.combined(
                                        common,
                                        Map.of(
                                                "GBIS_SERVICE_KEY",
                                                Environment.KEY,
                                                "GBIS_SERVICE_KEY_B",
                                                "",
                                                "GBIS_SERVICE_KEY_C",
                                                "",
                                                "GBIS_SERVICE_KEY_D",
                                                "")),
                        "postgres",
                                Map.of(
                                        "POSTGRES_DB",
                                        "salmonbus_dev",
                                        "POSTGRES_USER",
                                        "salmonbus_dev",
                                        "POSTGRES_PASSWORD",
                                        password),
                        "infra", Map.of("DEV_REVISION", revision));
        Environment.validate(values);
        return values;
    }

    private static String password() {
        byte[] bytes = new byte[24];
        new SecureRandom().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    void replacement(
            Path source,
            Path destination,
            JsonNode identity,
            String uuid,
            String revision,
            final int group)
            throws Exception {
        var settings = Environment.load(layout.config());
        Checks.require(!FileOps.exists(destination), "DESTINATION_EXISTS");
        Files.createDirectory(
                destination,
                java.nio.file.attribute.PosixFilePermissions.asFileAttribute(
                        java.nio.file.attribute.PosixFilePermissions.fromString("rwx------")));
        final boolean legacy = Files.isRegularFile(source.resolve("../config/mongodb.yml").normalize());
        for (var name : Environment.KEYS.keySet()) {
            byte[] content =
                    name.equals("infra")
                            ? Environment.text(Map.of("DEV_REVISION", revision)).getBytes()
                            : name.equals("api") ? (legacy
                                    ? FileOps.privateText(Files.isRegularFile(layout.config().resolve("retired-chat/api.env"))
                                            ? layout.config().resolve("retired-chat/api.env") : layout.config().resolve("api.env"))
                                    : Environment.text(ChatRetirement.databaseOnly(settings.get("api")))).getBytes()
                            : FileOps.privateText(layout.config().resolve(name + ".env")).getBytes();
            writer.write(destination.resolve(name + ".env"), content, 0600);
        }
        ChatRetirement.retain(layout.config(), destination, writer);
        if (legacy) {
            writer.write(destination.resolve("mongodb.env"),
                    FileOps.privateText(ChatRetirement.saved(layout.config(), "mongodb.env")).getBytes(), 0600);
        }
        var hashes = new TreeMap<String, String>();
        var yamlNames = legacy ? java.util.List.of("api.yml", "worker.yml", "mongodb.yml")
                : java.util.List.of("api.yml", "worker.yml");
        for (var name : yamlNames) {
            var file = name.equals("mongodb.yml") ? source.resolve("../config/" + name).normalize()
                    : source.resolve(name);
            byte[] content = Files.readAllBytes(file);
            writer.write(destination.resolve(name), content, 0644);
            hashes.put(name, FileOps.sha(content));
        }
        writer.write(
                destination.resolve("environment.json"),
                Json.bytes(
                        Map.of(
                                "environment",
                                "dev",
                                "instanceId",
                                identity.path("instanceId").asText(),
                                "accountId",
                                identity.path("accountId").asText(),
                                "dataVolumeUuid",
                                uuid,
                                "configSha256",
                                hashes)),
                0600);
        DatabaseTarget.preserve(layout.config(), destination, writer);
        ArrivalSettings.preserve(layout.config(), destination, writer);
        var updated = Environment.load(destination);
        for (var name : java.util.List.of("api", "worker", "postgres")) {
            Checks.require(name.equals("api")
                            ? ChatRetirement.databaseOnly(updated.get(name)).equals(ChatRetirement.databaseOnly(settings.get(name)))
                            : updated.get(name).equals(settings.get(name)), "DEV_SECRETS_CHANGED");
        }
        FileOps.owner(destination, 0, group);
        FileOps.mode(destination, 0750);
        FileOps.sync(destination);
    }

    String initialize(
            Path source,
            JsonNode identity,
            String uuid,
            String origin,
            String revision,
            final int group)
            throws Exception {
        if (origin != null) Environment.origin(origin);
        Checks.require(revision.matches("[0-9a-f]{40}"), "DEV_REVISION");
        var directory = layout.config();
        Checks.require(
                !Files.isSymbolicLink(directory) && !Files.isSymbolicLink(directory.getParent()),
                "CONFIG_DIRECTORY");
        var contents = new TreeMap<String, byte[]>();
        contents.put("api.yml", Files.readAllBytes(source.resolve("api.yml")));
        contents.put("worker.yml", Files.readAllBytes(source.resolve("worker.yml")));
        var hashes = new TreeMap<String, String>();
        for (var entry : contents.entrySet()) {
            hashes.put(entry.getKey(), FileOps.sha(entry.getValue()));
        }
        if (FileOps.exists(directory)) {
            Checks.require(
                    Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                            && FileOps.attribute(directory, "uid") == 0
                            && FileOps.attribute(directory, "gid") == group
                            && (FileOps.attribute(directory, "mode") & 0777) == 0750,
                    "CONFIG_DIRECTORY");
            var marker =
                    Json.read(
                            FileOps.privateText(directory.resolve("environment.json")).getBytes());
            Environment.identity(marker, identity);
            Checks.require(
                    marker.path("dataVolumeUuid").asText().equals(uuid)
                            && marker.path("configSha256").equals(Json.read(Json.bytes(hashes))),
                    "EXISTING_SETTINGS_DIFFER");
            for (var entry : hashes.entrySet()) {
                var file = directory.resolve(entry.getKey());
                FileOps.protectedFile(file, "CONFIG_FILE");
                Checks.require(
                        (FileOps.attribute(file, "mode") & 0777) == 0644
                                && FileOps.sha(file).equals(entry.getValue()),
                        "EXISTING_SETTINGS_DIFFER");
            }
            var settings = Environment.load(directory);
            Checks.require(
                    settings.get("infra").get("DEV_REVISION").equals(revision),
                    "EXISTING_SETTINGS_DIFFER");
            return "preserved";
        }
        for (var name : java.util.List.of("postgres", "mongodb")) {
            var path = layout.data().resolve(name);
            Checks.require(!Files.isSymbolicLink(path), "ORPHANED_DEV_DATA");
            if (FileOps.exists(path)) {
                try (var files = Files.list(path)) {
                    Checks.require(files.findAny().isEmpty(), "ORPHANED_DEV_DATA");
                }
            }
        }
        var stage = Files.createTempDirectory(directory.getParent(), ".salmonbus-dev-");
        try {
            for (var entry : create(origin, revision).entrySet()) {
                writer.write(
                        stage.resolve(entry.getKey() + ".env"),
                        Environment.text(entry.getValue()).getBytes(),
                        0600);
            }
            for (var entry : contents.entrySet()) {
                writer.write(stage.resolve(entry.getKey()), entry.getValue(), 0644);
            }
            writer.write(
                    stage.resolve("environment.json"),
                    Json.bytes(
                            Map.of(
                                    "environment",
                                    "dev",
                                    "instanceId",
                                    identity.path("instanceId").asText(),
                                    "accountId",
                                    identity.path("accountId").asText(),
                                    "dataVolumeUuid",
                                    uuid,
                                    "configSha256",
                                    hashes)),
                    0600);
            Environment.load(stage);
            FileOps.owner(stage, 0, group);
            FileOps.mode(stage, 0750);
            FileOps.sync(stage);
            FileOps.moveNew(stage, directory);
        } finally {
            FileOps.removeTree(stage);
        }
        return "prepared";
    }
}
