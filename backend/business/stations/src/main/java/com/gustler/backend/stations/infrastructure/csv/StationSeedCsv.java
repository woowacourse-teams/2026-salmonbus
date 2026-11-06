package com.gustler.backend.stations.infrastructure.csv;

import com.gustler.backend.stations.domain.BusStation;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.zip.GZIPInputStream;

/**
 * TAGO 명부의 GGB 원천만 선택한다. 다른 접두어의 ID는 GBIS ID로 변환하지 않는다.
 */
public final class StationSeedCsv {

    private StationSeedCsv() {
    }

    public record Seed(LocalDate date, String sha256, List<BusStation> stations) {

        public Seed {
            stations = List.copyOf(stations);
        }
    }

    public static Seed read(Path path, Charset charset) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream file = new DigestInputStream(Files.newInputStream(path), digest);
                InputStream decoded = decodedStream(file, path);
                PushbackReader reader = new PushbackReader(new InputStreamReader(decoded, charset), 1)) {
                ParsedRows rows = readRows(reader);
                return rows.toSeed(HexFormat.of().formatHex(digest.digest()));
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("정류장 기초 명부를 읽을 수 없다", failure);
        }
    }

    private static InputStream decodedStream(InputStream file, Path path) throws IOException {
        if (path.toString().endsWith(".gz")) {
            return new GZIPInputStream(file);
        }
        return file;
    }

    private static ParsedRows readRows(PushbackReader reader) throws IOException {
        List<String> headers = readHeaders(reader);
        ParsedRows parsed = new ParsedRows(headers);
        List<String> values;
        while ((values = row(reader)) != null) {
            parsed.add(values);
        }
        return parsed;
    }

    private static List<String> readHeaders(PushbackReader reader) throws IOException {
        final int first = reader.read();
        if (first != -1 && first != 0xFEFF) {
            reader.unread(first);
        }
        List<String> headers = row(reader);
        if (headers == null) {
            throw invalid();
        }
        headers.set(0, headers.getFirst().replace("\uFEFF", ""));
        return headers;
    }

    private static String regionOf(String city) {
        String region = city.strip();
        if (region.startsWith("서울")) {
            return "서울";
        }
        if (region.startsWith("인천")) {
            return "인천";
        }
        String[] parts = region.split("\\s+");
        return parts[parts.length - 1].replaceFirst("[시군]$", "");
    }

    private static int column(List<String> headers, String name) {
        final int index = headers.indexOf(name);
        if (index < 0) {
            throw invalid();
        }
        return index;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("정류장 CSV의 필수 열·GBIS ID·좌표·기준일·중복을 확인해야 한다");
    }

    private record Columns(
        int identifier,
        int name,
        int latitude,
        int longitude,
        int date,
        int mobileNumber,
        int city,
        int count
    ) {

        static Columns from(List<String> headers) {
            return new Columns(
                column(headers, "정류장번호"),
                column(headers, "정류장명"),
                column(headers, "위도"),
                column(headers, "경도"),
                column(headers, "정보수집일"),
                column(headers, "모바일단축번호"),
                column(headers, "도시명"),
                headers.size()
            );
        }
    }

    /**
     * 한 파일의 중복·단일 기준일·행 수 제한을 유지한다. CSV 열 위치는 하나의 입력 계약이다.
     */
    private static final class ParsedRows {

        private final Columns columns;

        private final Set<String> identifiers = new HashSet<>();

        private final List<BusStation> stations = new ArrayList<>();

        private LocalDate dataDate;

        ParsedRows(List<String> headers) {
            columns = Columns.from(headers);
        }

        void add(List<String> values) {
            if (isBlankRow(values)) {
                return;
            }
            validateWidth(values);
            String providerId = values.get(columns.identifier()).strip();
            if (!isBoardingStation(providerId)) {
                return;
            }
            BusStation station = stationOf(values, providerId.substring(3));
            acceptDate(LocalDate.parse(values.get(columns.date())));
            stations.add(station);
            if (stations.size() > 100_000) {
                throw invalid();
            }
        }

        private static boolean isBlankRow(List<String> values) {
            return values.size() == 1 && values.getFirst().isBlank();
        }

        private void validateWidth(List<String> values) {
            if (values.size() != columns.count()) {
                throw invalid();
            }
        }

        private static boolean isBoardingStation(String providerId) {
            return providerId.startsWith("GGB") && !providerId.startsWith("GGB277");
        }

        private BusStation stationOf(List<String> values, String stationId) {
            final double longitude = Double.parseDouble(values.get(columns.longitude()));
            final double latitude = Double.parseDouble(values.get(columns.latitude()));
            validateCoordinates(longitude, latitude);
            if (!identifiers.add(stationId)) {
                throw invalid();
            }
            String number = mobileNumberOf(values.get(columns.mobileNumber()));
            String region = regionOf(values.get(columns.city()));
            BusStation station =
                BusStation.from(stationId, values.get(columns.name()), longitude, latitude, number, region, null)
                .orElseThrow(StationSeedCsv::invalid);
            validateLengths(station);
            return station;
        }

        private static void validateCoordinates(final double longitude, final double latitude) {
            if (longitude <= -180 || longitude >= 180) {
                throw invalid();
            }
            if (latitude <= -85 || latitude >= 85) {
                throw invalid();
            }
        }

        private static String mobileNumberOf(String mobileNumber) {
            String number = mobileNumber.strip();
            if (number.matches("[0-9]{1,5}")) {
                return "0".repeat(5 - number.length()) + number;
            }
            return number;
        }

        private static void validateLengths(BusStation station) {
            if (station.name().length() > 80) {
                throw invalid();
            }
            if (station.mobileNo() != null && station.mobileNo().length() > 10) {
                throw invalid();
            }
            if (station.regionName() != null && station.regionName().length() > 20) {
                throw invalid();
            }
        }

        private void acceptDate(LocalDate observed) {
            if (dataDate != null && !dataDate.equals(observed)) {
                throw invalid();
            }
            dataDate = observed;
        }

        Seed toSeed(String digest) {
            if (stations.isEmpty()) {
                throw invalid();
            }
            return new Seed(dataDate, digest, stations);
        }
    }

    /**
     * 인용 필드의 쉼표·줄바꿈과 큰따옴표 이스케이프를 유지한다.
     */
    private static List<String> row(PushbackReader reader) throws IOException {
        CsvRow row = new CsvRow();
        boolean started = false;
        int character;
        while ((character = reader.read()) != -1) {
            started = true;
            if (row.isQuoted()) {
                row.appendQuoted(character, reader);
                row.validateSize();
                continue;
            }
            if (isDelimiter(character)) {
                row.finishField();
                if (character != ',') {
                    skipLineFeed(reader, character);
                    return row.values();
                }
                row.validateSize();
                continue;
            }
            row.appendUnquoted(character);
            row.validateSize();
        }
        return row.atEndOfFile(started);
    }

    private static boolean isDelimiter(final int character) {
        return character == ',' || isLineSeparator(character);
    }

    private static boolean isLineSeparator(final int character) {
        return character == '\n' || character == '\r';
    }

    private static void skipLineFeed(PushbackReader reader, final int character) throws IOException {
        if (character != '\r') {
            return;
        }
        final int next = reader.read();
        if (next != '\n' && next != -1) {
            reader.unread(next);
        }
    }

    private static final class CsvRow {

        private final List<String> values = new ArrayList<>();

        private final StringBuilder field = new StringBuilder();

        private boolean quoted;

        private boolean closed;

        boolean isQuoted() {
            return quoted;
        }

        void appendQuoted(final int character, PushbackReader reader) throws IOException {
            if (character != '"') {
                field.append((char) character);
                return;
            }
            final int next = reader.read();
            if (next == '"') {
                field.append('"');
                return;
            }
            quoted = false;
            closed = true;
            if (next != -1) {
                reader.unread(next);
            }
        }

        void appendUnquoted(final int character) {
            if (canOpenQuotedField(character)) {
                quoted = true;
                return;
            }
            if (closed || character == '"') {
                throw invalid();
            }
            field.append((char) character);
        }

        private boolean canOpenQuotedField(final int character) {
            if (character != '"') {
                return false;
            }
            return field.isEmpty() && !closed;
        }

        void finishField() {
            values.add(field.toString());
            field.setLength(0);
            closed = false;
        }

        void validateSize() {
            if (field.length() > 4096 || values.size() > 100) {
                throw invalid();
            }
        }

        List<String> values() {
            return values;
        }

        List<String> atEndOfFile(final boolean started) {
            if (quoted) {
                throw invalid();
            }
            if (!started) {
                return null;
            }
            finishField();
            return values;
        }
    }
}
