package com.Excel.VCS.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Service
public class IndexService {

    private static final Logger logger = LoggerFactory.getLogger(IndexService.class);
    private static final String INDEX_FILE_PATH = ".git/index";
    private static final String TEMP_INDEX_SUFFIX = ".tmp";
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    /**
     * Index entry representing a staged cell change
     */
    public static class IndexEntry {
        private String workbookId;
        private int sheetNumber;
        private String row;
        private int col;
        private String blobId;
        private long timestamp;
        private int originalSize;
        private int compressedSize;
        private String cellAddress;

        // Constructors
        public IndexEntry() {}

        public IndexEntry(String workbookId, int sheetNumber, String row, int col,
                          String blobId, int originalSize, int compressedSize) {
            this.workbookId = workbookId;
            this.sheetNumber = sheetNumber;
            this.row = row;
            this.col = col;
            this.blobId = blobId;
            this.timestamp = Instant.now().getEpochSecond();
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
            this.cellAddress = row + col;
        }

        // Getters and Setters
        public String getWorkbookId() { return workbookId; }
        public void setWorkbookId(String workbookId) { this.workbookId = workbookId; }

        public int getSheetNumber() { return sheetNumber; }
        public void setSheetNumber(int sheetNumber) { this.sheetNumber = sheetNumber; }

        public String getRow() { return row; }
        public void setRow(String row) { this.row = row; }

        public int getCol() { return col; }
        public void setCol(int col) { this.col = col; }

        public String getBlobId() { return blobId; }
        public void setBlobId(String blobId) { this.blobId = blobId; }

        public long getTimestamp() { return timestamp; }
        public void setTimestamp(long timestamp) { this.timestamp = timestamp; }

        public int getOriginalSize() { return originalSize; }
        public void setOriginalSize(int originalSize) { this.originalSize = originalSize; }

        public int getCompressedSize() { return compressedSize; }
        public void setCompressedSize(int compressedSize) { this.compressedSize = compressedSize; }

        public String getCellAddress() { return cellAddress; }
        public void setCellAddress(String cellAddress) { this.cellAddress = cellAddress; }

        /**
         * Generate unique key for this index entry
         */
        public String getKey() {
            return workbookId + ":" + sheetNumber + ":" + row + ":" + col;
        }

        @Override
        public String toString() {
            return String.format("IndexEntry{workbook='%s', sheet=%d, cell='%s%d', blobId='%s', size=%d->%d}",
                    workbookId, sheetNumber, row, col, blobId, originalSize, compressedSize);
        }
    }

