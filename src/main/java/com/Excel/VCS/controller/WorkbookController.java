package com.Excel.VCS.controller;

import com.Excel.VCS.model.Workbook;
import com.Excel.VCS.service.WorkbookService;
import lombok.Data;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/workbooks")
public class WorkbookController {

    private final WorkbookService workbookService;

    public WorkbookController(WorkbookService workbookService) {
        this.workbookService = workbookService;
    }

    @PostMapping("/{workbookId}/sheets/{sheetNumber}/cell")
    public ResponseEntity<String> updateCell(
            @PathVariable String workbookId,
            @PathVariable int sheetNumber,
            @RequestBody CellUpdateRequest request
    ) {
        workbookService.updateCell(workbookId, sheetNumber, request.getRow(), request.getCol(), request.getValue());
        return ResponseEntity.ok("Cell updated successfull");
    }

    @PostMapping("/save")
    public ResponseEntity<Workbook> saveWorkbook(@RequestBody Workbook workbook)
    {
        Workbook saved = workbookService.saveWorkbook(workbook);
        return ResponseEntity.ok(saved);
    }

    @Data
    public static class CellUpdateRequest

    {
        private String row;   // e.g., "A"
        private int col;      // e.g., 2
        private String value; // e.g., "Hi"
    }
}
