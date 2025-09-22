package com.Excel.VCS.dto;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * Data Transfer Object for commit information
 */
public class CommitInfo {

    @JsonProperty("hash")
    private String hash;

    @JsonProperty("shortHash")
    private String shortHash;

    @JsonProperty("tree")
    private String treeHash;

    @JsonProperty("parent")
    private String parentHash;

    @JsonProperty("author")
    private AuthorInfo author;

    @JsonProperty("committer")
    private AuthorInfo committer;

    @JsonProperty("message")
    private String message;

    @JsonProperty("timestamp")
    private long timestamp;

    @JsonProperty("formattedDate")
    private String formattedDate;

    @JsonProperty("filesChanged")
    private int filesChanged;

    @JsonProperty("changedFiles")
    private List<String> changedFiles;

    // Constructors
    public CommitInfo() {
    }

    public CommitInfo(String hash, String treeHash, String message, long timestamp) {
        this.hash = hash;
        this.shortHash = hash != null && hash.length() >= 7 ? hash.substring(0, 7) : hash;
        this.treeHash = treeHash;
        this.message = message;
        this.timestamp = timestamp;
    }

    // Getters and Setters
    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
        this.shortHash = hash != null && hash.length() >= 7 ? hash.substring(0, 7) : hash;
    }

    public String getShortHash() {
        return shortHash;
    }

    public void setShortHash(String shortHash) {
        this.shortHash = shortHash;
    }

    public String getTreeHash() {
        return treeHash;
    }

    public void setTreeHash(String treeHash) {
        this.treeHash = treeHash;
    }

    public String getParentHash() {
        return parentHash;
    }

    public void setParentHash(String parentHash) {
        this.parentHash = parentHash;
    }

    public AuthorInfo getAuthor() {
        return author;
    }

    public void setAuthor(AuthorInfo author) {
        this.author = author;
    }

    public AuthorInfo getCommitter() {
        return committer;
    }

    public void setCommitter(AuthorInfo committer) {
        this.committer = committer;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public String getFormattedDate() {
        return formattedDate;
    }

    public void setFormattedDate(String formattedDate) {
        this.formattedDate = formattedDate;
    }

    public int getFilesChanged() {
        return filesChanged;
    }

    public void setFilesChanged(int filesChanged) {
        this.filesChanged = filesChanged;
    }

    public List<String> getChangedFiles() {
        return changedFiles;
    }

    public void setChangedFiles(List<String> changedFiles) {
        this.changedFiles = changedFiles;
        this.filesChanged = changedFiles != null ? changedFiles.size() : 0;
    }

    /**
     * Convert to Map for API responses
     */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new java.util.HashMap<>();
        map.put("hash", hash);
        map.put("shortHash", shortHash);
        map.put("tree", treeHash);
        map.put("parent", parentHash);
        map.put("author", author != null ? author.toMap() : null);
        map.put("committer", committer != null ? committer.toMap() : null);
        map.put("message", message);
        map.put("timestamp", timestamp);
        map.put("formattedDate", formattedDate);
        map.put("filesChanged", filesChanged);
        map.put("changedFiles", changedFiles);
        return map;
    }

    /**
     * Author/Committer information
     */
    public static class AuthorInfo {
        private String name;
        private String email;
        private long timestamp;
        private String timezone;

        public AuthorInfo() {
        }

        public AuthorInfo(String name, String email, long timestamp, String timezone) {
            this.name = name;
            this.email = email;
            this.timestamp = timestamp;
            this.timezone = timezone;
        }

        // Getters and Setters
        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getEmail() {
            return email;
        }

        public void setEmail(String email) {
            this.email = email;
        }

        public long getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(long timestamp) {
            this.timestamp = timestamp;
        }

        public String getTimezone() {
            return timezone;
        }

        public void setTimezone(String timezone) {
            this.timezone = timezone;
        }

        public Map<String, Object> toMap() {
            Map<String, Object> map = new java.util.HashMap<>();
            map.put("name", name);
            map.put("email", email);
            map.put("timestamp", timestamp);
            map.put("timezone", timezone);
            return map;
        }

        @Override
        public String toString() {
            return String.format("%s <%s> %d %s", name, email, timestamp, timezone != null ? timezone : "+0000");
        }
    }
}

