package com.Excel.VCS.service;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.stream.Collectors;

@Service
public class IndexService {

    private static final Logger logger = LoggerFactory.getLogger(IndexService.class);

    // Use Path objects instead of String for OS compatibility
    private static final Path VCS_DIR = Paths.get(".VCS");
    private static final Path INDEX_FILE_PATH = VCS_DIR.resolve("index");
    private static final String TEMP_INDEX_SUFFIX = ".tmp";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ReadWriteLock indexLock = new ReentrantReadWriteLock();

    /**
     * Initialize VCS directory on startup
     */
    @PostConstruct
    public void initializeVCSDirectory() {
        try {
            Files.createDirectories(VCS_DIR);
            logger.info("Initialized VCS directory at: {}", VCS_DIR.toAbsolutePath());

            // Create empty index file if it doesn't exist
            if (!Files.exists(INDEX_FILE_PATH)) {
                saveIndex(new HashMap<>());
                logger.info("Created initial index file at: {}", INDEX_FILE_PATH);
            }
        } catch (IOException e) {
            logger.error("Failed to initialize VCS directory", e);
            throw new RuntimeException("Failed to initialize VCS directory", e);
        }
    }

    /**
     * Index entry representing a staged cell change
     */
    public static class IndexEntry {
        private String workbookId;
        private int sheetNumber;
        private String row;
        private int col;
        private String blobId; // This is your hash ID
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
            this.cellAddress = row.toUpperCase()+ ":"+ col;
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
        @JsonIgnore
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
     * Add or update an entry in the index with hash comparison
     */
    public boolean addToIndex(String workbookId, int sheetNumber, String row, int col,
                              String newBlobId, int originalSize, int compressedSize) {

        logger.info("Adding to index: workbook={}, sheet={}, cell={}{}, blobId={}",
                workbookId, sheetNumber, row, col, newBlobId);

        String key = workbookId + ":" + sheetNumber + ":" + row.toUpperCase() + ":" + col;

        indexLock.writeLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();

            // Check if entry already exists for this cell
            if (index.containsKey(key)) {
                IndexEntry existingEntry = index.get(key);
                String existingBlobId = existingEntry.getBlobId();

                logger.debug("Found existing entry for key: {} with blobId: {}", key, existingBlobId);

                // Compare hash IDs (blob IDs)
                if (newBlobId.equals(existingBlobId)) {
                    logger.info("Hash ID unchanged for cell {}{}, no update needed", row, col);
                    return false; // No change needed
                } else {
                    logger.info("Hash ID changed for cell {}{}: {} -> {}",
                            row, col, existingBlobId, newBlobId);

                    // Update existing entry with new hash ID and timestamp
                    existingEntry.setBlobId(newBlobId);
                    existingEntry.setTimestamp(Instant.now().getEpochSecond());
                    existingEntry.setOriginalSize(originalSize);
                    existingEntry.setCompressedSize(compressedSize);

                    // Update index using incremental update
                    updateIndexEntry(index, key, existingEntry);

                    logger.info("Successfully updated index entry: {}", existingEntry);
                    return true; // Entry was updated
                }
            } else {
                logger.debug("Adding new index entry for key: {}", key);

                // Create new entry
                IndexEntry newEntry = new IndexEntry(workbookId, sheetNumber, row.toUpperCase(), col,
                        newBlobId, originalSize, compressedSize);

                // Add new entry using incremental update
                updateIndexEntry(index, key, newEntry);

                logger.info("Successfully added new index entry: {}", newEntry);
                return true; // New entry was added
            }

        } catch (Exception e) {
            logger.error("Failed to add entry to index", e);
            throw new RuntimeException("Failed to add entry to index: " + e.getMessage(), e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Get existing entry for a specific cell
     */
    public IndexEntry getIndexEntry(String workbookId, int sheetNumber, String row, int col) {
        String key = workbookId + ":" + sheetNumber + ":" + row.toUpperCase() + ":" + col;

        indexLock.readLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            return index.get(key);
        } catch (Exception e) {
            logger.error("Failed to get index entry for key: {}", key, e);
            return null;
        } finally {
            indexLock.readLock().unlock();
        }
    }

    /**
     * Check if hash ID exists for a specific cell
     */
    public String getHashIdForCell(String workbookId, int sheetNumber, String row, int col) {
        IndexEntry entry = getIndexEntry(workbookId, sheetNumber, row.toUpperCase(), col);
        return entry != null ? entry.getBlobId() : null;
    }

    /**
     * Update a single index entry incrementally (without full rewrite)
     */
    private void updateIndexEntry(Map<String, IndexEntry> currentIndex, String key, IndexEntry entry) {
        // Update the in-memory index
        currentIndex.put(key, entry);

        // Save the updated index
        saveIndex(currentIndex);
    }

    /**
     * Remove an entry from the index
     */
    public boolean removeFromIndex(String workbookId, int sheetNumber, String row, int col) {
        String key = workbookId + ":" + sheetNumber + ":" + row.toUpperCase() + ":" + col;
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
     * Batch update multiple entries (for performance when updating many cells)
     */
    public Map<String, Boolean> batchAddToIndex(List<BatchIndexEntry> entries) {
        logger.info("Batch updating {} entries", entries.size());

        Map<String, Boolean> results = new HashMap<>();

        indexLock.writeLock().lock();
        try {
            Map<String, IndexEntry> index = loadIndex();
            boolean indexModified = false;

            for (BatchIndexEntry batchEntry : entries) {
                String key = batchEntry.workbookId + ":" + batchEntry.sheetNumber + ":" +
                        batchEntry.row + ":" + batchEntry.col;

                boolean entryModified = false;

                if (index.containsKey(key)) {
                    IndexEntry existingEntry = index.get(key);
                    String existingBlobId = existingEntry.getBlobId();

                    if (!batchEntry.newBlobId.equals(existingBlobId)) {
                        existingEntry.setBlobId(batchEntry.newBlobId);
                        existingEntry.setTimestamp(Instant.now().getEpochSecond());
                        existingEntry.setOriginalSize(batchEntry.originalSize);
                        existingEntry.setCompressedSize(batchEntry.compressedSize);
                        entryModified = true;
                        indexModified = true;
                    }
                } else {
                    IndexEntry newEntry = new IndexEntry(batchEntry.workbookId, batchEntry.sheetNumber,
                            batchEntry.row, batchEntry.col, batchEntry.newBlobId,
                            batchEntry.originalSize, batchEntry.compressedSize);
                    index.put(key, newEntry);
                    entryModified = true;
                    indexModified = true;
                }

                results.put(key, entryModified);
            }

            // Save only once after all updates
            if (indexModified) {
                saveIndex(index);
                logger.info("Successfully batch updated index with {} modified entries",
                        results.values().stream().mapToInt(b -> b ? 1 : 0).sum());
            }

            return results;

        } catch (Exception e) {
            logger.error("Failed to batch update index", e);
            throw new RuntimeException("Failed to batch update index: " + e.getMessage(), e);
        } finally {
            indexLock.writeLock().unlock();
        }
    }

    /**
     * Helper class for batch operations
     */
    public static class BatchIndexEntry {
        public String workbookId;
        public int sheetNumber;
        public String row;
        public int col;
        public String newBlobId;
        public int originalSize;
        public int compressedSize;

        public BatchIndexEntry(String workbookId, int sheetNumber, String row, int col,
                               String newBlobId, int originalSize, int compressedSize) {
            this.workbookId = workbookId;
            this.sheetNumber = sheetNumber;
            this.row = row.toUpperCase();
            this.col = col;
            this.newBlobId = newBlobId;
            this.originalSize = originalSize;
            this.compressedSize = compressedSize;
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
        String key = workbookId + ":" + sheetNumber + ":" + row.toUpperCase() + ":" + col;

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
        try {
            if (!Files.exists(INDEX_FILE_PATH)) {
                logger.debug("Index file does not exist at {}, returning empty index", INDEX_FILE_PATH);
                return new HashMap<>();
            }

            logger.debug("Loading index from file: {}", INDEX_FILE_PATH);
            String content = Files.readString(INDEX_FILE_PATH, StandardCharsets.UTF_8);

            if (content.trim().isEmpty()) {
                logger.debug("Index file is empty, returning new index");
                return new HashMap<>();
            }

            // Parse JSON content
            Map<String, IndexEntry> index = objectMapper.readValue(content,
                    objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, IndexEntry.class));

            logger.debug("Loaded {} entries from index", index.size());
            return index;

        } catch (IOException e) {
            logger.error("IO error loading index file: {}", e.getMessage());
            return new HashMap<>();
        } catch (Exception e) {
            logger.error("Failed to parse index file, returning empty index", e);
            return new HashMap<>();
        }
    }

    /**
     * Save index to file atomically
     */
    private void saveIndex(Map<String, IndexEntry> index) {
        Path tempPath = INDEX_FILE_PATH.resolveSibling(INDEX_FILE_PATH.getFileName() + TEMP_INDEX_SUFFIX);

        try {
            logger.debug("Saving index with {} entries to {}", index.size(), INDEX_FILE_PATH);

            // Ensure VCS directory exists
            Files.createDirectories(INDEX_FILE_PATH.getParent());

            // Write to temporary file first
            String jsonContent = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(index);
            Files.writeString(tempPath, jsonContent, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);

            // Atomic move to final location
            Files.move(tempPath, INDEX_FILE_PATH, StandardCopyOption.REPLACE_EXISTING);

            logger.debug("Successfully saved index to {}", INDEX_FILE_PATH);

        } catch (IOException e) {
            logger.error("IO error saving index file: {}", e.getMessage(), e);

            // Clean up temp file if it exists
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanupException) {
                logger.warn("Failed to clean up temporary index file: {}", cleanupException.getMessage());
            }

            throw new RuntimeException("Failed to save index: " + e.getMessage(), e);
        } catch (Exception e) {
            logger.error("Unexpected error saving index file", e);

            // Clean up temp file if it exists
            try {
                Files.deleteIfExists(tempPath);
            } catch (IOException cleanupException) {
                logger.warn("Failed to clean up temporary index file: {}", cleanupException.getMessage());
            }

            throw new RuntimeException("Failed to save index: " + e.getMessage(), e);
        }
    }
}