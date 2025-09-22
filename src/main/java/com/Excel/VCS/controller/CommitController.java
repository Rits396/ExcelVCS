package com.Excel.VCS.controller;


import com.Excel.VCS.service.CommitService;
import com.Excel.VCS.service.IndexService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/vcs")
public class CommitController {

    private static final Logger logger = LoggerFactory.getLogger(CommitController.class);

    private final CommitService commitService;
    private final IndexService indexService;

    public CommitController(CommitService commitService, IndexService indexService) {
        this.commitService = commitService;
        this.indexService = indexService;
    }

    /**
     * Create a new commit with the staged changes
     */
    @PostMapping("/commit")
    public ResponseEntity<Object> createCommit(@RequestBody CommitRequest request) {
        logger.info("Received commit request - message: '{}', author: {} <{}>",
                request.getMessage(), request.getAuthor(), request.getEmail());

        try {
            // Validate request
            if (request.getMessage() == null || request.getMessage().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Commit message is required",
                        "status", "error"
                ));
            }

            if (request.getAuthor() == null || request.getAuthor().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Author name is required",
                        "status", "error"
                ));
            }

            if (request.getEmail() == null || request.getEmail().trim().isEmpty() || !request.getEmail().contains("@")) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Valid email address is required",
                        "status", "error"
                ));
            }

            // Check if there are staged changes
            List<IndexService.IndexEntry> stagedEntries = indexService.getStagedEntries();
            if (stagedEntries.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "No changes staged for commit",
                        "message", "Use 'add' commands to stage changes before committing",
                        "status", "error",
                        "stagedCount", 0
                ));
            }

            // Create the commit
            CommitService.CommitResult result = commitService.commit(
                    request.getMessage().trim(),
                    request.getAuthor().trim(),
                    request.getEmail().trim()
            );

            logger.info("Successfully created commit: {}", result.getCommitHash());

            Map<String, Object> response = result.toMap();
            response.put("status", "success");
            response.put("message", "Commit created successfully");

            return ResponseEntity.ok(response);

        } catch (RuntimeException e) {
            logger.error("Commit failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Commit failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during commit", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred while creating the commit",
                    "status", "error"
            ));
        }
    }

    /**
     * Get commit history
     */
    @GetMapping("/commits")
    public ResponseEntity<Object> getCommitHistory(
            @RequestParam(defaultValue = "10") int limit,
            @RequestParam(defaultValue = "false") boolean verbose) {

        logger.info("Getting commit history with limit: {}, verbose: {}", limit, verbose);

        try {
            if (limit <= 0 || limit > 100) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Limit must be between 1 and 100",
                        "status", "error"
                ));
            }

            List<Map<String, Object>> history = commitService.getCommitHistory(limit);

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("commits", history);
            response.put("totalReturned", history.size());
            response.put("limit", limit);
            response.put("hasMore", history.size() == limit);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get commit history", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get commit history",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    /**
     * Get current repository status
     */
    @GetMapping("/status")
    public ResponseEntity<Object> getRepositoryStatus() {
        logger.info("Getting repository status");

        try {
            // Get staged entries
            List<IndexService.IndexEntry> stagedEntries = indexService.getStagedEntries();

            // Get index statistics
            Map<String, Object> indexStats = indexService.getIndexStats();

            // Build status response
            Map<String, Object> status = new HashMap<>();
            status.put("status", "success");
            status.put("staged", stagedEntries.size());
            status.put("clean", stagedEntries.isEmpty());

            // Staged files info
            Map<String, Object> stagedInfo = new HashMap<>();
            stagedInfo.put("count", stagedEntries.size());

            if (!stagedEntries.isEmpty()) {
                Map<String, Long> workbookCounts = stagedEntries.stream()
                        .collect(java.util.stream.Collectors.groupingBy(
                                IndexService.IndexEntry::getWorkbookId,
                                java.util.stream.Collectors.counting()
                        ));
                stagedInfo.put("byWorkbook", workbookCounts);

                List<String> stagedPaths = stagedEntries.stream()
                        .map(IndexService.IndexEntry::getGitPath)
                        .sorted()
                        .collect(java.util.stream.Collectors.toList());
                stagedInfo.put("files", stagedPaths);
            }

            status.put("stagedFiles", stagedInfo);
            status.put("indexStats", indexStats);

            // Get recent commit info if available
            List<Map<String, Object>> recentCommits = commitService.getCommitHistory(1);
            if (!recentCommits.isEmpty()) {
                status.put("lastCommit", recentCommits.get(0));
            }

            return ResponseEntity.ok(status);

        } catch (Exception e) {
            logger.error("Failed to get repository status", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get repository status",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    /**
     * Get specific commit details
     */
    @GetMapping("/commits/{commitHash}")
    public ResponseEntity<Object> getCommitDetails(@PathVariable String commitHash) {
        logger.info("Getting details for commit: {}", commitHash);

        try {
            if (commitHash == null || commitHash.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Commit hash is required",
                        "status", "error"
                ));
            }

            // This would need to be implemented in CommitService
            // For now, return a placeholder response
            Map<String, Object> response = new HashMap<>();
            response.put("status", "not_implemented");
            response.put("message", "Commit details endpoint not yet implemented");
            response.put("commitHash", commitHash);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get commit details for: {}", commitHash, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get commit details",
                    "message", e.getMessage(),
                    "status", "error",
                    "commitHash", commitHash
            ));
        }
    }

    /**
     * Check if repository has any commits
     */
    @GetMapping("/has-commits")
    public ResponseEntity<Object> hasCommits() {
        logger.info("Checking if repository has commits");

        try {
            List<Map<String, Object>> history = commitService.getCommitHistory(1);
            boolean hasCommits = !history.isEmpty();

            Map<String, Object> response = new HashMap<>();
            response.put("status", "success");
            response.put("hasCommits", hasCommits);
            response.put("totalCommits", hasCommits ? "unknown" : 0); // Would need separate method to count all

            if (hasCommits) {
                response.put("latestCommit", history.get(0));
            }

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to check for commits", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to check for commits",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    /**
     * Request class for commit operations
     */
    public static class CommitRequest {
        private String message;
        private String author;
        private String email;

        // Constructors
        public CommitRequest() {}

        public CommitRequest(String message, String author, String email) {
            this.message = message;
            this.author = author;
            this.email = email;
        }

        // Getters and Setters
        public String getMessage() { return message; }
        public void setMessage(String message) { this.message = message; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        @Override
        public String toString() {
            return String.format("CommitRequest{message='%s', author='%s', email='%s'}",
                    message, author, email);
        }
    }
}