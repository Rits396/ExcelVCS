package com.Excel.VCS.controller;

import com.Excel.VCS.model.Cell;
import com.Excel.VCS.model.Sheet;
import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.repository.WorkbookRepository;
import com.Excel.VCS.service.AddService;
import com.Excel.VCS.service.WorkbookService;
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
            Path filePath = Paths.get(".git", "objects", dirName, fileName);

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
}