package com.sms.extraction.util;

import com.sms.extraction.domain.ExtractedValue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;

public final class HashUtil {

    private HashUtil() {
        // utility class — no instances
    }

    /**
     * Returns the SHA-256 hex digest of the given input string.
     */
    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hashBytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Computes the static text hash for template deduplication (Option 1 lookup key).
     *
     * Algorithm:
     * 1. Take the normalized SMS.
     * 2. Remove each extracted value string from the SMS (by value text, not position,
     *    to stay deterministic and position-independent).
     * 3. Remove all spaces.
     * 4. SHA-256 the resulting string.
     */
    public static String staticTextHash(String normalizedSms, List<ExtractedValue> values) {
        String text = normalizedSms;
        for (ExtractedValue ev : values) {
            if (ev.getValue() != null && !ev.getValue().isEmpty()) {
                text = text.replace(ev.getValue(), "");
            }
        }
        // Remove all spaces
        text = text.replace(" ", "");
        return sha256(text);
    }

    /**
     * Computes the placeholder sequence hash for template matching (Option 2 lookup key).
     *
     * Algorithm:
     * Join entity names in order of appearance with ":" separator, then SHA-256.
     * Example: ["ACCT", "AMOUNT"] → "ACCT:AMOUNT" → SHA-256
     */
    public static String placeholderSequenceHash(List<String> entityNamesInOrder) {
        String joined = String.join(":", entityNamesInOrder);
        return sha256(joined);
    }
}
