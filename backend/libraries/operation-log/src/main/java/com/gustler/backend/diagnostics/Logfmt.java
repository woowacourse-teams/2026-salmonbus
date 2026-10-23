package com.gustler.backend.diagnostics;

/** logfmt 문자열 값. 한 이벤트가 항상 한 줄에 남도록 제어 문자도 이스케이프한다. */
public final class Logfmt {
    private Logfmt() {
    }

    public static String quote(String value) {
        if (value == null) {
            return "\"\"";
        }
        StringBuilder result = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            final char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (Character.isISOControl(c)) {
                        result.append(String.format("\\u%04x", (int) c));
                        continue;
                    }
                    result.append(c);
                }
            }
        }
        return result.append('"').toString();
    }
}
