package com.Excel.VCS.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Deflater;

@Service
public class AddService {

    private static final Logger logger = LoggerFactory.getLogger(AddService.class);

    private final WorkbookService workbookService;
    private final IndexService indexService;

    public AddService(WorkbookService workbookService, IndexService indexService) {
        this.workbookService = workbookService;
        this.indexService = indexService;
    }

    public Map<String, Object> add(String workbookId, int sheetNumber, String row, int col) {
        logger.info("Starting add operation for workbook: {}, sheet: {}, row: {}, col: {}",
                workbookId, sheetNumber, row, col);

        try {
            // Validate inputs
            validateInputs(workbookId, sheetNumber, row, col);

            // Read the file
            logger.debug("Retrieving cell value from workbook service");
            String value = workbookService.getCellValue(workbookId, sheetNumber, row, col);
            logger.debug("Retrieved cell value: '{}'", value);

            if (value == null) {
                logger.warn("Cell value is null, setting to empty string");
                value = "";
            }

            byte[] contentBytes = value.getBytes(StandardCharsets.UTF_8);
            logger.debug("Content bytes length: {}", contentBytes.length);

            // Prepend a header
            String header = "blob " + contentBytes.length + "\0";
            byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
            logger.debug("Header created: 'blob {}\\0'", contentBytes.length);

            // Step 3: Combine header + content
            byte[] fullBlob = new byte[headerBytes.length + contentBytes.length];
            System.arraycopy(headerBytes, 0, fullBlob, 0, headerBytes.length);
            System.arraycopy(contentBytes, 0, fullBlob, headerBytes.length, contentBytes.length);
            logger.debug("Full blob size: {} bytes", fullBlob.length);

            // Step 4: Calculate SHA-256 hash
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            byte[] hashBytes = sha256.digest(fullBlob);
            String blobId = bytesToHex(hashBytes);
            logger.info("Generated blob ID: {} (length: {})", blobId, blobId.length());

            // Validate blob ID before substring operations
            if (blobId.length() < 3) {
                logger.error("Generated blob ID is too short: {}", blobId);
                throw new RuntimeException("Generated blob ID is too short: " + blobId);
            }

            // Step 5: Compress the blob
            logger.debug("Compressing blob data");
            byte[] compressedData = zlibCompress(fullBlob);
            logger.debug("Compressed data size: {} bytes (compression ratio: {:.2f}%)",
                    compressedData.length, (double) compressedData.length / fullBlob.length * 100);

            // Step 6: Store the blob
            logger.debug("Storing blob in Git objects directory");
            storeBlobInGitObjects(blobId, compressedData);

            // Step 7: Add to index (staging area)
            logger.debug("Adding blob to index");
            indexService.addToIndex(workbookId, sheetNumber, row, col, blobId,
                    fullBlob.length, compressedData.length);

            // Step 8: Create result
            Map<String, Object> result = createResult(blobId, row, col, value,
                    fullBlob.length, compressedData.length);

            // Add staging information
            result.put("staged", true);
            result.put("stagingTimestamp", System.currentTimeMillis());

            logger.info("Successfully added cell to staging area. Blob ID: {}", blobId);

            return result;

        } catch (NoSuchAlgorithmException e) {
            logger.error("SHA-256 algorithm not available", e);
            throw new RuntimeException("SHA-256 algorithm not available", e);
        } catch (Exception e) {
            logger.error("Failed to add cell to staging area: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add cell: " + e.getMessage(), e);
        }
    }

    /**
     * Check if a cell is already staged
     */
    public boolean isCellStaged(String workbookId, int sheetNumber, String row, int col) {
        return indexService.isStaged(workbookId, sheetNumber, row, col);
    }

    /**
     * Remove a cell from staging area
     */
    public boolean removeCellFromStaging(String workbookId, int sheetNumber, String row, int col) {
        logger.info("Removing cell from staging: workbook={}, sheet={}, cell={}{}",
                workbookId, sheetNumber, row, col);

        try {
            boolean removed = indexService.removeFromIndex(workbookId, sheetNumber, row, col);
            if (removed) {
                logger.info("Successfully removed cell from staging area");
            } else {
                logger.warn("Cell was not found in staging area");
            }
            return removed;
        } catch (Exception e) {
            logger.error("Failed to remove cell from staging area", e);
            throw new RuntimeException("Failed to remove cell from staging: " + e.getMessage(), e);
        }
    }

