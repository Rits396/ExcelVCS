package com.Excel.VCS.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

import static com.Excel.VCS.service.AddService.bytesToHex;

@Service
public class RollbackService {

    private static final Logger logger = LoggerFactory.getLogger(RollbackService.class);

    // Path constants
    private static final Path VCS_DIR = Paths.get(".VCS");
    private static final Path OBJECTS_DIR = VCS_DIR.resolve("objects");
    private static final Path HEAD_FILE = VCS_DIR.resolve("HEAD");
    private static final Path REFS_DIR = VCS_DIR.resolve("refs");
    private static final Path HEADS_DIR = REFS_DIR.resolve("heads");

    private final CommitService commitService;
    private final IndexService indexService;

    public RollbackService(CommitService commitService, IndexService indexService) {
        this.commitService = commitService;
        this.indexService = indexService;
    }

    /**
     * Rollback result class
     */
    public static class RollbackResult {
        private final String operation;
        private final String targetCommit;
        private final String previousCommit;
        private final List<String> affectedFiles;
        private final long timestamp;
        private final String rollbackType;

        public RollbackResult(String operation, String targetCommit, String previousCommit,
                              List<String> affectedFiles, String rollbackType) {
            this.operation = operation;
            this.targetCommit = targetCommit;
            this.previousCommit = previousCommit;
            this.affectedFiles = affectedFiles;
            this.timestamp = Instant.now().getEpochSecond();
            this.rollbackType = rollbackType;
        }

        // Getters
        public String getOperation() { return operation; }
        public String getTargetCommit() { return targetCommit; }
        public String getPreviousCommit() { return previousCommit; }
        public List<String> getAffectedFiles() { return affectedFiles; }
        public long getTimestamp() { return timestamp; }
        public String getRollbackType() { return rollbackType; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("operation", operation);
            map.put("targetCommit", targetCommit);
            map.put("previousCommit", previousCommit);
            map.put("affectedFiles", affectedFiles);
            map.put("affectedFileCount", affectedFiles.size());
            map.put("timestamp", timestamp);
            map.put("rollbackType", rollbackType);
            return map;
        }
    }

