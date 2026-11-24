package com.gustler.backend.forecasting.domain.model.alighting;

import static java.util.Collections.unmodifiableList;

import com.gustler.backend.forecasting.domain.model.ObservedVehicle;
import com.gustler.backend.forecasting.support.Baseline;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 고정 시드 입력에서 차량마다 입력 계산이 내야 하는 결과의 고정 기대값이다({@link Baseline}). 값은 기준 구현(dev
 * {@code e98ae3ea}의 입력 계산)이 같은 입력에 낸 것이다. 그 구현은 저장소에 남아 있지 않아 파일을 다시 만들 수 없으므로, 기대값을
 * 쓰는 테스트가 노선·관측을 만드는 흐름과 난수를 뽑는 순서를 바꾸면 기대값과 견줄 수 없다. 입력 계산은 지수·로그·사인 함수를
 * 거치지 않아 어느 JVM에서나 비트까지 견준다.
 * <ul>
 *   <li>{@code e 번호 예외 메시지…}: 거부 사유. 번호는 한 글자다. {@code NullPointerException}은 JVM이 문구를 만들어 메시지를
 *       적지 않고 종류만 견준다</li>
 *   <li>{@code x 차량 거부번호}: 만들 때 거부되는 차량</li>
 *   <li>{@code s 차량 운행시작관측 목표별결과 지문}: 만들어지는 차량. 운행 시작 관측을 내지 못하면 그 칸이 {@code !거부번호}다.
 *       목표별 결과는 견주는 목표를 작은 것부터 한 글자씩 적은 것으로 {@code v}는 입력 벡터, 그 밖은 거부 번호다. 지문은
 *       {@link #digest}다</li>
 * </ul>
 */
final class FeatureSnapshotBaseline {

    private static final char VECTOR = 'v';

    private final Map<String, Rejection> rejections = new HashMap<>();
    private final Map<String, Expected> vehicles = new HashMap<>();
    private final Set<String> unread = new HashSet<>();

    private FeatureSnapshotBaseline() {
    }

    static FeatureSnapshotBaseline read(String name) {
        FeatureSnapshotBaseline baseline = new FeatureSnapshotBaseline();
        for (String[] line : Baseline.lines(name)) {
            baseline.accept(line);
        }
        return baseline;
    }

    private void accept(String[] line) {
        switch (line[0]) {
            case "e" -> rejections.put(line[1], rejection(line));
            case "x" -> put(
                line[1],
                new Expected(
                        rejection(line[2]),
                        null,
                        null,
                        List.of(),
                        null
                )
            );
            case "s" -> put(line[1], constructed(line));
            default -> throw new IllegalStateException("모르는 줄: " + line[0]);
        }
    }

    private static Rejection rejection(String[] line) {
        if (line.length == 3) {
            return new Rejection(line[2], null);
        }
        return new Rejection(
            line[2],
            String.join(" ", Arrays.copyOfRange(line, 3, line.length))
        );
    }

    private Expected constructed(String[] line) {
        List<Rejection> targets = new ArrayList<>();
        for (final char code : line[3].toCharArray()) {
            targets.add(targetRejection(code));
        }
        if (line[2].startsWith("!")) {
            return new Expected(
                    null,
                    null,
                    rejection(line[2].substring(1)),
                    unmodifiableList(targets),
                    line[4]
            );
        }
        return new Expected(
                null,
                Long.valueOf(line[2]),
                null,
                unmodifiableList(targets),
                line[4]
        );
    }

    private Rejection targetRejection(final char code) {
        if (code == VECTOR) {
            return null;
        }
        return rejection(String.valueOf(code));
    }

    private Rejection rejection(String code) {
        Rejection rejection = rejections.get(code);
        if (rejection == null) {
            throw new IllegalStateException("모르는 거부 번호: " + code);
        }
        return rejection;
    }

    private void put(String vehicle, Expected expected) {
        if (vehicles.put(vehicle, expected) != null) {
            throw new IllegalStateException("차량이 두 번 나온다: " + vehicle);
        }
        unread.add(vehicle);
    }

    /** 그 차량의 기대값. 기대값에 없는 차량이면 예외다. */
    Expected vehicle(String vehicle) {
        Expected expected = vehicles.get(vehicle);
        if (expected == null) {
            throw new IllegalStateException("기대값에 없는 차량: " + vehicle);
        }
        unread.remove(vehicle);
        return expected;
    }

    /** {@link #vehicle}로 아직 읽지 않은 차량. */
    Set<String> unread() {
        return Set.copyOf(unread);
    }

    /**
     * 차량 하나의 값을 SHA-256으로 줄인다. 관측 값·정원·기준 관측 번호·기준 관측을 적은 글({@code values})에 이어 목표 순서대로
     * 입력 벡터를 넣는다. 벡터가 없는 목표는 없음 표시만 넣는다. 실수는 {@link Double#doubleToLongBits}의 비트로 넣는다.
     */
    static String digest(String values, List<AlightingAwareFeatureVector> vectors) {
        MessageDigest digest = Baseline.sha256();
        digest.update(values.getBytes(StandardCharsets.UTF_8));
        for (AlightingAwareFeatureVector vector : vectors) {
            if (vector == null) {
                digest.update((byte) 0);
                continue;
            }
            digest.update((byte) 1);
            doubles(digest, vector.seat88());
            doubles(digest, vector.probability80());
        }
        return Baseline.hex(digest);
    }

    private static void doubles(MessageDigest digest, double[] values) {
        ByteBuffer bytes = ByteBuffer.allocate(values.length * Long.BYTES);
        for (final double value : values) {
            bytes.putLong(Double.doubleToLongBits(value));
        }
        digest.update(bytes.array());
    }

    static String text(ObservedVehicle observation) {
        return String.join(
                "/",
                observation.vehicleId(),
                Long.toString(observation.routeVersionId()),
                Integer.toString(observation.passedStopOrder()),
                String.valueOf(observation.observedAt()),
                String.valueOf(observation.remainingSeats()),
                String.valueOf(observation.crowdLevel())
        );
    }

    static String text(AlightingAwareObservation observation) {
        return String.join(
                "/",
                Long.toString(observation.observationId()),
                Long.toString(observation.batchId()),
                Long.toString(observation.batchRank()),
                String.valueOf(observation.receivedAt()),
                observation.vehicleId(),
                Integer.toString(observation.passedStopOrder()),
                Integer.toString(observation.stopOrder()),
                Integer.toString(observation.state()),
                String.valueOf(observation.seats()),
                Integer.toString(observation.crowd()),
                Long.toString(observation.routeVersionId()),
                String.valueOf(observation.sourceReceivedAt()),
                Boolean.toString(observation.down())
        );
    }

    /**
     * @param type 예외 클래스 이름
     * @param message 예외 메시지. {@code NullPointerException}이면 비어 있고 견주지 않는다
     */
    record Rejection(String type, String message) {

        boolean matches(RuntimeException error) {
            if (!error.getClass().getName().equals(type)) {
                return false;
            }
            return type.equals(NullPointerException.class.getName()) || Objects.equals(message, error.getMessage());
        }
    }

    /**
     * @param rejection 만들 때의 거부 사유. 만들어지는 차량은 비어 있다
     * @param tripStart 운행 시작 관측. 내지 못하면 비어 있다
     * @param tripStartRejection 운행 시작 관측을 내지 못하는 사유. 내면 비어 있다
     * @param targets 견주는 목표를 작은 것부터 늘어놓은 순서의 거부 사유. 입력 벡터를 내는 목표는 비어 있다
     * @param digest 차량의 값 지문
     */
    record Expected(
        Rejection rejection,
        Long tripStart,
        Rejection tripStartRejection,
        List<Rejection> targets,
        String digest
    ) {

    }
}
