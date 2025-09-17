package com.Excel.VCS.service;


import com.Excel.VCS.model.Cell;
import com.Excel.VCS.model.Sheet;
import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.repository.WorkbookRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkbookService {
    private static final Logger logger = LoggerFactory.getLogger(AddService.class);

    private final WorkbookRepository workbookRepository;

    public WorkbookService(WorkbookRepository workbookRepository) {
        this.workbookRepository = workbookRepository;
    }

    public Workbook saveWorkbook(Workbook workbook) {
        return workbookRepository.save(workbook);
    }

    public void updateCell(String workbookId, int sheetNumber, String row, int col, String value) {
        Workbook workbook = workbookRepository.findById(workbookId)
                .orElseThrow(() -> new RuntimeException("Workbook not found"));

        // Get or create sheet
        Sheet sheet = workbook.getSheetByNumber(sheetNumber);
        if (sheet == null) {
            sheet = new Sheet();
            sheet.setNumber(sheetNumber);
            workbook.addSheet(sheet);
        }

        // Update or create cell
        Cell cell = sheet.getCell(row, col);
        cell.setValue(value);
        sheet.setCell(cell);

        // Save workbook
        workbookRepository.save(workbook);
    }

    public String getCellValue(String workbookId, int sheetNumber, String row, int col) {
        logger.debug("Getting cell value - workbook: {}, sheet: {}, row: {}, col:{}",
                workbookId, sheetNumber, row, col);

        try {
            // Lookup workbook by ID
            Workbook workbook = workbookRepository.findById(workbookId)
                    .orElseThrow(() -> new RuntimeException("Workbook not found: " + workbookId));

            // Find sheet by number (not by array index)
            Sheet sheet = workbook.getSheetByNumber(sheetNumber);
            if (sheet == null) {
                logger.warn("Sheet with number {} not found", sheetNumber);
                return ""; // Return empty string instead of throwing exception
            }

            // Get cell value
            Cell cell = sheet.getCell(row, col);
            String value = cell.getValue();

            logger.debug("Retrieved cell value: '{}'", value);
            return value != null ? value : "";

        } catch (Exception e) {
            logger.error("Error getting cell value: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to get cell value: " + e.getMessage(), e);
        }
    }

}
