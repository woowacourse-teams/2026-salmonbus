package com.gustler.backend.routecatalog.infrastructure.file;

import com.gustler.backend.routecatalog.domain.StopCoordinates;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDataset;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDatasetReader;
import com.gustler.backend.routecatalog.domain.walking.StopWalkDistance;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code station_a,station_b,a_x,a_y,b_x,b_y,walk_m} 머리줄의 {@code csv.gz}와 메타데이터 {@code json}을 읽는다.
 * 묶음의 신원은 압축 파일 바이트의 SHA-256이다. 메타데이터의 {@code sha256}·{@code pairCount}가 압축 파일과 맞지 않으면
 * 두 파일이 서로 다른 묶음인 것으로 보고 읽지 않는다.
 */
@Component
public class StopWalkDatasetFileReader implements StopWalkDatasetReader {
    private static final String HEADER = "station_a,station_b,a_x,a_y,b_x,b_y,walk_m";
    private final ObjectMapper json = new ObjectMapper();

    @Override
    public StopWalkDataset read(Path distances, Path metadata) {
        try {
            byte[] bytes = Files.readAllBytes(distances);
            JsonNode node = json.readTree(Files.readString(metadata, StandardCharsets.UTF_8));
            String checksum = sha256(bytes);
            if (!checksum.equals(node.path("sha256").asString(""))) {
                throw new IllegalStateException("도보 거리 묶음의 지문이 메타데이터와 다르다: " + checksum);
            }
            List<StopWalkDistance> pairs = parse(bytes);
            if (pairs.size() != node.path("pairCount").asInt(-1)) {
                throw new IllegalStateException("도보 거리 묶음의 쌍 수가 메타데이터와 다르다: " + pairs.size());
            }
            return new StopWalkDataset(checksum, OffsetDateTime.parse(node.get("osmBase").asString()), pairs);
        } catch (IOException failure) {
            throw new UncheckedIOException("도보 거리 묶음을 읽지 못했다", failure);
        }
    }

    private static List<StopWalkDistance> parse(byte[] bytes) throws IOException {
        List<StopWalkDistance> pairs = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            new GZIPInputStream(new ByteArrayInputStream(bytes)), StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!HEADER.equals(header)) {
                throw new IllegalStateException("도보 거리 묶음의 머리줄이 다르다: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                String[] cells = line.split(",", -1);
                if (cells.length != 7) {
                    throw new IllegalStateException("도보 거리 묶음의 칸 수가 다르다: " + line);
                }
                pairs.add(new StopWalkDistance(cells[0], cells[1],
                    new StopCoordinates(Double.parseDouble(cells[2]), Double.parseDouble(cells[3])),
                    new StopCoordinates(Double.parseDouble(cells[4]), Double.parseDouble(cells[5])),
                    Integer.parseInt(cells[6])));
            }
        }
        return pairs;
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
