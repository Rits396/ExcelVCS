package com.Excel.VCS.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.Inflater;
import java.util.zip.InflaterOutputStream;

/**
 * Utility class for VCS commit operations
 */
public class CommitUtils {

    private static final Logger logger = LoggerFactory.getLogger(CommitUtils.class);

    /**
     * Decompress zlib-compressed data
     */
    public static byte[] zlibDecompress(byte[] compressedData) {
        logger.debug("Decompressing {} bytes of zlib data", compressedData.length);

        try {
            Inflater inflater = new Inflater();
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();

            try (InflaterOutputStream inflaterStream = new InflaterOutputStream(outputStream, inflater)) {
                inflaterStream.write(compressedData);
            }

            byte[] result = outputStream.toByteArray();
            logger.debug("Decompressed to {} bytes", result.length);

            return result;

        } catch (IOException e) {
            logger.error("Failed to decompress zlib data", e);
            throw new RuntimeException("Failed to decompress zlib data", e);
        }
    }

    /**
     * Parse Git object header to extract type and size
     */
    public static GitObjectHeader parseGitObjectHeader(byte[] data) {
        String content = new String(data, StandardCharsets.UTF_8);

        // Find the null terminator
        int nullIndex = content.indexOf('\0');
        if (nullIndex == -1) {
            throw new IllegalArgumentException("Invalid Git object format - no null terminator found");
        }

        String header = content.substring(0, nullIndex);
        String[] parts = header.split(" ", 2);

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid Git object header format: " + header);
        }

        String type = parts[0];
        long size;
        try {
            size = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid size in Git object header: " + parts[1]);
        }

        return new GitObjectHeader(type, size, nullIndex + 1);
    }

    /**
     * Git object header information
     */
    public static class GitObjectHeader {
        private final String type;
        private final long size;
        private final int contentOffset;

        public GitObjectHeader(String type, long size, int contentOffset) {
            this.type = type;
            this.size = size;
            this.contentOffset = contentOffset;
        }

        public String getType() { return type; }
        public long getSize() { return size; }
        public int getContentOffset() { return contentOffset; }

        @Override
        public String toString() {
            return String.format("GitObjectHeader{type='%s', size=%d, contentOffset=%d}",
                    type, size, contentOffset);
        }
    }

    /**
     * Convert column number to Excel column letters (A, B, ..., Z, AA, AB, etc.)
     */
    public static String numberToColumnLetters(int columnNumber) {
        if (columnNumber <= 0) {
            throw new IllegalArgumentException("Column number must be positive");
        }

        StringBuilder result = new StringBuilder();
        while (columnNumber > 0) {
            columnNumber--; // Make it 0-based
            result.insert(0, (char) ('A' + (columnNumber % 26)));
            columnNumber /= 26;
        }
        return result.toString();
    }

    /**
     * Convert Excel column letters to column number (A=1, B=2, ..., Z=26, AA=27, etc.)
     */
    public static int columnLettersToNumber(String columnLetters) {
        if (columnLetters == null || columnLetters.isEmpty()) {
            throw new IllegalArgumentException("Column letters cannot be null or empty");
        }

        columnLetters = columnLetters.toUpperCase();
        int result = 0;

        for (int i = 0; i < columnLetters.length(); i++) {
            char c = columnLetters.charAt(i);
            if (c < 'A' || c > 'Z') {
                throw new IllegalArgumentException("Invalid column letter: " + c);
            }
            result = result * 26 + (c - 'A' + 1);
        }

        return result;
    }

    /**
     * Format timestamp for Git commit display
     */
    public static String formatCommitTimestamp(long timestamp) {
        java.time.Instant instant = java.time.Instant.ofEpochSecond(timestamp);
        java.time.format.DateTimeFormatter formatter = java.time.format.DateTimeFormatter
                .ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(java.time.ZoneOffset.UTC);
        return formatter.format(instant);
    }

    /**
     * Validate Git object hash format
     */
    public static boolean isValidGitHash(String hash) {
        if (hash == null || hash.length() != 40) {
            return false;
        }

        return hash.matches("[0-9a-f]{40}");
    }

    /**
     * Truncate Git hash for display
     */
    public static String truncateHash(String hash, int length) {
        if (hash == null) {
            return null;
        }
        if (length <= 0 || length >= hash.length()) {
            return hash;
        }
        return hash.substring(0, length);
    }
}

// Additional DTO class for commit information
