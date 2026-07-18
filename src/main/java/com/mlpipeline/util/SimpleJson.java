package com.mlpipeline.util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Minimal, dependency-free JSON helper for flat (non-nested) objects.
 * We deliberately avoid pulling in Jackson/Gson so the whole project builds
 * with zero external dependencies (JDK 21 only).
 *
 * This is intentionally narrow in scope: it only needs to handle the small,
 * flat request/response payloads that cross the network boundaries in this
 * project (client -> gateway, gateway -> Python inference service).
 */
public final class SimpleJson {

    private SimpleJson() {}

    /** Parses a flat JSON object into a Map<String,Object> (String/Double/Boolean/null values). */
    public static Map<String, Object> parseObject(String json) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (json == null) return map;
        String s = json.trim();
        if (s.isEmpty()) return map;
        if (s.startsWith("{")) s = s.substring(1);
        if (s.endsWith("}")) s = s.substring(0, s.length() - 1);

        int i = 0;
        int n = s.length();
        while (i < n) {
            // skip whitespace / commas
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ',')) i++;
            if (i >= n) break;

            // parse key (must be a quoted string)
            if (s.charAt(i) != '"') break; // malformed - stop parsing gracefully
            int[] keyEnd = new int[1];
            String key = parseString(s, i, keyEnd);
            i = keyEnd[0];

            // skip whitespace and colon
            while (i < n && (Character.isWhitespace(s.charAt(i)) || s.charAt(i) == ':')) i++;

            // parse value
            if (i >= n) break;
            char c = s.charAt(i);
            Object value;
            if (c == '"') {
                int[] valEnd = new int[1];
                value = parseString(s, i, valEnd);
                i = valEnd[0];
            } else if (s.startsWith("true", i)) {
                value = Boolean.TRUE;
                i += 4;
            } else if (s.startsWith("false", i)) {
                value = Boolean.FALSE;
                i += 5;
            } else if (s.startsWith("null", i)) {
                value = null;
                i += 4;
            } else {
                // number
                int start = i;
                while (i < n && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.' || s.charAt(i) == '-' || s.charAt(i) == '+' || s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                    i++;
                }
                String numStr = s.substring(start, i);
                try {
                    value = Double.parseDouble(numStr);
                } catch (NumberFormatException e) {
                    value = numStr;
                }
            }
            map.put(key, value);
        }
        return map;
    }

    /** Parses a JSON string starting at index i (which must point at the opening quote). */
    private static String parseString(String s, int i, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        int n = s.length();
        i++; // skip opening quote
        while (i < n && s.charAt(i) != '"') {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < n) {
                char next = s.charAt(i + 1);
                switch (next) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 't': sb.append('\t'); break;
                    case 'r': sb.append('\r'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 5 < n) {
                            String hex = s.substring(i + 2, i + 6);
                            sb.append((char) Integer.parseInt(hex, 16));
                            i += 4;
                        }
                        break;
                    default: sb.append(next);
                }
                i += 2;
            } else {
                sb.append(c);
                i++;
            }
        }
        endOut[0] = i + 1; // skip closing quote
        return sb.toString();
    }

    public static String escape(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
