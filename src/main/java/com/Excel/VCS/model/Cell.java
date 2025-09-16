package com.Excel.VCS.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class Cell {
    private String row;   // "A", "B", etc.
    private int col;      // 1, 2, 3...
    private String value; // cell value or formula
}