    /**
     * Add or update an entry in the index
     */
    public void addToIndex(String workbookId, int sheetNumber, String row, int col,
                           String blobId, int originalSize, int compressedSize) {

        logger.info("Adding to index: workbook={}, sheet={}, cell={}{}, blobId={}",
                workbookId, sheetNumber, row, col, blobId);

        IndexEntry entry = new IndexEntry(workbookId, sheetNumber, row, col, blobId, originalSize, compressedSize);

        indexLock.writeLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            String key = entry.getKey();

            if (index.containsKey(key)) {
                logger.debug("Updating existing index entry for key: {}", key);
            } else {
                logger.debug("Adding new index entry for key: {}", key);
            }

            index.put(key, entry);
            saveIndex(index);

            logger.info("Successfully added/updated index entry: {}", entry);

        } catch (Exception e) {
            logger.error("Failed to add entry to index", e);
            throw new RuntimeException("Failed to add entry to index: " + e.getMessage(), e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Remove an entry from the index
     */
    public boolean removeFromIndex(String workbookId, int sheetNumber, String row, int col) {
        String key = workbookId + ":" + sheetNumber + ":" + row + ":" + col;
        logger.info("Removing from index: {}", key);

        indexLock.writeLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            IndexEntry removed = index.remove(key);

            if (removed != null) {
                saveIndex(index);
                logger.info("Successfully removed index entry: {}", removed);
                return true;
            } else {
                logger.debug("Entry not found in index: {}", key);
                return false;
            }

        } catch (Exception e) {
            logger.error("Failed to remove entry from index", e);
            throw new RuntimeException("Failed to remove entry from index: " + e.getMessage(), e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Get all staged entries
     */
    public List<IndexEntry> getStagedEntries() {
        logger.debug("Retrieving all staged entries");

        indexLock.readLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            List<IndexEntry> entries = new ArrayList<>(index.values());

            // Sort by timestamp for consistent ordering
            entries.sort(Comparator.comparingLong(IndexEntry::getTimestamp));

            logger.debug("Retrieved {} staged entries", entries.size());
            return entries;

        } catch (Exception e) {
            logger.error("Failed to retrieve staged entries", e);
            throw new RuntimeException("Failed to retrieve staged entries: " + e.getMessage(), e);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Get staged entries for a specific workbook
     */
    public List<IndexEntry> getStagedEntriesForWorkbook(String workbookId) {
        logger.debug("Retrieving staged entries for workbook: {}", workbookId);

        return getStagedEntries().stream()
                .filter(entry -> workbookId.equals(entry.getWorkbookId()))
                .sorted(Comparator.comparingInt(IndexEntry::getSheetNumber)
                        .thenComparing(IndexEntry::getRow)
                        .thenComparingInt(IndexEntry::getCol))
                .toList();
    }

    /**
     * Get staged entries for a specific sheet
     */
    public List<IndexEntry> getStagedEntriesForSheet(String workbookId, int sheetNumber) {
        logger.debug("Retrieving staged entries for workbook: {}, sheet: {}", workbookId, sheetNumber);

        return getStagedEntries().stream()
                .filter(entry -> workbookId.equals(entry.getWorkbookId()) &&
                        entry.getSheetNumber() == sheetNumber)
                .sorted(Comparator.comparing(IndexEntry::getRow)
                        .thenComparingInt(IndexEntry::getCol))
                .toList();
    }

    /**
     * Check if a specific cell is staged
     */
    public boolean isStaged(String workbookId, int sheetNumber, String row, int col) {
        String key = workbookId + ":" + sheetNumber + ":" + row + ":" + col;

        indexLock.readLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            return index.containsKey(key);
        } catch (Exception e) {
            logger.error("Failed to check if entry is staged", e);
            return false;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Clear all staged entries (reset index)
     */
    public void clearIndex() {
        logger.info("Clearing all staged entries");

        indexLock.writeLock().lock();
        try {
            saveIndex(new HashMap<>());
            logger.info("Successfully cleared index");
        } catch (Exception e) {
            logger.error("Failed to clear index", e);
            throw new RuntimeException("Failed to clear index: " + e.getMessage(), e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Get index statistics
     */
    public Map<String, Object> getIndexStats() {
        indexLock.readLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();

            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEntries", index.size());

            // Group by workbook
            Map<String, Long> workbookCounts = index.values().stream()
                    .collect(Collectors.groupingBy(IndexEntry::getWorkbookId, Collectors.counting()));
            stats.put("entriesByWorkbook", workbookCounts);

            // Calculate total sizes
            int totalOriginalSize = index.values().stream().mapToInt(IndexEntry::getOriginalSize).sum();
            int totalCompressedSize = index.values().stream().mapToInt(IndexEntry::getCompressedSize).sum();
            stats.put("totalOriginalSize", totalOriginalSize);
            stats.put("totalCompressedSize", totalCompressedSize);
            stats.put("overallCompressionRatio", totalOriginalSize > 0 ? (double) totalCompressedSize / totalOriginalSize : 0);

            return stats;

        } catch (Exception e) {
            logger.error("Failed to generate index statistics", e);
            throw new RuntimeException("Failed to generate index statistics: " + e.getMessage(), e);
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Load index from file
     */
    private Map<String, IndexEntry> loadIndex() {
        Path indexPath = Paths.get(INDEX_FILE_PATH);

        if (!Files.exists(indexPath)) {
            logger.debug("Index file does not exist, creating new empty index");
            return new HashMap<>();
        }

        try {
            logger.debug("Loading index from file: {}", indexPath);
            String content = Files.readString(indexPath, StandardCharsets.UTF_8);

            if (content.trim().isEmpty()) {
                logger.debug("Index file is empty, returning new index");
                return new HashMap<>();
            }

            // Parse JSON content
            Map<String, IndexEntry> index = objectMapper.readValue(content,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, IndexEntry.class));

            logger.debug("Loaded {} entries from index", index.size());
            return index;

        } catch (Exception e) {
            logger.error("Failed to load index file, creating new empty index", e);
            return new HashMap<>();
        }
    }

    /**
     * Save index to file atomically
     */
    private void saveIndex(Map<String, IndexEntry> index) {
        Path indexPath = Paths.get(INDEX_FILE_PATH);
        Path tempPath = Paths.get(INDEX_FILE_PATH + TEMP_INDEX_SUFFIX);

        try {
            logger.debug("Saving index with {} entries", index.size());

            // Ensure .git directory exists
            Files.createDirectories(indexPath.getParent());

            // Write to temporary file first
            String jsonContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Files.writeString(tempPath, jsonContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Atomic move to final location
            Files.move(tempPath, indexPath);

            logger.debug("Successfully saved index to {}", indexPath);

        } catch (Exception e) {
            logger.error("Failed to save index file", e);

            // Clean up temp file if it exists
            try {
                Files.deleteIfExists(tempPath);
            } catch (Exception cleanupException) {
                logger.warn("Failed to clean up temporary index file", cleanupException);
            }

            throw new RuntimeException("Failed to save index: " + e.getMessage(), e);
        }
    }
}