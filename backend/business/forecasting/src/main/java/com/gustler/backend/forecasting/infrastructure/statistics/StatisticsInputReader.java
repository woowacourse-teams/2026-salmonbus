package com.gustler.backend.forecasting.infrastructure.statistics;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/** 작업 전용 불변 디렉터리의 JSON Lines를 선언 순서로 읽는다. 운영 원자료 추출기는 아니다. */
final class StatisticsInputReader implements Iterator<StatisticsInputRow>, AutoCloseable {
    private static final JsonMapper JSON = JsonMapper.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
        .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
        .enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES)
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
        .build();

    private final Path directory;
    private final List<StatisticsInputFile> files;
    private final int maxLineCharacters;
    private BufferedReader input;
    private int fileIndex;
    private StatisticsInputRow next;
    private boolean finished;
    private boolean closed;

    StatisticsInputReader(Path directory, List<StatisticsInputFile> files, int maxLineCharacters) {
        if (maxLineCharacters < 1) {
            throw new IllegalArgumentException("한 행의 최대 길이는 양수여야 한다");
        }
        StatisticsInputFiles.verify(directory, files);
        this.directory = directory;
        this.files = List.copyOf(files);
        this.maxLineCharacters = maxLineCharacters;
    }

    @Override
    public boolean hasNext() {
        if (closed) {
            throw new IllegalStateException("닫힌 입력은 읽을 수 없다");
        }
        if (next != null) {
            return true;
        }
        if (finished) {
            return false;
        }
        try {
            while (true) {
                if (input == null) {
                    if (fileIndex == files.size()) {
                        // 검증 이후 변경도 거부한다. 실행 중 경로 교체 방지는 작업 디렉터리 소유권으로 보장한다.
                        StatisticsInputFiles.verify(directory, files);
                        finished = true;
                        return false;
                    }
                    var decoder = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
                    var channel = Files.newByteChannel(directory.resolve(files.get(fileIndex++).name()),
                        StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
                    input = new BufferedReader(Channels.newReader(channel, decoder, -1));
                }
                String line = readLine();
                if (line == null) {
                    input.close();
                    input = null;
                    continue;
                }
                if (line.isBlank()) {
                    throw new IllegalArgumentException("빈 자료 행은 허용하지 않는다");
                }
                next = JSON.readValue(line, StatisticsInputRow.class);
                if (next == null) {
                    throw new IllegalArgumentException("비어 있는 자료가 포함됐다");
                }
                return true;
            }
        } catch (IOException | RuntimeException exception) {
            try {
                close();
            } catch (RuntimeException closing) {
                exception.addSuppressed(closing);
            }
            throw new IllegalArgumentException("통계 입력 파일을 읽거나 검증하지 못했다", exception);
        }
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = input.read()) != -1) {
            if (value == '\n') {
                return line.toString();
            }
            if (line.length() == maxLineCharacters) {
                throw new IllegalArgumentException("자료 행이 최대 길이를 초과했다");
            }
            line.append((char) value);
        }
        return line.isEmpty() ? null : line.toString();
    }

    @Override
    public StatisticsInputRow next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        StatisticsInputRow result = next;
        next = null;
        return result;
    }

    @Override
    public void close() {
        closed = true;
        next = null;
        if (input != null) {
            try {
                input.close();
            } catch (IOException exception) {
                throw new IllegalArgumentException("입력 파일을 닫지 못했다", exception);
            } finally {
                input = null;
            }
        }
    }
}
