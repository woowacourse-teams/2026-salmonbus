package com.gustler.backend.forecasting.support;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;

/**
 * 테스트 자원 {@code baseline/}에 적어 둔 고정 기대값을 읽는다. 값은 기준 구현(dev {@code e98ae3ea}의 입력 계산·입력 조회·발행·정산)이
 * 같은 입력에 낸 것이다. 그 구현은 저장소에 남아 있지 않아 파일을 다시 만들 수 없으므로, 기대값을 쓰는 테스트가 입력을 만드는
 * 흐름(관측 흐름, 시나리오)과 난수 순서를 바꾸면 기대값과 견줄 수 없다. 한 줄이 한 항목이고 칸은 공백 하나로 나눈다.
 * {@code #}로 시작하는 줄은 설명이다.
 *
 * <p>실수는 {@link Double#doubleToLongBits}의 16진수로 적어 비트를 그대로 둔다. 지수·로그·사인 함수는 JVM마다 마지막 비트가
 * 다를 수 있어, 그 함수를 거친 값(예측값, 정답 값)의 파일은 기록한 JVM의 함수 지문({@code math} 줄)을 함께 담는다. 지문이
 * 같은 JVM에서는 비트까지 견주고, 다른 JVM에서는 {@value #RELATIVE_TOLERANCE}의 상대 오차 안에서 견준다. 그 함수를 거치지
 * 않는 값(관측 번호, 상태, 사유, 수, 입력 벡터)은 어디서나 그대로 견준다.
 */
public final class Baseline {
    public static final double RELATIVE_TOLERANCE = 1e-9;
    private static final int PROBES = 50_000;
    private static final String CURRENT_MATH = fingerprint();

    private Baseline() {
    }

    /** 설명 줄과 빈 줄을 뺀 줄을 칸으로 나눠 돌려준다. */
    public static List<String[]> lines(String name) {
        String path = "/baseline/" + name + ".txt";
        try (InputStream stream = Baseline.class.getResourceAsStream(path)) {
            if (stream == null) {
                throw new IllegalStateException("기대값 파일이 없다: " + path);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8));
            List<String[]> lines = new ArrayList<>();
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    lines.add(line.split(" "));
                }
            }
            return lines;
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    public static String hex(final double value) {
        return Long.toHexString(Double.doubleToLongBits(value));
    }

    public static double value(String hex) {
        return Double.longBitsToDouble(Long.parseUnsignedLong(hex, 16));
    }

    /** 이 JVM의 지수·로그·사인 함수가 기록한 JVM과 같은 값을 내면 참이다. */
    public static boolean sameMath(String recordedFingerprint) {
        return CURRENT_MATH.equals(recordedFingerprint);
    }

    public static String currentMath() {
        return CURRENT_MATH;
    }

    /** 함수 지문이 같으면 비트가 같은지, 다르면 상대 오차 안인지 본다. */
    public static boolean sameValue(final double expected, final double actual, final boolean exact) {
        if (Double.doubleToLongBits(expected) == Double.doubleToLongBits(actual)) {
            return true;
        }
        if (exact) {
            return false;
        }
        final double scale = Math.max(1, Math.max(Math.abs(expected), Math.abs(actual)));
        return Math.abs(expected - actual) <= RELATIVE_TOLERANCE * scale;
    }

    public static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String hex(MessageDigest digest) {
        return HexFormat.of().formatHex(digest.digest());
    }

    private static String fingerprint() {
        Random random = new Random(20261115L);
        MessageDigest digest = sha256();
        ByteBuffer values = ByteBuffer.allocate(3 * Long.BYTES);
        for (int probe = 0; probe < PROBES; probe++) {
            final double exponent = random.nextDouble() * 80 - 40;
            final double positive = random.nextDouble() * 1000 + Double.MIN_NORMAL;
            final double angle = random.nextDouble() * 20 - 10;
            values.clear();
            values.putLong(Double.doubleToLongBits(Math.exp(exponent)));
            values.putLong(Double.doubleToLongBits(Math.log(positive)));
            values.putLong(Double.doubleToLongBits(Math.sin(angle)));
            digest.update(values.array());
        }
        return hex(digest).substring(0, 16);
    }
}