    /**
     * Hard reset - moves HEAD to target commit and updates working directory
     * This is like 'git reset --hard <commit>'
     */
    public RollbackResult hardReset(String targetCommitHash) {
        logger.info("Starting hard reset to commit: {}", targetCommitHash);

        try {
            // Validate target commit exists
            if (!commitExists(targetCommitHash)) {
                throw new IllegalArgumentException("Commit does not exist: " + targetCommitHash);
            }

            String currentCommit = getCurrentHead();

            // Get files that will be affected
            List<String> affectedFiles = getAffectedFilesBetweenCommits(currentCommit, targetCommitHash);

            // Update HEAD to point to target commit
            updateHeadToCommit(targetCommitHash);

            // Restore working directory to target commit state
            restoreWorkingDirectory(targetCommitHash);

            // Clear staging area
            indexService.clearIndex();

            logger.info("Hard reset completed successfully to commit: {}", targetCommitHash);

            return new RollbackResult(
                    "hard_reset",
                    targetCommitHash,
                    currentCommit,
                    affectedFiles,
                    "destructive"
            );

        } catch (Exception e) {
            logger.error("Hard reset failed", e);
            throw new RuntimeException("Hard reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * Soft reset - moves HEAD to target commit but keeps working directory and staging area
     * This is like 'git reset --soft <commit>'
     */
    public RollbackResult softReset(String targetCommitHash) {
        logger.info("Starting soft reset to commit: {}", targetCommitHash);

        try {
            // Validate target commit exists
            if (!commitExists(targetCommitHash)) {
                throw new IllegalArgumentException("Commit does not exist: " + targetCommitHash);
            }

            String currentCommit = getCurrentHead();

            // Get files that would be affected (for information purposes)
            List<String> affectedFiles = getAffectedFilesBetweenCommits(currentCommit, targetCommitHash);

            // Only update HEAD, keep working directory and staging area unchanged
            updateHeadToCommit(targetCommitHash);

            logger.info("Soft reset completed successfully to commit: {}", targetCommitHash);

            return new RollbackResult(
                    "soft_reset",
                    targetCommitHash,
                    currentCommit,
                    affectedFiles,
                    "non_destructive"
            );

        } catch (Exception e) {
            logger.error("Soft reset failed", e);
            throw new RuntimeException("Soft reset failed: " + e.getMessage(), e);
        }
    }

    /**
     * Revert commit - creates a new commit that undoes changes from specified commit
     * This is like 'git revert <commit>'
     */
    /**
     * Revert commit - creates a new commit that undoes changes from specified commit
     * This is like 'git revert <commit>'
     */
    public RollbackResult revertCommit(String commitToRevert, String author, String email) {
        logger.info("Starting revert of commit: {}", commitToRevert);

        try {
            // Validate commit exists
            if (!commitExists(commitToRevert)) {
                throw new IllegalArgumentException("Commit does not exist: " + commitToRevert);
            }

            String currentCommit = getCurrentHead();

            // Debug the commit we're trying to revert
            Map<String, Object> debugInfo = debugCommitObject(commitToRevert);
            logger.info("Debug info for commit {}: {}", commitToRevert, debugInfo);

            // Get the commit to revert and its parent
            Map<String, Object> commitInfo = readCommitObject(commitToRevert);

            // Check if tree hash exists
            String commitTreeHash = (String) commitInfo.get("tree");
            if (commitTreeHash == null || commitTreeHash.trim().isEmpty()) {
                logger.error("Commit info parsed: {}", commitInfo);
                throw new RuntimeException("Commit tree hash is null or empty for commit: " + commitToRevert);
            }

            String parentCommit = (String) commitInfo.get("parent");
            if (parentCommit == null || parentCommit.trim().isEmpty()) {
                throw new RuntimeException("Cannot revert initial commit (no parent found): " + commitToRevert);
            }

            // Validate that parent commit exists
            if (!commitExists(parentCommit)) {
                throw new RuntimeException("Parent commit does not exist: " + parentCommit);
            }

            // Debug the parent commit too
            Map<String, Object> parentDebugInfo = debugCommitObject(parentCommit);
            logger.info("Debug info for parent commit {}: {}", parentCommit, parentDebugInfo);

            // Read parent commit info
            Map<String, Object> parentCommitInfo = readCommitObject(parentCommit);
            String parentTreeHash = (String) parentCommitInfo.get("tree");
            if (parentTreeHash == null || parentTreeHash.trim().isEmpty()) {
                logger.error("Parent commit info parsed: {}", parentCommitInfo);
                throw new RuntimeException("Parent commit tree hash is null or empty for commit: " + parentCommit);
            }

            logger.info("Successfully parsed commit hashes - commit tree: {}, parent tree: {}",
                    commitTreeHash, parentTreeHash);

            // Continue with the rest of the revert logic...
            List<IndexService.IndexEntry> commitEntries = getEntriesFromTree(commitTreeHash);
            List<IndexService.IndexEntry> parentEntries = getEntriesFromTree(parentTreeHash);

            // Clear current staging area
            indexService.clearIndex();

            // Stage the parent commit's version of all files that were changed
            Map<String, IndexService.IndexEntry> parentEntryMap = parentEntries.stream()
                    .collect(Collectors.toMap(IndexService.IndexEntry::getGitPath, e -> e));

            Map<String, IndexService.IndexEntry> commitEntryMap = commitEntries.stream()
                    .collect(Collectors.toMap(IndexService.IndexEntry::getGitPath, e -> e));

            // Stage files from parent commit (reverting changes)
            int stagedCount = 0;
            Set<String> allPaths = new HashSet<>();
            allPaths.addAll(parentEntryMap.keySet());
            allPaths.addAll(commitEntryMap.keySet());

            for (String gitPath : allPaths) {
                IndexService.IndexEntry commitEntry = commitEntryMap.get(gitPath);
                IndexService.IndexEntry parentEntry = parentEntryMap.get(gitPath);

                if (commitEntry != null && parentEntry != null) {
                    // File exists in both commits - check if they're different
                    if (!commitEntry.getBlobId().equals(parentEntry.getBlobId())) {
                        // File was modified in the commit we're reverting, use parent version
                        boolean staged = indexService.addToIndex(
                                parentEntry.getWorkbookId(),
                                parentEntry.getSheetNumber(),
                                parentEntry.getRow(),
                                parentEntry.getCol(),
                                parentEntry.getBlobId(),
                                parentEntry.getOriginalSize(),
                                parentEntry.getCompressedSize()
                        );
                        if (staged) stagedCount++;
                        logger.debug("Staged parent version of modified file: {}", gitPath);
                    }
                } else if (commitEntry != null && parentEntry == null) {
                    // File was added in the commit we're reverting - we would need to remove it
                    logger.debug("File {} was added in commit being reverted - skipping removal", gitPath);
                } else if (commitEntry == null && parentEntry != null) {
                    // File was deleted in the commit we're reverting, restore it
                    boolean staged = indexService.addToIndex(
                            parentEntry.getWorkbookId(),
                            parentEntry.getSheetNumber(),
                            parentEntry.getRow(),
                            parentEntry.getCol(),
                            parentEntry.getBlobId(),
                            parentEntry.getOriginalSize(),
                            parentEntry.getCompressedSize()
                    );
                    if (staged) stagedCount++;
                    logger.debug("Restored deleted file: {}", gitPath);
                }
            }

            logger.info("Staged {} files for revert", stagedCount);

            if (stagedCount == 0) {
                throw new RuntimeException("No changes to revert - commit may be empty or identical to parent");
            }

            // Create revert commit
            String originalMessage = (String) commitInfo.get("message");
            if (originalMessage == null) {
                originalMessage = "Unknown commit message";
            }

            String revertMessage = String.format("Revert \"%s\"\n\nThis reverts commit %s.",
                    originalMessage.trim(), commitToRevert);

            CommitService.CommitResult revertCommitResult = commitService.commit(revertMessage, author, email);

            logger.info("Revert commit created: {}", revertCommitResult.getCommitHash());

            List<String> affectedFiles = new ArrayList<>();
            affectedFiles.addAll(commitEntryMap.keySet());
            affectedFiles.addAll(parentEntryMap.keySet());

            return new RollbackResult(
                    "revert",
                    revertCommitResult.getCommitHash(),
                    currentCommit,
                    affectedFiles,
                    "safe"
            );

        } catch (Exception e) {
            logger.error("Revert failed: {}", e.getMessage(), e);
            throw new RuntimeException("Revert failed: " + e.getMessage(), e);
        }
    }

    public Map<String, Object> debugCommitObject(String commitHash) {
        logger.info("Debug inspection of commit: {}", commitHash);

        try {
            String dirName = commitHash.substring(0, 2);
            String fileName = commitHash.substring(2);
            Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);

            Map<String, Object> debug = new HashMap<>();
            debug.put("commitHash", commitHash);
            debug.put("objectPath", objectFile.toString());
            debug.put("fileExists", Files.exists(objectFile));

            if (!Files.exists(objectFile)) {
                debug.put("error", "Object file does not exist");
                return debug;
            }

            byte[] compressedData = Files.readAllBytes(objectFile);
            debug.put("compressedSize", compressedData.length);

            try {
                byte[] decompressedData = zlibDecompress(compressedData);
                debug.put("decompressedSize", decompressedData.length);

                String content = new String(decompressedData, StandardCharsets.UTF_8);
                debug.put("rawContent", content);

                // Try to parse it
                Map<String, Object> parsed = parseCommitContent(content, commitHash);
                debug.put("parsedContent", parsed);
                debug.put("hasTreeHash", parsed.containsKey("tree") && parsed.get("tree") != null);

            } catch (Exception parseError) {
                debug.put("parseError", parseError.getMessage());
            }

            return debug;

        } catch (Exception e) {
            logger.error("Failed to debug commit object", e);
            Map<String, Object> errorResult = new HashMap<>();
            errorResult.put("error", "Failed to debug commit: " + e.getMessage());
            return errorResult;
        }
    }


    /**
     * Cherry-pick a commit from another branch or point in history
     * This is like 'git cherry-pick <commit>'
     */
    public RollbackResult cherryPick(String commitHash, String author, String email) {
        logger.info("Starting cherry-pick of commit: {}", commitHash);

        try {
            // Validate commit exists
            if (!commitExists(commitHash)) {
                throw new IllegalArgumentException("Commit does not exist: " + commitHash);
            }

            String currentCommit = getCurrentHead();

            // Get the commit info and its parent
            Map<String, Object> commitInfo = readCommitObject(commitHash);
            String parentCommit = (String) commitInfo.get("parent");

            if (parentCommit == null) {
                throw new RuntimeException("Cannot cherry-pick initial commit");
            }

            // Get entries from the commit we want to cherry-pick
            List<IndexService.IndexEntry> commitEntries = getEntriesFromTree((String) commitInfo.get("tree"));

            // Clear staging area and apply the cherry-pick
            indexService.clearIndex();

            // Stage all entries from the cherry-picked commit
            int stagedCount = 0;
            for (IndexService.IndexEntry entry : commitEntries) {
                boolean staged = indexService.addToIndex(
                        entry.getWorkbookId(),
                        entry.getSheetNumber(),
                        entry.getRow(),
                        entry.getCol(),
                        entry.getBlobId(),
                        entry.getOriginalSize(),
                        entry.getCompressedSize()
                );
                if (staged) stagedCount++;
            }

            logger.info("Staged {} files for cherry-pick", stagedCount);

            if (stagedCount == 0) {
                throw new RuntimeException("No changes to cherry-pick - commit may be empty");
            }

            // Create cherry-pick commit
            String originalMessage = (String) commitInfo.get("message");
            String cherryPickMessage = String.format("%s\n\n(cherry picked from commit %s)",
                    originalMessage, commitHash);

            CommitService.CommitResult cherryPickResult = commitService.commit(cherryPickMessage, author, email);

            logger.info("Cherry-pick commit created: {}", cherryPickResult.getCommitHash());

            List<String> affectedFiles = commitEntries.stream()
                    .map(IndexService.IndexEntry::getGitPath)
                    .collect(Collectors.toList());

            return new RollbackResult(
                    "cherry_pick",
                    cherryPickResult.getCommitHash(),
                    currentCommit,
                    affectedFiles,
                    "additive"
            );

        } catch (Exception e) {
            logger.error("Cherry-pick failed", e);
            throw new RuntimeException("Cherry-pick failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get preview of what a rollback operation would affect
     */
    public Map<String, Object> previewRollback(String targetCommit, String rollbackType) {
        logger.info("Generating rollback preview for commit: {} with type: {}", targetCommit, rollbackType);

        try {
            if (!commitExists(targetCommit)) {
                throw new IllegalArgumentException("Commit does not exist: " + targetCommit);
            }

            String currentCommit = getCurrentHead();
            List<String> affectedFiles = getAffectedFilesBetweenCommits(currentCommit, targetCommit);

            Map<String, Object> preview = new HashMap<>();
            preview.put("currentCommit", currentCommit);
            preview.put("targetCommit", targetCommit);
            preview.put("rollbackType", rollbackType);
            preview.put("affectedFiles", affectedFiles);
            preview.put("affectedFileCount", affectedFiles.size());
            preview.put("isDestructive", Arrays.asList("hard_reset", "revert").contains(rollbackType));

            // Get commit info for both commits
            if (currentCommit != null) {
                preview.put("currentCommitInfo", readCommitObject(currentCommit));
            }
            preview.put("targetCommitInfo", readCommitObject(targetCommit));

            return preview;

        } catch (Exception e) {
            logger.error("Failed to generate rollback preview", e);
            throw new RuntimeException("Failed to generate rollback preview: " + e.getMessage(), e);
        }
    }

    // ========== PRIVATE HELPER METHODS ==========

    private boolean commitExists(String commitHash) {
        if (commitHash == null || commitHash.length() != 40) {
            return false;
        }

        try {
            String dirName = commitHash.substring(0, 2);
            String fileName = commitHash.substring(2);
            Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);
            return Files.exists(objectFile);
        } catch (Exception e) {
            return false;
        }
    }

    private String getCurrentHead() {
        try {
            if (!Files.exists(HEAD_FILE)) {
                return null;
            }

            String headContent = Files.readString(HEAD_FILE, StandardCharsets.UTF_8).trim();

            if (headContent.startsWith("ref: ")) {
                String refPath = headContent.substring(5);
                Path branchFile = VCS_DIR.resolve(refPath);
                if (Files.exists(branchFile)) {
                    return Files.readString(branchFile, StandardCharsets.UTF_8).trim();
                }
            } else {
                return headContent;
            }

            return null;
        } catch (IOException e) {
            return null;
        }
    }

    private void updateHeadToCommit(String commitHash) throws IOException {
        String currentBranch = getCurrentBranch();
        Path branchFile = HEADS_DIR.resolve(currentBranch);
        Files.createDirectories(branchFile.getParent());
        Files.writeString(branchFile, commitHash + "\n", StandardCharsets.UTF_8);
        logger.debug("Updated HEAD to commit: {}", commitHash);
    }

    private String getCurrentBranch() {
        try {
            if (!Files.exists(HEAD_FILE)) {
                return "master";
            }

            String headContent = Files.readString(HEAD_FILE, StandardCharsets.UTF_8).trim();
            if (headContent.startsWith("ref: refs/heads/")) {
                return headContent.substring(16);
            }
            return "master";
        } catch (IOException e) {
            return "master";
        }
    }

    private void restoreWorkingDirectory(String commitHash) throws IOException {
        logger.info("Restoring working directory to commit: {}", commitHash);

        Map<String, Object> commitInfo = readCommitObject(commitHash);
        String treeHash = (String) commitInfo.get("tree");

        // Get entries from the commit
        List<IndexService.IndexEntry> commitEntries = getEntriesFromTree(treeHash);

        // Clear current index
        indexService.clearIndex();

        // Restore each file to the working directory (database)
        for (IndexService.IndexEntry entry : commitEntries) {
            try {
                // Read the blob content
                String blobContent = readBlobContent(entry.getBlobId());

                // Update the actual cell value in the database
                updateCellInDatabase(entry.getWorkbookId(), entry.getSheetNumber(),
                        entry.getRow(), entry.getCol(), blobContent);

                // Stage the entry
                indexService.addToIndex(entry.getWorkbookId(), entry.getSheetNumber(),
                        entry.getRow(), entry.getCol(), entry.getBlobId(),
                        entry.getOriginalSize(), entry.getCompressedSize());

            } catch (Exception e) {
                logger.warn("Failed to restore entry: {}", entry, e);
            }
        }
    }

    // Helper method to update cell in database (you'll need to implement this)
    private void updateCellInDatabase(String workbookId, int sheetNumber, String row, int col, String value) {
        // This method should update the actual cell value in your MongoDB database
        // You'll need to inject WorkbookService and use it to update the cell
        try {
            // Assuming you have access to workbookService
            // workbookService.setCellValue(workbookId, sheetNumber, row, col, value);
            logger.debug("Updated cell {}:{} to value: {}", row, col, value);
        } catch (Exception e) {
            logger.error("Failed to update cell in database", e);
            throw new RuntimeException("Failed to update cell in database", e);
        }
    }

    // Helper method to read blob content
    private String readBlobContent(String blobId) throws IOException {
        String dirName = blobId.substring(0, 2);
        String fileName = blobId.substring(2);
        Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);

        if (!Files.exists(objectFile)) {
            throw new IOException("Blob object not found: " + blobId);
        }

        // Read and decompress the blob
        byte[] compressedData = Files.readAllBytes(objectFile);
        byte[] decompressedData = zlibDecompress(compressedData);
        String content = new String(decompressedData, StandardCharsets.UTF_8);

        // Remove the blob header "blob <size>\0"
        int nullIndex = content.indexOf('\0');
        if (nullIndex != -1) {
            return content.substring(nullIndex + 1);
        }

        return content;
    }

    private List<String> getAffectedFilesBetweenCommits(String fromCommit, String toCommit) {
        Set<String> affectedFiles = new HashSet<>();

        try {
            if (fromCommit != null) {
                Map<String, Object> fromCommitInfo = readCommitObject(fromCommit);
                String fromTreeHash = (String) fromCommitInfo.get("tree");
                List<IndexService.IndexEntry> fromEntries = getEntriesFromTree(fromTreeHash);
                affectedFiles.addAll(fromEntries.stream()
                        .map(IndexService.IndexEntry::getGitPath)
                        .collect(Collectors.toSet()));
            }

            Map<String, Object> toCommitInfo = readCommitObject(toCommit);
            String toTreeHash = (String) toCommitInfo.get("tree");
            List<IndexService.IndexEntry> toEntries = getEntriesFromTree(toTreeHash);
            affectedFiles.addAll(toEntries.stream()
                    .map(IndexService.IndexEntry::getGitPath)
                    .collect(Collectors.toSet()));

        } catch (Exception e) {
            logger.warn("Failed to determine affected files between commits", e);
        }

        return new ArrayList<>(affectedFiles);
    }

    /**
     * Parse tree object and extract all entries recursively
     * This is the key method that was missing!
     */
    private List<IndexService.IndexEntry> getEntriesFromTree(String treeHash) {
        logger.debug("Parsing tree object: {}", treeHash);

        if (treeHash == null || treeHash.isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // Read the tree object
            String dirName = treeHash.substring(0, 2);
            String fileName = treeHash.substring(2);
            Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);

            if (!Files.exists(objectFile)) {
                logger.warn("Tree object not found: {}", treeHash);
                return new ArrayList<>();
            }

            // Read and decompress the tree object
            byte[] compressedData = Files.readAllBytes(objectFile);
            byte[] decompressedData = zlibDecompress(compressedData);
            String content = new String(decompressedData, StandardCharsets.UTF_8);

            // Parse the tree content
            return parseTreeContent(content);

        } catch (Exception e) {
            logger.error("Failed to parse tree object: {}", treeHash, e);
            return new ArrayList<>();
        }
    }

    /**
     * Parse tree content and extract IndexEntry objects
     */
    private List<IndexService.IndexEntry> parseTreeContent(String content) {
        List<IndexService.IndexEntry> entries = new ArrayList<>();

        try {
            // Find the header end (after "tree <size>\0")
            int nullIndex = content.indexOf('\0');
            if (nullIndex == -1) {
                logger.error("Invalid tree object format - no null terminator found");
                return entries;
            }

            // Get the binary data after the header
            String treeData = content.substring(nullIndex + 1);
            byte[] treeBytes = treeData.getBytes(StandardCharsets.ISO_8859_1); // Use ISO_8859_1 to preserve bytes

            // Parse tree entries from binary data
            ByteArrayInputStream bais = new ByteArrayInputStream(treeBytes);
            DataInputStream dis = new DataInputStream(bais);

            while (dis.available() > 0) {
                try {
                    // Read mode and name (null-terminated string)
                    StringBuilder entryBuilder = new StringBuilder();
                    int b;
                    while ((b = dis.read()) != 0 && b != -1) {
                        entryBuilder.append((char) b);
                    }

                    if (entryBuilder.length() == 0) break;

                    String modeAndName = entryBuilder.toString();
                    String[] parts = modeAndName.split(" ", 2);
                    if (parts.length != 2) continue;

                    String mode = parts[0];
                    String name = parts[1];

                    // Read 20-byte SHA-1 hash (or 32-byte SHA-256)
                    byte[] hashBytes = new byte[20]; // Assuming SHA-1 for Git compatibility
                    int bytesRead = dis.read(hashBytes);
                    if (bytesRead != 20) break;

                    String hash = bytesToHex(hashBytes);

                    if ("40000".equals(mode)) {
                        // This is a subdirectory - recurse into it
                        List<IndexService.IndexEntry> subEntries = getEntriesFromTree(hash);
                        entries.addAll(subEntries);
                    } else if ("100644".equals(mode)) {
                        // This is a blob (file) - convert to IndexEntry
                        IndexService.IndexEntry entry = parseFilePathToIndexEntry(name, hash);
                        if (entry != null) {
                            entries.add(entry);
                        }
                    }

                } catch (Exception e) {
                    logger.warn("Failed to parse tree entry", e);
                    break; // Stop parsing on error
                }
            }

            dis.close();

        } catch (Exception e) {
            logger.error("Failed to parse tree content", e);
        }

        logger.debug("Parsed {} entries from tree", entries.size());
        return entries;
    }
    /**
     * Convert file path to IndexEntry
     * Path format: "workbookId/sheetNumber/rowCol" -> "121/1/A1"
     */
    private IndexService.IndexEntry parseFilePathToIndexEntry(String filePath, String blobHash) {
        try {
            String[] pathParts = filePath.split("/", 3);
            if (pathParts.length != 3) {
                logger.warn("Invalid file path format: {}", filePath);
                return null;
            }

            String workbookId = pathParts[0];
            int sheetNumber = Integer.parseInt(pathParts[1]);
            String cellRef = pathParts[2]; // e.g., "A1"

            // Parse cell reference correctly: letters = COLUMN, numbers = ROW
            String colLetters = "";
            String rowNumbers = "";

            int i = 0;
            // Extract column letters (A, B, AA, etc.)
            while (i < cellRef.length() && Character.isLetter(cellRef.charAt(i))) {
                colLetters += cellRef.charAt(i);
                i++;
            }
            // Extract row numbers (1, 2, 100, etc.)
            while (i < cellRef.length() && Character.isDigit(cellRef.charAt(i))) {
                rowNumbers += cellRef.charAt(i);
                i++;
            }

            if (colLetters.isEmpty() || rowNumbers.isEmpty()) {
                logger.warn("Invalid cell reference format: {}", cellRef);
                return null;
            }

            // Convert column letters to number (A=1, B=2, AA=27, etc.)
            int col = convertColumnLettersToNumber(colLetters);
            int row = Integer.parseInt(rowNumbers);

            // Create IndexEntry - note the corrected field assignments
            IndexService.IndexEntry entry = new IndexService.IndexEntry();
            entry.setWorkbookId(workbookId);
            entry.setSheetNumber(sheetNumber);
            entry.setRow(colLetters.toUpperCase()); // This should be column letters
            entry.setCol(row); // This should be row number
            entry.setBlobId(blobHash);
            entry.setCellAddress(colLetters.toUpperCase() + row);
            entry.setTimestamp(Instant.now().getEpochSecond());
            entry.setOriginalSize(0);
            entry.setCompressedSize(0);

            return entry;

        } catch (Exception e) {
            logger.error("Failed to parse file path to IndexEntry: {}", filePath, e);
            return null;
        }
    }


    private Map<String, Object> readCommitObject(String commitHash) {
        try {
            String dirName = commitHash.substring(0, 2);
            String fileName = commitHash.substring(2);
            Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);

            if (!Files.exists(objectFile)) {
                throw new RuntimeException("Commit object not found: " + commitHash);
            }

            byte[] compressedData = Files.readAllBytes(objectFile);
            byte[] decompressedData = zlibDecompress(compressedData);
            String content = new String(decompressedData, StandardCharsets.UTF_8);

            return parseCommitContent(content, commitHash);

        } catch (Exception e) {
            logger.error("Failed to read commit object: {}", commitHash, e);
            throw new RuntimeException("Failed to read commit object", e);
        }
    }

    // Utility methods (same as in CommitService)
    private byte[] zlibDecompress(byte[] compressedData) {
        try {
            java.util.zip.Inflater inflater = new java.util.zip.Inflater();
            inflater.setInput(compressedData);

            java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[1024];

            while (!inflater.finished()) {
                int count = inflater.inflate(buffer);
                outputStream.write(buffer, 0, count);
            }

            inflater.end();
            return outputStream.toByteArray();

        } catch (Exception e) {
            throw new RuntimeException("Failed to decompress zlib data", e);
        }
    }

    private Map<String, Object> parseCommitContent(String content, String hash) {
        Map<String, Object> commit = new HashMap<>();
        commit.put("hash", hash);

        String[] lines = content.split("\n");
        StringBuilder messageBuilder = new StringBuilder();
        boolean inMessage = false;

        for (String line : lines) {
            if (inMessage) {
                messageBuilder.append(line).append("\n");
            } else if (line.startsWith("tree ")) {
                commit.put("tree", line.substring(5));
            } else if (line.startsWith("parent ")) {
                commit.put("parent", line.substring(7));
            } else if (line.startsWith("author ")) {
                commit.put("author", line.substring(7));
            } else if (line.startsWith("committer ")) {
                commit.put("committer", line.substring(10));
            } else if (line.isEmpty()) {
                inMessage = true;
            }
        }

        commit.put("message", messageBuilder.toString().trim());
        return commit;
    }
    private int convertColumnLettersToNumber(String columnLetters) {
        int result = 0;
        for (int i = 0; i < columnLetters.length(); i++) {
            result = result * 26 + (columnLetters.charAt(i) - 'A' + 1);
        }
        return result;
    }
}