package com.Excel.VCS.controller;
import com.Excel.VCS.service.RollbackService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/vcs/rollback")
public class RollbackController {

    private static final Logger logger = LoggerFactory.getLogger(RollbackController.class);

    private final RollbackService rollbackService;

    public RollbackController(RollbackService rollbackService) {
        this.rollbackService = rollbackService;
    }

    /**
     * Hard reset - destructive rollback that changes working directory
     * POST /vcs/rollback/hard-reset
     */
    @PostMapping("/hard-reset")
    public ResponseEntity<Object> hardReset(@RequestBody RollbackRequest request) {
        logger.info("Received hard reset request to commit: {}", request.getTargetCommit());

        try {
            // Validate request
            if (request.getTargetCommit() == null || request.getTargetCommit().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Target commit hash is required",
                        "status", "error"
                ));
            }

            if (!isValidCommitHash(request.getTargetCommit())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid commit hash format",
                        "status", "error"
                ));
            }

            // Perform hard reset
            RollbackService.RollbackResult result = rollbackService.hardReset(request.getTargetCommit());

            logger.info("Hard reset completed successfully to commit: {}", request.getTargetCommit());

            Map<String, Object> response = result.toMap();
            response.put("status", "success");
            response.put("message", "Hard reset completed successfully");
            response.put("warning", "This operation has modified your working directory");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Hard reset failed - invalid argument: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (RuntimeException e) {
            logger.error("Hard reset failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Hard reset failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during hard reset", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred during hard reset",
                    "status", "error"
            ));
        }
    }

    /**
     * Soft reset - non-destructive rollback that only moves HEAD
     * POST /vcs/rollback/soft-reset
     */
    @PostMapping("/soft-reset")
    public ResponseEntity<Object> softReset(@RequestBody RollbackRequest request) {
        logger.info("Received soft reset request to commit: {}", request.getTargetCommit());

        try {
            // Validate request
            if (request.getTargetCommit() == null || request.getTargetCommit().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Target commit hash is required",
                        "status", "error"
                ));
            }

            if (!isValidCommitHash(request.getTargetCommit())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid commit hash format",
                        "status", "error"
                ));
            }

            // Perform soft reset
            RollbackService.RollbackResult result = rollbackService.softReset(request.getTargetCommit());

            logger.info("Soft reset completed successfully to commit: {}", request.getTargetCommit());

            Map<String, Object> response = result.toMap();
            response.put("status", "success");
            response.put("message", "Soft reset completed successfully");
            response.put("info", "Working directory and staging area unchanged");

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Soft reset failed - invalid argument: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (RuntimeException e) {
            logger.error("Soft reset failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Soft reset failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during soft reset", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred during soft reset",
                    "status", "error"
            ));
        }
    }

    /**
     * Revert commit - creates new commit that undoes specified commit
     * POST /vcs/rollback/revert
     */
    @PostMapping("/revert")
    public ResponseEntity<Object> revertCommit(@RequestBody RevertRequest request) {
        logger.info("Received revert request for commit: {}", request.getCommitToRevert());

        try {
            // Validate request
            if (request.getCommitToRevert() == null || request.getCommitToRevert().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Commit to revert is required",
                        "status", "error"
                ));
            }

            if (!isValidCommitHash(request.getCommitToRevert())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid commit hash format",
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

            // Perform revert
            RollbackService.RollbackResult result = rollbackService.revertCommit(
                    request.getCommitToRevert(),
                    request.getAuthor(),
                    request.getEmail()
            );

            logger.info("Revert completed successfully, created commit: {}", result.getTargetCommit());

            Map<String, Object> response = result.toMap();
            response.put("status", "success");
            response.put("message", "Revert completed successfully");
            response.put("revertCommit", result.getTargetCommit());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Revert failed - invalid argument: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (RuntimeException e) {
            logger.error("Revert failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Revert failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during revert", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred during revert",
                    "status", "error"
            ));
        }
    }

    /**
     * Cherry-pick commit - apply changes from another commit
     * POST /vcs/rollback/cherry-pick
     */
    @PostMapping("/cherry-pick")
    public ResponseEntity<Object> cherryPick(@RequestBody RevertRequest request) {
        logger.info("Received cherry-pick request for commit: {}", request.getCommitToRevert());

        try {
            // Validate request (reusing RevertRequest as it has same fields)
            if (request.getCommitToRevert() == null || request.getCommitToRevert().trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Commit to cherry-pick is required",
                        "status", "error"
                ));
            }

            if (!isValidCommitHash(request.getCommitToRevert())) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid commit hash format",
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

            // Perform cherry-pick
            RollbackService.RollbackResult result = rollbackService.cherryPick(
                    request.getCommitToRevert(),
                    request.getAuthor(),
                    request.getEmail()
            );

            logger.info("Cherry-pick completed successfully, created commit: {}", result.getTargetCommit());

            Map<String, Object> response = result.toMap();
            response.put("status", "success");
            response.put("message", "Cherry-pick completed successfully");
            response.put("cherryPickCommit", result.getTargetCommit());

            return ResponseEntity.ok(response);

        } catch (IllegalArgumentException e) {
            logger.error("Cherry-pick failed - invalid argument: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (RuntimeException e) {
            logger.error("Cherry-pick failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Cherry-pick failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during cherry-pick", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred during cherry-pick",
                    "status", "error"
            ));
        }
    }

    /**
     * Preview rollback operation - shows what would be affected without making changes
     * GET /vcs/rollback/preview
     */
    @GetMapping("/preview")
    public ResponseEntity<Object> previewRollback(
            @RequestParam String targetCommit,
            @RequestParam(defaultValue = "hard_reset") String rollbackType) {

        logger.info("Received rollback preview request for commit: {} with type: {}", targetCommit, rollbackType);

        try {
            // Validate request
            if (targetCommit == null || targetCommit.trim().isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Target commit hash is required",
                        "status", "error"
                ));
            }

            if (!isValidCommitHash(targetCommit)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid commit hash format",
                        "status", "error"
                ));
            }

            if (!isValidRollbackType(rollbackType)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Invalid rollback type. Valid types: hard_reset, soft_reset, revert, cherry_pick",
                        "status", "error"
                ));
            }

            // Generate preview
            Map<String, Object> preview = rollbackService.previewRollback(targetCommit, rollbackType);
            preview.put("status", "success");
            preview.put("message", "Rollback preview generated successfully");

            return ResponseEntity.ok(preview);

        } catch (IllegalArgumentException e) {
            logger.error("Preview failed - invalid argument: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid request",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (RuntimeException e) {
            logger.error("Preview failed: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Preview failed",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        } catch (Exception e) {
            logger.error("Unexpected error during preview", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "An unexpected error occurred during preview",
                    "status", "error"
            ));
        }
    }

    /**
     * Get rollback history - shows recent rollback operations
     * GET /vcs/rollback/history
     */
    @GetMapping("/history")
    public ResponseEntity<Object> getRollbackHistory(
            @RequestParam(defaultValue = "10") int limit) {

        logger.info("Getting rollback history with limit: {}", limit);

        try {
            if (limit <= 0 || limit > 50) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Limit must be between 1 and 50",
                        "status", "error"
                ));
            }

            // This would need to be implemented to track rollback operations
            // For now, return a placeholder
            Map<String, Object> response = new HashMap<>();
            response.put("status", "not_implemented");
            response.put("message", "Rollback history tracking not yet implemented");
            response.put("limit", limit);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            logger.error("Failed to get rollback history", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get rollback history",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    // ========== PRIVATE HELPER METHODS ==========

    private boolean isValidCommitHash(String commitHash) {
        if (commitHash == null) {
            return false;
        }

        String trimmed = commitHash.trim();
        if (trimmed.length() != 40) {
            return false;
        }

        return trimmed.matches("^[a-fA-F0-9]{40}$");
    }

    private boolean isValidRollbackType(String rollbackType) {
        return rollbackType != null &&
                (rollbackType.equals("hard_reset") ||
                        rollbackType.equals("soft_reset") ||
                        rollbackType.equals("revert") ||
                        rollbackType.equals("cherry_pick"));
    }

    // ========== REQUEST CLASSES ==========

    /**
     * Request class for basic rollback operations (reset operations)
     */
    public static class RollbackRequest {
        private String targetCommit;

        // Constructors
        public RollbackRequest() {}

        public RollbackRequest(String targetCommit) {
            this.targetCommit = targetCommit;
        }

        // Getters and Setters
        public String getTargetCommit() { return targetCommit; }
        public void setTargetCommit(String targetCommit) { this.targetCommit = targetCommit; }

        @Override
        public String toString() {
            return String.format("RollbackRequest{targetCommit='%s'}", targetCommit);
        }
    }

    /**
     * Request class for revert and cherry-pick operations
     */
    public static class RevertRequest {
        private String commitToRevert;
        private String author;
        private String email;

        // Constructors
        public RevertRequest() {}

        public RevertRequest(String commitToRevert, String author, String email) {
            this.commitToRevert = commitToRevert;
            this.author = author;
            this.email = email;
        }

        // Getters and Setters
        public String getCommitToRevert() { return commitToRevert; }
        public void setCommitToRevert(String commitToRevert) { this.commitToRevert = commitToRevert; }

        public String getAuthor() { return author; }
        public void setAuthor(String author) { this.author = author; }

        public String getEmail() { return email; }
        public void setEmail(String email) { this.email = email; }

        @Override
        public String toString() {
            return String.format("RevertRequest{commitToRevert='%s', author='%s', email='%s'}",
                    commitToRevert, author, email);
        }
    }
}
