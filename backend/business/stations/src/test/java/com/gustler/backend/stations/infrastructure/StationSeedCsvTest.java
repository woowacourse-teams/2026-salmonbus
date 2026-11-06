package com.gustler.backend.stations.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.SoftAssertions.assertSoftly;

import com.gustler.backend.stations.domain.BusStation;
import com.gustler.backend.stations.infrastructure.csv.StationSeedCsv;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StationSeedCsvTest {
    private static final String HEADER =
        "정류장번호,정류장명,위도,경도,정보수집일,모바일단축번호,도시코드,도시명,관리도시명";

    @TempDir
    private Path directory;

    @Test
    void BOM과_CRLF를_읽고_인용_필드의_쉼표와_따옴표와_개행을_보존한다() throws Exception {
        // given
        String body = "\uFEFF" + HEADER + "\r\n"
            + "GGB206000532,\"판교,\"\"역\"\"\r\n승강장\",37.39,127.11,2026-10-20,7489,31020,경기도 성남시,경기BIS\r\n";
        Path path = writeCsv(body);

        // when
        StationSeedCsv.Seed seed = StationSeedCsv.read(path, StandardCharsets.UTF_8);

        // then
        assertThat(seed.stations()).hasSize(1);
        BusStation station = seed.stations().getFirst();
        assertSoftly(softly -> {
            softly.assertThat(station.stationId()).isEqualTo("206000532");
            softly.assertThat(station.name()).isEqualTo("판교,\"역\"\r\n승강장");
            softly.assertThat(station.mobileNo()).isEqualTo("07489");
            softly.assertThat(station.regionName()).isEqualTo("성남");
            softly.assertThat(seed.date()).isEqualTo(LocalDate.of(2026, 10, 20));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"판교역\"뒤문자", "\"판교역"})
    void 닫힌_인용_필드_뒤_문자나_미종결_인용_필드는_거부한다(String name) throws Exception {
        // given
        Path path = writeCsv(HEADER + "\n" + row(name, "경기도 성남시"));

        // when
        Throwable failure = catchThrowable(() -> StationSeedCsv.read(path, StandardCharsets.UTF_8));

        // then
        assertThat(failure).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void gzip을_풀어_읽고_압축된_원본_파일의_해시를_기록한다() throws Exception {
        // given
        byte[] compressed = gzip(HEADER + "\n" + row("판교역", "경기도 성남시"));
        Path path = directory.resolve("stations.csv.gz");
        Files.write(path, compressed);
        String expectedHash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(compressed));

        // when
        StationSeedCsv.Seed seed = StationSeedCsv.read(path, StandardCharsets.UTF_8);

        // then
        assertSoftly(softly -> {
            softly.assertThat(seed.stations()).extracting(BusStation::stationId).containsExactly("206000532");
            softly.assertThat(seed.sha256()).isEqualTo(expectedHash);
        });
    }

    @Test
    void 빈_지역명과_빈_줄을_허용하고_지역명을_없는_값으로_둔다() throws Exception {
        // given
        Path path = writeCsv(HEADER + "\n\n" + row("판교역", "") + "\n");

        // when
        StationSeedCsv.Seed seed = StationSeedCsv.read(path, StandardCharsets.UTF_8);

        // then
        assertThat(seed.stations()).singleElement().satisfies(station -> {
            assertSoftly(softly -> {
                softly.assertThat(station.name()).isEqualTo("판교역");
                softly.assertThat(station.regionName()).isNull();
            });
        });
    }

    @Test
    void 파일_끝에_개행이_없어도_마지막_행을_읽는다() throws Exception {
        // given
        Path path = writeCsv(HEADER + "\n" + row("판교역", "경기도 성남시").stripTrailing());

        // when
        StationSeedCsv.Seed seed = StationSeedCsv.read(path, StandardCharsets.UTF_8);

        // then
        assertThat(seed.stations()).extracting(BusStation::stationId).containsExactly("206000532");
    }

    private Path writeCsv(String body) throws IOException {
        Path path = directory.resolve("stations.csv");
        Files.writeString(path, body, StandardCharsets.UTF_8);
        return path;
    }

    private static String row(String name, String city) {
        return "GGB206000532," + name + ",37.39,127.11,2026-10-20,7489,31020," + city + ",경기BIS\n";
    }

    private static byte[] gzip(String body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream compressed = new GZIPOutputStream(bytes)) {
            compressed.write(body.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
