package com.Excel.VCS.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Sheet {

    private int number; // Sheet 1, Sheet 2

    // Key: "A:1", "B:2" etc.
    private Map<String, Cell> cells = new HashMap<>();

    public Cell getCell(String row, int col) {
        String key = row.toUpperCase() + ":" + col;
        return cells.computeIfAbsent(key, k -> new Cell(row.toUpperCase(), col, ""));
    }

    public void setCell(Cell cell) {
        String key = cell.getRow().toUpperCase() + ":" + cell.getCol();
        cells.put(key, cell);
    }
}
