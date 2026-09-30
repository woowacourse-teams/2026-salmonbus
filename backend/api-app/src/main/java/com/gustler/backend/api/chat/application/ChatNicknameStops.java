package com.gustler.backend.api.chat.application;

import java.util.List;
import java.util.regex.Pattern;

public final class ChatNicknameStops {

    private static final Pattern BRACKETS = Pattern.compile("\\([^)]*\\)|\\[[^\\]]*]");
    private static final String FRONT_SUFFIX = "앞";
    private static final int SHORT_NAME_MAX_LENGTH = 7;
    private static final int ENOUGH_SHORT_NAMES = 8;

    private ChatNicknameStops() {
    }

    public static List<String> from(List<String> stopNames) {
        List<String> names = stopNames.stream()
            .map(ChatNicknameStops::clean)
            .filter(name -> !name.isEmpty())
            .distinct()
            .toList();
        List<String> shortNames = names.stream()
            .filter(name -> name.codePointCount(0, name.length()) <= SHORT_NAME_MAX_LENGTH)
            .toList();
        return shortNames.size() >= ENOUGH_SHORT_NAMES ? shortNames : names;
    }

    static String clean(String stopName) {
        String name = BRACKETS.matcher(stopName).replaceAll("");
        int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        name = name.strip();
        if (name.length() > FRONT_SUFFIX.length() && name.endsWith(FRONT_SUFFIX)) {
            name = name.substring(0, name.length() - FRONT_SUFFIX.length());
        }
        return name.strip();
    }
}
