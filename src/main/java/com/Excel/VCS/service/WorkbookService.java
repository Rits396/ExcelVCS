package com.Excel.VCS.service;


import com.Excel.VCS.model.Cell;
import com.Excel.VCS.model.Sheet;
import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.repository.WorkbookRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class WorkbookService {

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
}
