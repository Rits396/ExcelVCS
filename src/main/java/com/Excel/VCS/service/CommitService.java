package com.Excel.VCS.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.Deflater;

@Service
public class CommitService {

    private static final Logger logger = LoggerFactory.getLogger(CommitService.class);

    // Path constants
    private static final Path VCS_DIR = Paths.get(".VCS");
    private static final Path OBJECTS_DIR = VCS_DIR.resolve("objects");
    private static final Path HEAD_FILE = VCS_DIR.resolve("HEAD");
    private static final Path REFS_DIR = VCS_DIR.resolve("refs");
    private static final Path HEADS_DIR = REFS_DIR.resolve("heads");

    private final IndexService indexService;

    public CommitService(IndexService indexService) {
        this.indexService = indexService;
        initializeVCSStructure();
    }

    /**
     * Initialize VCS directory structure for commits
     */
    private void initializeVCSStructure() {
        try {
            Files.createDirectories(OBJECTS_DIR);
            Files.createDirectories(HEADS_DIR);

            // Initialize HEAD to point to master branch if it doesn't exist
            if (!Files.exists(HEAD_FILE)) {
                Files.writeString(HEAD_FILE, "ref: refs/heads/master\n", StandardCharsets.UTF_8);
                logger.info("Initialized HEAD to point to master branch");
            }

            logger.debug("VCS structure initialized successfully");
        } catch (IOException e) {
            logger.error("Failed to initialize VCS structure", e);
            throw new RuntimeException("Failed to initialize VCS structure", e);
        }
    }

    /**
     * Commit class to represent commit data
     */
    public static class CommitResult {
        private final String commitHash;
        private final String treeHash;
        private final String message;
        private final String author;
        private final String email;
        private final long timestamp;
        private final String parentCommit;
        private final int stagedFiles;
        private final String branch;

        public CommitResult(String commitHash, String treeHash, String message,
                            String author, String email, long timestamp,
                            String parentCommit, int stagedFiles, String branch) {
            this.commitHash = commitHash;
            this.treeHash = treeHash;
            this.message = message;
            this.author = author;
            this.email = email;
            this.timestamp = timestamp;
            this.parentCommit = parentCommit;
            this.stagedFiles = stagedFiles;
            this.branch = branch;
        }

