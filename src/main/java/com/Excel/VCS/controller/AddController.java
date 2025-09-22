package com.Excel.VCS.controller;

import com.Excel.VCS.model.Cell;
import com.Excel.VCS.model.Sheet;
import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.repository.WorkbookRepository;
import com.Excel.VCS.service.AddService;
import com.Excel.VCS.service.IndexService;
import com.Excel.VCS.service.WorkbookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;
import java.util.zip.Inflater;

@RestController
@RequestMapping("/workbooks")
public class AddController {

    private static final Logger logger = LoggerFactory.getLogger(AddController.class);

    AddService addService;
    WorkbookService workbookService;
    WorkbookRepository workbookRepository;
    IndexService indexService;

    public AddController(AddService addService, WorkbookService workbookService,
                         WorkbookRepository workbookRepository, IndexService indexService) {
        this.addService = addService;
        this.workbookService = workbookService;
        this.workbookRepository = workbookRepository;
        this.indexService = indexService;
    }

    @PostMapping("/add/{workbookId}/sheets/{sheetNumber}/cell")
    public ResponseEntity<Object> add(@PathVariable String workbookId,
                                      @PathVariable int sheetNumber,
                                      @RequestParam String row,
                                      @RequestParam int col) {

        logger.info("Received add request - workbookId: {}, sheetNumber: {}, row: {}, col: {}",
                workbookId, sheetNumber, row, col);

        try {
            Map<String, Object> result = addService.add(workbookId, sheetNumber, row, col);
            logger.info("Successfully processed add request for cell {}{}. Blob ID: {}",
                    row, col, result.get("blobId"));

            return ResponseEntity.ok(result);

        } catch (IllegalArgumentException e) {
            logger.warn("Invalid input for add request: {}", e.getMessage());
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Invalid input",
                    "message", e.getMessage(),
                    "status", "error"
            ));

        } catch (RuntimeException e) {
            logger.error("Service error during add request: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Internal server error",
                    "message", e.getMessage(),
                    "status", "error",
                    "type", "service_error"
            ));

        } catch (Exception e) {
            logger.error("Unexpected error during add request", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Unexpected error occurred",
                    "message", "Error while adding cell to staging area",
                    "status", "error",
                    "type", "unknown_error"
            ));
        }
    }

    @GetMapping("/verify-blob/{blobId}")
    public ResponseEntity<Object> verifyBlob(@PathVariable String blobId) {
        try {
            // Read the compressed file
            String dirName = blobId.substring(0, 2);
            String fileName = blobId.substring(2);
            Path filePath = Paths.get(".VCS", "objects", dirName, fileName);

            if (!Files.exists(filePath)) {
                return ResponseEntity.notFound().build();
            }

            byte[] compressedData = Files.readAllBytes(filePath);

            // Decompress and extract content
            String originalContent = decompressAndExtractContent(compressedData);

            Map<String, Object> result = new HashMap<>();
            result.put("blobId", blobId);
            result.put("filePath", filePath.toAbsolutePath().toString());
            result.put("compressedSize", compressedData.length);
            result.put("originalContent", originalContent);
            result.put("exists", true);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to verify blob",
                    "message", e.getMessage()
            ));
        }
    }

    private String decompressAndExtractContent(byte[] compressedData) {
        try {
            // Decompress using zlib
            Inflater inflater = new Inflater();
            inflater.setInput(compressedData);

            byte[] buffer = new byte[1024];
            int decompressedSize = inflater.inflate(buffer);
            inflater.end();

            byte[] decompressed = new byte[decompressedSize];
            System.arraycopy(buffer, 0, decompressed, 0, decompressedSize);

            // Find null byte separator
            int nullIndex = -1;
            for (int i = 0; i < decompressed.length; i++) {
                if (decompressed[i] == 0) {
                    nullIndex = i;
                    break;
                }
            }

            if (nullIndex == -1) {
                return "Invalid blob format";
            }

            // Extract content after header
            byte[] contentBytes = new byte[decompressed.length - nullIndex - 1];
            System.arraycopy(decompressed, nullIndex + 1, contentBytes, 0, contentBytes.length);

            return new String(contentBytes, StandardCharsets.UTF_8);

        } catch (Exception e) {
            return "Decompression failed: " + e.getMessage();
        }
    }

    @GetMapping("/debug-sheet-structure/{workbookId}/{sheetNumber}")
    public ResponseEntity<Object> debugSheetStructure(@PathVariable String workbookId,
                                                      @PathVariable int sheetNumber) {
        try {
            Workbook workbook = workbookRepository.findById(workbookId).orElse(null);
            if (workbook == null) {
                return ResponseEntity.ok(Map.of("error", "Workbook not found"));
            }

            Sheet sheet = workbook.getSheetByNumber(sheetNumber);
            if (sheet == null) {
                return ResponseEntity.ok(Map.of("error", "Sheet not found", "sheetNumber", sheetNumber));
            }

            Map<String, Object> result = new HashMap<>();
            result.put("sheetNumber", sheet.getNumber());
            result.put("totalCells", sheet.getCells().size());
            result.put("cellKeys", sheet.getCells().keySet());

            // Try to get specific cells
            Cell cellA1 = sheet.getCell("A", 1);
            result.put("cellA1Exists", cellA1 != null);
            result.put("cellA1Value", cellA1 != null ? cellA1.getValue() : null);
            result.put("expectedKey", "A:1");
            result.put("hasExpectedKey", sheet.getCells().containsKey("A:1"));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }

    // Updated index endpoints for Git-like format

    @GetMapping("/index/raw")
    public ResponseEntity<Object> readIndexFileRaw() {
        logger.info("Reading raw index file (Git format)");

        try {
            Path indexPath = Paths.get(".VCS", "index");

            if (!Files.exists(indexPath)) {
                return ResponseEntity.ok(Map.of(
                        "status", "not_found",
                        "message", "Index file does not exist",
                        "path", indexPath.toAbsolutePath().toString()
                ));
            }

            // Read file as string (Git format is plain text)
            String content = Files.readString(indexPath, StandardCharsets.UTF_8);
            List<String> lines = Arrays.asList(content.split("\n"));

            // Filter out empty lines for stats
            List<String> nonEmptyLines = lines.stream()
                    .filter(line -> !line.trim().isEmpty() && !line.startsWith("#"))
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", "git-like");
            result.put("path", indexPath.toAbsolutePath().toString());
            result.put("size", Files.size(indexPath));
            result.put("lastModified", Files.getLastModifiedTime(indexPath).toString());
            result.put("content", content);
            result.put("lines", lines);
            result.put("totalLines", lines.size());
            result.put("entryCount", nonEmptyLines.size());
            result.put("isEmpty", content.trim().isEmpty());

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to read index file", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to read index file",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    @GetMapping("/index/parsed")
    public ResponseEntity<Object> readIndexFileParsed() {
        logger.info("Reading and parsing index file (Git format)");

        try {
            Path indexPath = Paths.get(".VCS", "index");

            if (!Files.exists(indexPath)) {
                return ResponseEntity.ok(Map.of(
                        "status", "not_found",
                        "message", "Index file does not exist",
                        "path", indexPath.toAbsolutePath().toString()
                ));
            }

            String content = Files.readString(indexPath, StandardCharsets.UTF_8);

            if (content.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "empty",
                        "message", "Index file is empty",
                        "path", indexPath.toAbsolutePath().toString()
                ));
            }

            // Parse Git-like format entries
            List<String> lines = Arrays.asList(content.split("\n"));
            List<Map<String, Object>> parsedEntries = new ArrayList<>();
            List<String> parseErrors = new ArrayList<>();

            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue; // Skip empty lines and comments
                }

                try {
                    IndexService.IndexEntry entry = IndexService.IndexEntry.fromGitIndexLine(line);
                    if (entry != null) {
                        Map<String, Object> entryMap = new HashMap<>();
                        entryMap.put("workbookId", entry.getWorkbookId());
                        entryMap.put("sheetNumber", entry.getSheetNumber());
                        entryMap.put("row", entry.getRow());
                        entryMap.put("col", entry.getCol());
                        entryMap.put("blobId", entry.getBlobId());
                        entryMap.put("cellAddress", entry.getCellAddress());
                        entryMap.put("gitPath", entry.getGitPath());
                        entryMap.put("timestamp", entry.getTimestamp());
                        entryMap.put("key", entry.getKey());
                        entryMap.put("lineNumber", i + 1);
                        entryMap.put("originalLine", line);
                        parsedEntries.add(entryMap);
                    }
                } catch (Exception e) {
                    parseErrors.add("Line " + (i + 1) + ": " + e.getMessage() + " [" + line + "]");
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", "git-like");
            result.put("path", indexPath.toAbsolutePath().toString());
            result.put("entryCount", parsedEntries.size());
            result.put("entries", parsedEntries);
            result.put("parseErrors", parseErrors);
            result.put("hasParseErrors", !parseErrors.isEmpty());

            // Add statistics
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEntries", parsedEntries.size());
            stats.put("parseErrors", parseErrors.size());

            // Count by workbook
            Map<String, Long> workbookCounts = parsedEntries.stream()
                    .collect(Collectors.groupingBy(
                            entry -> (String) entry.get("workbookId"),
                            Collectors.counting()
                    ));
            stats.put("entriesByWorkbook", workbookCounts);

            // Count by sheet
            Map<String, Long> sheetCounts = parsedEntries.stream()
                    .collect(Collectors.groupingBy(
                            entry -> entry.get("workbookId") + "/sheet" + entry.get("sheetNumber"),
                            Collectors.counting()
                    ));
            stats.put("entriesBySheet", sheetCounts);

            result.put("statistics", stats);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to parse index file", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to parse index file",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    @GetMapping("/index/info")
    public ResponseEntity<Object> getIndexFileInfo() {
        logger.info("Getting index file information");

        try {
            Path indexPath = Paths.get(".VCS", "index");
            Path vcsDir = Paths.get(".VCS");

            Map<String, Object> result = new HashMap<>();

            // VCS directory info
            Map<String, Object> dirInfo = new HashMap<>();
            dirInfo.put("exists", Files.exists(vcsDir));
            dirInfo.put("path", vcsDir.toAbsolutePath().toString());
            if (Files.exists(vcsDir)) {
                dirInfo.put("readable", Files.isReadable(vcsDir));
                dirInfo.put("writable", Files.isWritable(vcsDir));
            }
            result.put("vcsDirectory", dirInfo);

            // Index file info
            Map<String, Object> fileInfo = new HashMap<>();
            fileInfo.put("exists", Files.exists(indexPath));
            fileInfo.put("path", indexPath.toAbsolutePath().toString());
            fileInfo.put("format", "git-like");

            if (Files.exists(indexPath)) {
                fileInfo.put("size", Files.size(indexPath));
                fileInfo.put("sizeFormatted", formatFileSize(Files.size(indexPath)));
                fileInfo.put("lastModified", Files.getLastModifiedTime(indexPath).toString());
                fileInfo.put("readable", Files.isReadable(indexPath));
                fileInfo.put("writable", Files.isWritable(indexPath));

                // Parse Git-like format
                try {
                    String content = Files.readString(indexPath, StandardCharsets.UTF_8);
                    fileInfo.put("isEmpty", content.trim().isEmpty());

                    if (!content.trim().isEmpty()) {
                        List<String> lines = Arrays.asList(content.split("\n"));
                        List<String> validLines = lines.stream()
                                .filter(line -> !line.trim().isEmpty() && !line.startsWith("#"))
                                .collect(Collectors.toList());

                        fileInfo.put("validFormat", true);
                        fileInfo.put("totalLines", lines.size());
                        fileInfo.put("entryCount", validLines.size());
                        fileInfo.put("commentLines", lines.size() - validLines.size());

                        // Try parsing a few entries to validate format
                        int validEntries = 0;
                        int invalidEntries = 0;
                        for (String line : validLines) {
                            try {
                                IndexService.IndexEntry.fromGitIndexLine(line);
                                validEntries++;
                            } catch (Exception e) {
                                invalidEntries++;
                            }
                        }
                        fileInfo.put("validEntries", validEntries);
                        fileInfo.put("invalidEntries", invalidEntries);

                    } else {
                        fileInfo.put("validFormat", true);
                        fileInfo.put("entryCount", 0);
                        fileInfo.put("totalLines", 0);
                        fileInfo.put("commentLines", 0);
                    }
                } catch (Exception e) {
                    fileInfo.put("validFormat", false);
                    fileInfo.put("parseError", e.getMessage());
                }
            }

            result.put("indexFile", fileInfo);
            result.put("status", "success");

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to get index file info", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get index file info",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    @GetMapping("/index/entries/{workbookId}")
    public ResponseEntity<Object> getIndexEntriesForWorkbook(@PathVariable String workbookId) {
        logger.info("Getting index entries for workbook: {}", workbookId);

        try {
            // Use IndexService to get entries for workbook
            List<IndexService.IndexEntry> entries = indexService.getStagedEntriesForWorkbook(workbookId);

            // Convert to response format
            List<Map<String, Object>> entryMaps = entries.stream()
                    .map(entry -> {
                        Map<String, Object> entryMap = new HashMap<>();
                        entryMap.put("workbookId", entry.getWorkbookId());
                        entryMap.put("sheetNumber", entry.getSheetNumber());
                        entryMap.put("row", entry.getRow());
                        entryMap.put("col", entry.getCol());
                        entryMap.put("blobId", entry.getBlobId());
                        entryMap.put("cellAddress", entry.getCellAddress());
                        entryMap.put("gitPath", entry.getGitPath());
                        entryMap.put("timestamp", entry.getTimestamp());
                        entryMap.put("originalSize", entry.getOriginalSize());
                        entryMap.put("compressedSize", entry.getCompressedSize());
                        entryMap.put("key", entry.getKey());
                        entryMap.put("gitIndexLine", entry.toGitIndexLine());
                        return entryMap;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", "git-like");
            result.put("workbookId", workbookId);
            result.put("entryCount", entryMaps.size());
            result.put("entries", entryMaps);

            // Add sheet breakdown
            Map<String, Long> sheetCounts = entries.stream()
                    .collect(Collectors.groupingBy(
                            entry -> "sheet" + entry.getSheetNumber(),
                            Collectors.counting()
                    ));
            result.put("entriesBySheet", sheetCounts);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to get index entries for workbook: {}", workbookId, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get index entries",
                    "message", e.getMessage(),
                    "status", "error",
                    "workbookId", workbookId
            ));
        }
    }

    @GetMapping("/index/entries/{workbookId}/sheet/{sheetNumber}")
    public ResponseEntity<Object> getIndexEntriesForSheet(@PathVariable String workbookId,
                                                          @PathVariable int sheetNumber) {
        logger.info("Getting index entries for workbook: {}, sheet: {}", workbookId, sheetNumber);

        try {
            // Use IndexService to get entries for specific sheet
            List<IndexService.IndexEntry> entries = indexService.getStagedEntriesForSheet(workbookId, sheetNumber);

            // Convert to response format
            List<Map<String, Object>> entryMaps = entries.stream()
                    .map(entry -> {
                        Map<String, Object> entryMap = new HashMap<>();
                        entryMap.put("workbookId", entry.getWorkbookId());
                        entryMap.put("sheetNumber", entry.getSheetNumber());
                        entryMap.put("row", entry.getRow());
                        entryMap.put("col", entry.getCol());
                        entryMap.put("blobId", entry.getBlobId());
                        entryMap.put("cellAddress", entry.getCellAddress());
                        entryMap.put("gitPath", entry.getGitPath());
                        entryMap.put("timestamp", entry.getTimestamp());
                        entryMap.put("originalSize", entry.getOriginalSize());
                        entryMap.put("compressedSize", entry.getCompressedSize());
                        entryMap.put("key", entry.getKey());
                        entryMap.put("gitIndexLine", entry.toGitIndexLine());
                        return entryMap;
                    })
                    .collect(Collectors.toList());

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", "git-like");
            result.put("workbookId", workbookId);
            result.put("sheetNumber", sheetNumber);
            result.put("entryCount", entryMaps.size());
            result.put("entries", entryMaps);

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to get index entries for workbook: {}, sheet: {}", workbookId, sheetNumber, e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get index entries",
                    "message", e.getMessage(),
                    "status", "error",
                    "workbookId", workbookId,
                    "sheetNumber", sheetNumber
            ));
        }
    }

    @GetMapping("/index/git-format")
    public ResponseEntity<Object> getIndexInGitFormat() {
        logger.info("Getting index in Git format");

        try {
            List<String> gitLines = indexService.getIndexContentAsGitFormat();

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("format", "git-like");
            result.put("entryCount", gitLines.size());
            result.put("gitLines", gitLines);
            result.put("content", String.join("\n", gitLines));

            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to get index in Git format", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get index in Git format",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    @GetMapping("/index/stats")
    public ResponseEntity<Object> getIndexStatistics() {
        logger.info("Getting index statistics");

        try {
            Map<String, Object> stats = indexService.getIndexStats();
            stats.put("status", "success");
            stats.put("format", "git-like");

            return ResponseEntity.ok(stats);

        } catch (Exception e) {
            logger.error("Failed to get index statistics", e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to get index statistics",
                    "message", e.getMessage(),
                    "status", "error"
            ));
        }
    }

    private String formatFileSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        } else if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        } else if (bytes < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        } else {
            return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
        }
    }
}