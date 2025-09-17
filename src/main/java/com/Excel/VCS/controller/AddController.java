package com.Excel.VCS.controller;

import com.Excel.VCS.model.Cell;
import com.Excel.VCS.model.Sheet;
import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.repository.WorkbookRepository;
import com.Excel.VCS.service.AddService;
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
import java.util.HashMap;
import java.util.Map;
import java.util.zip.Inflater;

@RestController
@RequestMapping("/workbooks")
public class AddController {

    private static final Logger logger = LoggerFactory.getLogger(AddController.class);

    AddService addService;
    WorkbookService workbookService;
    WorkbookRepository workbookRepository;
    public AddController(AddService addService, WorkbookService workbookService, WorkbookRepository workbookRepository) {
        this.addService = addService;
        this.workbookService = workbookService;
        this.workbookRepository=workbookRepository;
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



    // Add these methods to your existing AddController class

    @GetMapping("/index/raw")
    public ResponseEntity<Object> readIndexFileRaw() {
        logger.info("Reading raw index file");

        try {
            Path indexPath = Paths.get(".VCS", "index");

            if (!Files.exists(indexPath)) {
                return ResponseEntity.ok(Map.of(
                        "status", "not_found",
                        "message", "Index file does not exist",
                        "path", indexPath.toAbsolutePath().toString()
                ));
            }

            // Read file as string
            String content = Files.readString(indexPath, StandardCharsets.UTF_8);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("path", indexPath.toAbsolutePath().toString());
            result.put("size", Files.size(indexPath));
            result.put("lastModified", Files.getLastModifiedTime(indexPath).toString());
            result.put("content", content);
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
        logger.info("Reading and parsing index file");

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

            // Parse JSON content
            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> indexData = mapper.readValue(content, Map.class);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("path", indexPath.toAbsolutePath().toString());
            result.put("entryCount", indexData.size());
            result.put("entries", indexData);

            // Add statistics
            Map<String, Object> stats = new HashMap<>();
            stats.put("totalEntries", indexData.size());

            // Count by workbook
            Map<String, Integer> workbookCounts = new HashMap<>();
            for (Object entry : indexData.values()) {
                if (entry instanceof Map) {
                    Map<String, Object> entryMap = (Map<String, Object>) entry;
                    String workbookId = (String) entryMap.get("workbookId");
                    if (workbookId != null) {
                        workbookCounts.put(workbookId, workbookCounts.getOrDefault(workbookId, 0) + 1);
                    }
                }
            }
            stats.put("entriesByWorkbook", workbookCounts);

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

            if (Files.exists(indexPath)) {
                fileInfo.put("size", Files.size(indexPath));
                fileInfo.put("sizeFormatted", formatFileSize(Files.size(indexPath)));
                fileInfo.put("lastModified", Files.getLastModifiedTime(indexPath).toString());
                fileInfo.put("readable", Files.isReadable(indexPath));
                fileInfo.put("writable", Files.isWritable(indexPath));

                // Check if it's valid JSON
                try {
                    String content = Files.readString(indexPath, StandardCharsets.UTF_8);
                    fileInfo.put("isEmpty", content.trim().isEmpty());

                    if (!content.trim().isEmpty()) {
                        ObjectMapper mapper = new ObjectMapper();
                        Map<String, Object> parsed = mapper.readValue(content, Map.class);
                        fileInfo.put("validJson", true);
                        fileInfo.put("entryCount", parsed.size());
                    } else {
                        fileInfo.put("validJson", true);
                        fileInfo.put("entryCount", 0);
                    }
                } catch (Exception e) {
                    fileInfo.put("validJson", false);
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
            Path indexPath = Paths.get(".VCS", "index");

            if (!Files.exists(indexPath)) {
                return ResponseEntity.ok(Map.of(
                        "status", "not_found",
                        "message", "Index file does not exist",
                        "workbookId", workbookId
                ));
            }

            String content = Files.readString(indexPath, StandardCharsets.UTF_8);

            if (content.trim().isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "status", "empty",
                        "message", "Index file is empty",
                        "workbookId", workbookId,
                        "entries", new HashMap<>()
                ));
            }

            ObjectMapper mapper = new ObjectMapper();
            Map<String, Object> indexData = mapper.readValue(content, Map.class);

            // Filter entries for the specific workbook
            Map<String, Object> workbookEntries = new HashMap<>();
            for (Map.Entry<String, Object> entry : indexData.entrySet()) {
                if (entry.getValue() instanceof Map) {
                    Map<String, Object> entryData = (Map<String, Object>) entry.getValue();
                    if (workbookId.equals(entryData.get("workbookId"))) {
                        workbookEntries.put(entry.getKey(), entryData);
                    }
                }
            }

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("workbookId", workbookId);
            result.put("entryCount", workbookEntries.size());
            result.put("entries", workbookEntries);

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