        // Getters
        public String getCommitHash() { return commitHash; }
        public String getTreeHash() { return treeHash; }
        public String getMessage() { return message; }
        public String getAuthor() { return author; }
        public String getEmail() { return email; }
        public long getTimestamp() { return timestamp; }
        public String getParentCommit() { return parentCommit; }
        public int getStagedFiles() { return stagedFiles; }
        public String getBranch() { return branch; }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new HashMap<>();
            map.put("commitHash", commitHash);
            map.put("treeHash", treeHash);
            map.put("message", message);
            map.put("author", author);
            map.put("email", email);
            map.put("timestamp", timestamp);
            map.put("parentCommit", parentCommit);
            map.put("stagedFiles", stagedFiles);
            map.put("branch", branch);
            map.put("commitDate", Instant.ofEpochSecond(timestamp).atOffset(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT));
            return map;
        }
    }

    /**
     * Tree entry class for building tree objects
     */
    private static class TreeEntry {
        private final String mode;
        private final String type;
        private final String hash;
        private final String name;

        public TreeEntry(String mode, String type, String hash, String name) {
            this.mode = mode;
            this.type = type;
            this.hash = hash;
            this.name = name;
        }

        public String getMode() { return mode; }
        public String getType() { return type; }
        public String getHash() { return hash; }
        public String getName() { return name; }

        public String toTreeLine() {
            return String.format("%s %s %s\t%s", mode, type, hash, name);
        }
    }

    /**
     * Main commit method
     */
    public CommitResult commit(String message, String author, String email) {
        logger.info("Starting commit process - message: '{}', author: {} <{}>", message, author, email);

        try {
            // Validate inputs
            validateCommitInputs(message, author, email);

            // Get staged entries from index
            List<IndexService.IndexEntry> stagedEntries = indexService.getStagedEntries();

            if (stagedEntries.isEmpty()) {
                throw new RuntimeException("No changes staged for commit");
            }

            logger.info("Found {} staged entries to commit", stagedEntries.size());

            // Build tree structure from staged entries
            Map<String, Object> treeStructure = buildTreeStructure(stagedEntries);

            // Create tree objects recursively
            String rootTreeHash = createTreeObjects(treeStructure, "");
            logger.info("Created root tree with hash: {}", rootTreeHash);

            // Get current HEAD (parent commit)
            String parentCommit = getCurrentHead();
            logger.debug("Parent commit: {}", parentCommit != null ? parentCommit : "none (initial commit)");

            // Create commit object
            long timestamp = Instant.now().getEpochSecond();
            String commitContent = buildCommitContent(rootTreeHash, parentCommit, author, email, message, timestamp);
            String commitHash = writeObject(commitContent, "commit");
            logger.info("Created commit with hash: {}", commitHash);

            // Update branch reference
            String currentBranch = getCurrentBranch();
            updateBranchHead(currentBranch, commitHash);
            logger.info("Updated branch '{}' to point to commit {}", currentBranch, commitHash);

            // Clear the index after successful commit
            indexService.clearIndex();
            logger.info("Cleared staging area after successful commit");

            CommitResult result = new CommitResult(
                    commitHash, rootTreeHash, message, author, email,
                    timestamp, parentCommit, stagedEntries.size(), currentBranch
            );

            logger.info("Commit completed successfully: {}", commitHash);
            return result;

        } catch (Exception e) {
            logger.error("Commit failed: {}", e.getMessage(), e);
            throw new RuntimeException("Commit failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build tree structure from staged entries
     */
    private Map<String, Object> buildTreeStructure(List<IndexService.IndexEntry> entries) {
        logger.debug("Building tree structure from {} entries", entries.size());

        Map<String, Object> tree = new HashMap<>();

        for (IndexService.IndexEntry entry : entries) {
            String gitPath = entry.getGitPath(); // e.g., "121/1/A1"
            String[] pathParts = gitPath.split("/");

            Map<String, Object> current = tree;

            // Navigate/create nested structure
            for (int i = 0; i < pathParts.length - 1; i++) {
                String part = pathParts[i];
                if (!current.containsKey(part)) {
                    current.put(part, new HashMap<String, Object>());
                }
                current = (Map<String, Object>) current.get(part);
            }

            // Add the file entry
            String fileName = pathParts[pathParts.length - 1];
            Map<String, Object> fileEntry = new HashMap<>();
            fileEntry.put("type", "blob");
            fileEntry.put("hash", entry.getBlobId());
            fileEntry.put("mode", "100644"); // regular file
            current.put(fileName, fileEntry);
        }

        logger.debug("Built tree structure with {} top-level entries", tree.size());
        return tree;
    }

    /**
     * Create tree objects recursively
     */
    private String createTreeObjects(Map<String, Object> treeStructure, String currentPath) {
        logger.debug("Creating tree object for path: '{}'", currentPath);

        List<TreeEntry> entries = new ArrayList<>();

        // Sort keys for consistent tree generation (directories first, then files)
        List<String> sortedKeys = treeStructure.keySet().stream()
                .sorted((a, b) -> {
                    Object objA = treeStructure.get(a);
                    Object objB = treeStructure.get(b);

                    boolean aIsDir = isDirectory(objA);
                    boolean bIsDir = isDirectory(objB);

                    if (aIsDir && !bIsDir) return -1;
                    if (!aIsDir && bIsDir) return 1;
                    return a.compareTo(b);
                })
                .collect(Collectors.toList());

        for (String key : sortedKeys) {
            Object value = treeStructure.get(key);

            if (isDirectory(value)) {
                // Recursively create subtree
                Map<String, Object> subTree = (Map<String, Object>) value;
                String subTreeHash = createTreeObjects(subTree, currentPath.isEmpty() ? key : currentPath + "/" + key);
                entries.add(new TreeEntry("40000", "tree", subTreeHash, key));
            } else {
                // File entry
                Map<String, Object> fileEntry = (Map<String, Object>) value;
                String mode = (String) fileEntry.get("mode");
                String hash = (String) fileEntry.get("hash");
                entries.add(new TreeEntry(mode, "blob", hash, key));
            }
        }

        // Create tree content
        StringBuilder treeContent = new StringBuilder();
        for (TreeEntry entry : entries) {
            treeContent.append(entry.getMode()).append(" ").append(entry.getName()).append("\0").append(entry.getHash()).append("\n");
        }

        // Write tree object
        String treeHash = writeObject(treeContent.toString(), "tree");
        logger.debug("Created tree object {} with {} entries at path '{}'", treeHash, entries.size(), currentPath);

        return treeHash;
    }

    /**
     * Check if an object represents a directory
     */
    private boolean isDirectory(Object obj) {
        if (!(obj instanceof Map)) {
            return false;
        }
        Map<String, Object> map = (Map<String, Object>) obj;
        return !map.containsKey("type"); // If it doesn't have a "type" field, it's a directory
    }

    /**
     * Build commit content string
     */
    private String buildCommitContent(String treeHash, String parentCommit, String author,
                                      String email, String message, long timestamp) {
        StringBuilder content = new StringBuilder();

        content.append("tree ").append(treeHash).append("\n");

        if (parentCommit != null && !parentCommit.trim().isEmpty()) {
            content.append("parent ").append(parentCommit).append("\n");
        }

        String authorLine = String.format("%s <%s> %d +0000", author, email, timestamp);
        content.append("author ").append(authorLine).append("\n");
        content.append("committer ").append(authorLine).append("\n");
        content.append("\n");
        content.append(message).append("\n");

        return content.toString();
    }

    /**
     * Write object to Git objects directory
     */
    private String writeObject(String content, String type) {
        try {
            // Create Git object format: "type size\0content"
            String header = type + " " + content.getBytes(StandardCharsets.UTF_8).length + "\0";
            String fullContent = header + content;

            // Calculate SHA-1 hash
            String hash = calculateSHA1(fullContent);

            // Create directory structure
            String dirName = hash.substring(0, 2);
            String fileName = hash.substring(2);
            Path objectDir = OBJECTS_DIR.resolve(dirName);
            Path objectFile = objectDir.resolve(fileName);

            // Create directory if it doesn't exist
            Files.createDirectories(objectDir);

            // Write compressed content if file doesn't exist
            if (!Files.exists(objectFile)) {
                byte[] compressedData = zlibCompress(fullContent.getBytes(StandardCharsets.UTF_8));
                Files.write(objectFile, compressedData);
                logger.debug("Wrote {} object: {}", type, hash);
            } else {
                logger.debug("{} object already exists: {}", type, hash);
            }

            return hash;

        } catch (Exception e) {
            logger.error("Failed to write {} object", type, e);
            throw new RuntimeException("Failed to write " + type + " object", e);
        }
    }

    /**
     * Get current HEAD commit hash
     */
    private String getCurrentHead() {
        try {
            if (!Files.exists(HEAD_FILE)) {
                return null; // No previous commits
            }

            String headContent = Files.readString(HEAD_FILE, StandardCharsets.UTF_8).trim();

            if (headContent.startsWith("ref: ")) {
                // HEAD points to a branch
                String refPath = headContent.substring(5);
                Path branchFile = VCS_DIR.resolve(refPath);

                if (Files.exists(branchFile)) {
                    return Files.readString(branchFile, StandardCharsets.UTF_8).trim();
                }
            } else {
                // Detached HEAD - points directly to commit
                return headContent;
            }

            return null;

        } catch (IOException e) {
            logger.debug("No previous HEAD found");
            return null;
        }
    }

    /**
     * Get current branch name
     */
    private String getCurrentBranch() {
        try {
            if (!Files.exists(HEAD_FILE)) {
                return "master"; // Default branch
            }

            String headContent = Files.readString(HEAD_FILE, StandardCharsets.UTF_8).trim();

            if (headContent.startsWith("ref: refs/heads/")) {
                return headContent.substring(16); // Remove "ref: refs/heads/"
            }

            return "master"; // Default if detached HEAD

        } catch (IOException e) {
            logger.debug("Failed to read HEAD, using default branch 'master'");
            return "master";
        }
    }

    /**
     * Update branch head to point to new commit
     */
    private void updateBranchHead(String branchName, String commitHash) {
        try {
            Path branchFile = HEADS_DIR.resolve(branchName);
            Files.createDirectories(branchFile.getParent());
            Files.writeString(branchFile, commitHash + "\n", StandardCharsets.UTF_8);

            logger.debug("Updated branch '{}' to commit: {}", branchName, commitHash);

        } catch (IOException e) {
            logger.error("Failed to update branch head", e);
            throw new RuntimeException("Failed to update branch head", e);
        }
    }

    /**
     * Calculate SHA-1 hash
     */
    private String calculateSHA1(String content) {
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] hashBytes = sha1.digest(content.getBytes(StandardCharsets.UTF_8));
            return bytesToHex(hashBytes);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-1 algorithm not available", e);
        }
    }

    /**
     * Compress data using zlib
     */
    private byte[] zlibCompress(byte[] data) {
        try {
            Deflater deflater = new Deflater();
            deflater.setInput(data);
            deflater.finish();

            byte[] buffer = new byte[data.length * 2];
            int compressedSize = deflater.deflate(buffer);
            deflater.end();

            byte[] result = new byte[compressedSize];
            System.arraycopy(buffer, 0, result, 0, compressedSize);

            return result;

        } catch (Exception e) {
            throw new RuntimeException("Compression failed", e);
        }
    }

    /**
     * Convert bytes to hex string
     */
    private String bytesToHex(byte[] bytes) {
        final char[] HEX_CHARS = "0123456789abcdef".toCharArray();
        StringBuilder result = new StringBuilder(bytes.length * 2);

        for (byte b : bytes) {
            result.append(HEX_CHARS[(b >> 4) & 0xF]);
            result.append(HEX_CHARS[b & 0xF]);
        }

        return result.toString();
    }

    /**
     * Validate commit inputs
     */
    private void validateCommitInputs(String message, String author, String email) {
        if (message == null || message.trim().isEmpty()) {
            throw new IllegalArgumentException("Commit message cannot be empty");
        }
        if (author == null || author.trim().isEmpty()) {
            throw new IllegalArgumentException("Author name cannot be empty");
        }
        if (email == null || email.trim().isEmpty() || !email.contains("@")) {
            throw new IllegalArgumentException("Valid email address is required");
        }
    }

    /**
     * Get commit history for current branch
     */
    public List<Map<String, Object>> getCommitHistory(int limit) {
        logger.info("Getting commit history with limit: {}", limit);

        List<Map<String, Object>> history = new ArrayList<>();
        String currentCommit = getCurrentHead();

        try {
            while (currentCommit != null && history.size() < limit) {
                Map<String, Object> commitInfo = readCommitObject(currentCommit);
                if (commitInfo != null) {
                    history.add(commitInfo);
                    currentCommit = (String) commitInfo.get("parent");
                } else {
                    break;
                }
            }
        } catch (Exception e) {
            logger.error("Failed to read commit history", e);
        }

        return history;
    }

    /**
     * Read commit object from disk
     */
    private Map<String, Object> readCommitObject(String commitHash) {
        try {
            String dirName = commitHash.substring(0, 2);
            String fileName = commitHash.substring(2);
            Path objectFile = OBJECTS_DIR.resolve(dirName).resolve(fileName);

            if (!Files.exists(objectFile)) {
                throw new RuntimeException("Commit object not found: " + commitHash);
            }

            byte[] compressedData = Files.readAllBytes(objectFile);
            logger.debug("Read {} bytes of compressed data for commit {}", compressedData.length, commitHash);

            byte[] decompressedData = zlibDecompress(compressedData);
            logger.debug("Decompressed to {} bytes for commit {}", decompressedData.length, commitHash);

            String content = new String(decompressedData, StandardCharsets.UTF_8);
            logger.debug("Raw commit content for {}: '{}'", commitHash, content);

            // Try the simple parsing method first
            Map<String, Object> parsed = parseCommitContentSimple(content, commitHash);

            // If tree hash is still missing, something is seriously wrong
            if (parsed.get("tree") == null || parsed.get("tree").toString().trim().isEmpty()) {
                logger.error("CRITICAL: Tree hash still null after parsing. Raw content bytes: {}",
                        Arrays.toString(decompressedData));

                // Try manual extraction as last resort
                String treeHash = extractTreeHashManually(content);
                if (treeHash != null) {
                    parsed.put("tree", treeHash);
                    logger.info("Manually extracted tree hash: {}", treeHash);
                }
            }

            logger.debug("Final parsed commit data for {}: {}", commitHash, parsed);
            return parsed;

        } catch (Exception e) {
            logger.error("Failed to read commit object: {}", commitHash, e);
            throw new RuntimeException("Failed to read commit object: " + commitHash, e);
        }
    }
    private Map<String, Object> parseCommitContentSimple(String content, String hash) {
        Map<String, Object> commit = new HashMap<>();
        commit.put("hash", hash);

        logger.debug("Simple parsing of commit content: {}", content);

        // Split content into lines
        String[] allLines = content.split("\\r?\\n");

        // Find where the actual commit data starts (after the "commit NNN" line)
        int dataStartIndex = -1;
        for (int i = 0; i < allLines.length; i++) {
            String line = allLines[i].trim();
            if (line.startsWith("tree ") || line.startsWith("parent ") ||
                    line.startsWith("author ") || line.startsWith("committer ")) {
                dataStartIndex = i;
                break;
            }
        }

        if (dataStartIndex == -1) {
            logger.error("Could not find commit data start in content: {}", content);
            return commit;
        }

        // Parse from the data start
        StringBuilder messageBuilder = new StringBuilder();
        boolean inMessage = false;

        for (int i = dataStartIndex; i < allLines.length; i++) {
            String line = allLines[i];
            logger.debug("Processing data line {}: '{}'", i, line);

            if (inMessage) {
                if (messageBuilder.length() > 0) {
                    messageBuilder.append("\n");
                }
                messageBuilder.append(line);
            } else if (line.startsWith("tree ")) {
                String treeHash = line.substring(5).trim();
                commit.put("tree", treeHash);
                logger.debug("Found tree hash: '{}'", treeHash);
            } else if (line.startsWith("parent ")) {
                String parentHash = line.substring(7).trim();
                commit.put("parent", parentHash);
                logger.debug("Found parent hash: '{}'", parentHash);
            } else if (line.startsWith("author ")) {
                String authorInfo = line.substring(7).trim();
                commit.put("author", authorInfo);
                logger.debug("Found author: '{}'", authorInfo);
            } else if (line.startsWith("committer ")) {
                String committerInfo = line.substring(10).trim();
                commit.put("committer", committerInfo);
                logger.debug("Found committer: '{}'", committerInfo);
            } else if (line.trim().isEmpty()) {
                logger.debug("Found empty line, switching to message parsing");
                inMessage = true;
            } else if (!inMessage) {
                logger.warn("Unrecognized commit line before message: '{}'", line);
            }
        }

        String message = messageBuilder.toString().trim();
        commit.put("message", message);
        logger.debug("Final commit message: '{}'", message);

        // Log final result
        logger.info("Parsed commit data: {}", commit);

        return commit;
    }
    /**
     * Last resort manual tree hash extraction
     */
    private String extractTreeHashManually(String content) {
        try {
            // Look for "tree " followed by 40 hex characters
            java.util.regex.Pattern treePattern = java.util.regex.Pattern.compile("tree ([a-f0-9]{40})");
            java.util.regex.Matcher matcher = treePattern.matcher(content);

            if (matcher.find()) {
                String treeHash = matcher.group(1);
                logger.info("Manually extracted tree hash using regex: {}", treeHash);
                return treeHash;
            }

            logger.error("Could not manually extract tree hash from content: {}", content);
            return null;

        } catch (Exception e) {
            logger.error("Failed to manually extract tree hash", e);
            return null;
        }
    }
    /**
     * Decompress zlib data
     */
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
            logger.error("Failed to decompress zlib data", e);
            throw new RuntimeException("Failed to decompress zlib data", e);
        }
    }

    /**
     * Parse commit content
     */
    private Map<String, Object> parseCommitContent(String content, String hash) {
        Map<String, Object> commit = new HashMap<>();
        commit.put("hash", hash);

        logger.debug("Parsing commit content: {}", content);

        // Find the header end (Git objects start with "type size\0")
        int nullIndex = content.indexOf('\0');
        if (nullIndex == -1) {
            logger.error("Invalid commit object format - no null terminator found in content: {}", content);
            return commit;
        }

        String header = content.substring(0, nullIndex);
        String commitData = content.substring(nullIndex + 1);

        logger.debug("Commit header: '{}'", header);
        logger.debug("Commit data: '{}'", commitData);

        // Validate header format
        if (!header.startsWith("commit ")) {
            logger.error("Invalid commit object - header doesn't start with 'commit': {}", header);
            return commit;
        }

        // The commitData now contains the actual commit information
        // Parse the commit data line by line
        String[] lines = commitData.split("\\r?\\n"); // Handle both \n and \r\n
        StringBuilder messageBuilder = new StringBuilder();
        boolean inMessage = false;

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            logger.debug("Processing line {}: '{}'", i, line);

            if (inMessage) {
                if (messageBuilder.length() > 0) {
                    messageBuilder.append("\n");
                }
                messageBuilder.append(line);
            } else if (line.startsWith("tree ")) {
                String treeHash = line.substring(5).trim();
                commit.put("tree", treeHash);
                logger.debug("Found tree hash: '{}'", treeHash);
            } else if (line.startsWith("parent ")) {
                String parentHash = line.substring(7).trim();
                commit.put("parent", parentHash);
                logger.debug("Found parent hash: '{}'", parentHash);
            } else if (line.startsWith("author ")) {
                String authorInfo = line.substring(7).trim();
                commit.put("author", authorInfo);
                logger.debug("Found author: '{}'", authorInfo);
            } else if (line.startsWith("committer ")) {
                String committerInfo = line.substring(10).trim();
                commit.put("committer", committerInfo);
                logger.debug("Found committer: '{}'", committerInfo);
            } else if (line.trim().isEmpty()) {
                logger.debug("Found empty line, switching to message parsing");
                inMessage = true;
            } else {
                logger.warn("Unrecognized commit line: '{}'", line);
            }
        }

        String message = messageBuilder.toString().trim();
        commit.put("message", message);
        logger.debug("Final commit message: '{}'", message);

        // Validate required fields
        if (!commit.containsKey("tree") || commit.get("tree") == null || commit.get("tree").toString().trim().isEmpty()) {
            logger.error("Commit parsing failed - no tree hash found. Parsed data: {}", commit);
            logger.error("Raw lines were: {}", Arrays.toString(lines));
        } else {
            logger.debug("Successfully found tree hash: {}", commit.get("tree"));
        }

        return commit;
    }


}