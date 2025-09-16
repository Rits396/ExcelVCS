package com.Excel.VCS.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "workbooks")
public class Workbook {

    @Id
    private String id;

    private String name; // Workbook name

    private List<Sheet> sheets = new ArrayList<>();

    public Sheet getSheetByNumber(int number) {
        return sheets.stream()
                .filter(s -> s.getNumber() == number)
                .findFirst()
                .orElse(null);
    }

    public void addSheet(Sheet sheet) {
        sheets.add(sheet);
    }
}

