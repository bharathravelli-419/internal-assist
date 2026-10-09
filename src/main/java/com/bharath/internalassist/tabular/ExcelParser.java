package com.bharath.internalassist.tabular;

import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

@Component
public class ExcelParser {

    private final int maxRows;

    public ExcelParser(@Value("${app.excel.max-rows:5000}") int maxRows){
        this.maxRows = maxRows;
    }

    public static boolean isExcel(String fileName){
        String lower = fileName.toLowerCase();
        return lower.endsWith(".xlsx") && !lower.startsWith("~$");
    }

    public List<TableData> parse(Path file, String fileName){
        List<TableData> tables = new ArrayList<>();
        int totalRows = 0;
        DataFormatter formatter = new DataFormatter();

        try(Workbook workbook = WorkbookFactory.create(file.toFile(), null, true)){
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            for(int i=0; i<workbook.getNumberOfSheets(); i++){
                if(workbook.getSheetVisibility(i) != SheetVisibility.VISIBLE) continue;

                TableData table = readSheet(workbook.getSheetAt(i), fileName, formatter, evaluator);
                if(table == null) continue;

                totalRows += table.rows().size();
                if(totalRows > maxRows){
                    throw new IllegalArgumentException(fileName+ " has more than "+ maxRows
                    + " data rows. Raise app.excel.max-rows if that is intended.");
                }
                tables.add(table);
            }
        }catch (IOException e){
            throw new UncheckedIOException("Could not read "+ fileName, e);
        }
        return tables;
    }

    private TableData readSheet(Sheet sheet, String fileName, DataFormatter formatter, FormulaEvaluator evaluator){
        List<String> columns = null;
        List<Map<String, String>> rows  = new ArrayList<>();

        for(Row row: sheet){
            if(columns == null){
                columns = readHeader(row, formatter, evaluator);
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            boolean hasData = false;
            for(int c =0; c<columns.size(); c++){
                String value = text(row.getCell(c), formatter, evaluator);
                if(!value.isEmpty()){
                    hasData = true;
                }
                values.put(columns.get(c), value);

            }
            if(hasData)
                rows.add(values); //skip the blank rows

        }
        if(columns == null || rows.isEmpty()){
            return null;
        }
        return new TableData(tableId(fileName,sheet.getSheetName()), fileName,sheet.getSheetName(), List.copyOf(columns), List.copyOf(rows));

    }
    // Returns null for a row without text, so the caller keeps looking for the header
    private List<String> readHeader(Row row, DataFormatter formatter, FormulaEvaluator formulaEvaluator){
         short last = row.getLastCellNum();
         if(last < 1){
             return null;
         }
         List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean any = false;
        for(int c=0; c<last; c++){
            String header = text(row.getCell(c), formatter, formulaEvaluator);
            if(!header.isEmpty()){
                any = true;
            }else{
                header = "Column "+(c+1);
            }
            String unique = header;
            int n= 2;
            while (!seen.add(unique.toLowerCase())){
                unique = header+ " ("+ n++ +")";
            }
            names.add(unique);
        }
        return any ? names : null;
    }

    private static  String text(Cell cell,DataFormatter formatter, FormulaEvaluator evaluator){
        if(cell == null){
            return "";
        }
        try{
            return formatter.formatCellValue(cell, evaluator).trim();
        }catch (RuntimeException e){
            return "#ERROR";
        }
    }

    private static String tableId(String fileName, String sheetName){
        int dot = fileName.lastIndexOf('.');
        String base = dot>0 ? fileName.substring(0, dot): fileName;
        return base+ "."+ sheetName;
    }
}
