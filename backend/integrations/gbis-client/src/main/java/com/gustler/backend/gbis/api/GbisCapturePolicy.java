package com.gustler.backend.gbis.api;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Reject entire retained bodies rather than altering provider bytes or storing credential-bearing error text. */
public final class GbisCapturePolicy {
    static final int MAX_RESPONSE_BYTES = 16_000_000;
    private static final Set<String> FIELDS = Set.of("servicekey", "apikey", "authorization", "token",
        "accesstoken", "refreshtoken", "password", "passwd", "secret", "credential", "credentials", "key");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile(
        "(?i)(?:service[_-]?key|api[_-]?key|access[_-]?token|refresh[_-]?token|authorization|password|token|key)\\s*[:=]");
    private static final Pattern AUTH_VALUE = Pattern.compile("(?i)(?:\\bBearer\\s+\\S+|https?://[^/\\s]+@)");
    private GbisCapturePolicy() {}

    public static GbisResponseCapture screen(byte[] bytes, String secret, ObjectMapper json) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_RESPONSE_BYTES) return GbisResponseCapture.NONE;
        try {
            String text = new String(bytes, StandardCharsets.UTF_8);
            JsonNode tree = json.readTree(bytes);
            Set<String> secrets = secretVariants(secret);
            if (sensitiveText(text, secrets) || sensitiveTree(tree, secrets))
                return new GbisResponseCapture(GbisResponseCapture.Disposition.SENSITIVE_RESPONSE, null);
            return new GbisResponseCapture(GbisResponseCapture.Disposition.AVAILABLE, bytes);
        } catch (RuntimeException invalidJson) {
            return GbisResponseCapture.NONE;
        }
    }

    private static boolean sensitiveTree(JsonNode node, Set<String> secrets) {
        if (node == null) return false;
        if (node.isObject()) {
            for (var property : node.properties()) {
                String name = fieldName(property.getKey());
                if (FIELDS.contains(name) || sensitiveTree(property.getValue(), secrets)) return true;
            }
        } else if (node.isArray()) {
            for (JsonNode element : node) if (sensitiveTree(element, secrets)) return true;
        } else if (node.isString()) {
            return sensitiveText(node.asString(), secrets);
        }
        return false;
    }

    private static String fieldName(String name) {
        for (int i=0;i<2;i++) {
            try {
                String decoded=URLDecoder.decode(name,StandardCharsets.UTF_8);
                if(decoded.equals(name)) break;
                name=decoded;
            } catch(IllegalArgumentException malformed) { break; }
        }
        return name.replaceAll("[^A-Za-z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private static Set<String> secretVariants(String secret) {
        var secrets = new java.util.LinkedHashSet<String>();
        if (secret != null && !secret.isEmpty()) {
            String variant = secret;
            for (int i=0;i<3;i++) {
                secrets.add(variant);
                try {
                    String decoded=URLDecoder.decode(variant,StandardCharsets.UTF_8);
                    if(decoded.equals(variant)) break;
                    variant=decoded;
                } catch(IllegalArgumentException malformed) { break; }
            }
        }
        return Set.copyOf(secrets);
    }

    private static boolean sensitiveText(String text, Set<String> secrets) {
        String candidate = text;
        for (int i = 0; i < 3; i++) {
            boolean knownSecret = false;
            for (String variant : secrets) if (!variant.isEmpty() && candidate.contains(variant)) knownSecret = true;
            if (knownSecret
                    || CREDENTIAL_ASSIGNMENT.matcher(candidate).find() || AUTH_VALUE.matcher(candidate).find())
                return true;
            try {
                String decoded = URLDecoder.decode(candidate, StandardCharsets.UTF_8);
                if (decoded.equals(candidate)) break;
                candidate = decoded;
            } catch (IllegalArgumentException malformedEncoding) {
                break;
            }
        }
        return false;
    }
}