    private void validateInputs(String workbookId, int sheetNumber, String row, int col) {
        logger.debug("Validating input parameters");

        if (workbookId == null || workbookId.trim().isEmpty()) {
            logger.error("Workbook ID is null or empty");
            throw new IllegalArgumentException("Workbook ID cannot be null or empty");
        }
        if (row == null || row.trim().isEmpty()) {
            logger.error("Row is null or empty");
            throw new IllegalArgumentException("Row cannot be null or empty");
        }
        if (col < 0) {
            logger.error("Column is negative: {}", col);
            throw new IllegalArgumentException("Column must be non-negative");
        }
        if (sheetNumber < 0) {
            logger.error("Sheet number is negative: {}", sheetNumber);
            throw new IllegalArgumentException("Sheet number must be non-negative");
        }

        logger.debug("Input validation successful");
    }

    private Map<String, Object> createResult(String blobId, String row, int col, String value,
                                             int originalSize, int compressedSize) {
        Map<String, Object> result = new HashMap<>();
        result.put("blobId", blobId);
        result.put("cellAddress", row + col);
        result.put("originalSize", originalSize);
        result.put("compressedSize", compressedSize);
        result.put("compressionRatio", (double) compressedSize / originalSize);
        result.put("value", value);
        result.put("storagePath", ".git/objects/" + blobId.substring(0, 2) + "/" + blobId.substring(2));

        return result;
    }

    private void storeBlobInGitObjects(String blobId, byte[] compressedData) {
        logger.debug("Storing blob with ID: {}", blobId);

        try {
            if (blobId == null || blobId.length() < 3) {
                logger.error("Invalid blob ID for storage: {}", blobId);
                throw new RuntimeException("Invalid blob ID: " + blobId);
            }

            String dirName = blobId.substring(0, 2);
            String fileName = blobId.substring(2);
            logger.debug("Storage path - directory: {}, filename: {}", dirName, fileName);

            // Create directory structure and store file
            Path objectsDir = Paths.get(".VCS", "objects");
            Path subDir = objectsDir.resolve(dirName);
            Path filePath = subDir.resolve(fileName);

            Files.createDirectories(subDir);
            logger.debug("Created directories: {}", subDir);

            if (!Files.exists(filePath)) {
                Files.write(filePath, compressedData);
                logger.info("Stored new blob at: {}", filePath);
            } else {
                logger.info("Blob already exists at: {}", filePath);
            }

        } catch (Exception e) {
            logger.error("Failed to store blob: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to store blob: " + blobId, e);
        }
    }

    private static byte[] zlibCompress(byte[] data) {
        Logger compressLogger = LoggerFactory.getLogger(AddService.class.getName() + ".compress");
        compressLogger.debug("Compressing {} bytes of data", data.length);

        try {
            Deflater deflater = new Deflater();
            deflater.setInput(data);
            deflater.finish();

            byte[] buffer = new byte[data.length * 2];
            int compressedSize = deflater.deflate(buffer);
            deflater.end();

            byte[] result = new byte[compressedSize];
            System.arraycopy(buffer, 0, result, 0, compressedSize);

            compressLogger.debug("Compressed to {} bytes", compressedSize);
            return result;

        } catch (Exception e) {
            compressLogger.error("Compression failed", e);
            throw new RuntimeException("Compression failed", e);
        }
    }

    static String bytesToHex(byte[] bytes) {
        Logger hexLogger = LoggerFactory.getLogger(AddService.class.getName() + ".hex");

        if (bytes == null || bytes.length == 0) {
            hexLogger.error("Cannot convert null or empty byte array to hex");
            throw new RuntimeException("Cannot convert null or empty byte array to hex");
        }

        hexLogger.debug("Converting {} bytes to hex", bytes.length);

        final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            result.append(HEX_CHARS[(b >> 4) & 0xF]);
            result.append(HEX_CHARS[b & 0xF]);
        }

        String hexString = result.toString();
        hexLogger.debug("Generated hex string length: {}", hexString.length());

        return hexString;
    }
}