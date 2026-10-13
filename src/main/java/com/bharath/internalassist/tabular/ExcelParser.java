package com.bharath.internalassist.tabular;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.*;

@Slf4j
@Component
public class ExcelParser {

    private final int maxRows;

    private static final int MAX_SCAN_ROWS = 20_000;
    private static final int MAX_COLUMNS = 200;

    private record Block(int firstRow, int lastRow,int firstCol, int lastCol){}
    private record RawTable(String title, List<String> columns, List<Map<String, String>> rows){}

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

                for(TableData table: readSheet(workbook.getSheetAt(i),fileName, formatter,evaluator)){
                    totalRows +=table.rows().size();
                    if(totalRows > maxRows){
                        throw new IllegalArgumentException(fileName + " has more than "+ maxRows +". Raise the max-rows if that is intended.");
                    }
                    tables.add(table);
                }
            }
        }catch (IOException e){
            throw new UncheckedIOException("Could not read "+ fileName, e);
        }
        return tables;
    }

    private List<TableData> readSheet(Sheet sheet, String fileName, DataFormatter formatter, FormulaEvaluator evaluator){
        String[][] grid = readGrid(sheet, formatter,evaluator);
        if(grid.length == 0){
            return List.of();
        }
        List<RawTable> found = new ArrayList<>();
        String pendingTitle = null;
        int pendingTitleRow = -10;

        for(Block block: findBlocks(grid)){
            if(isSingleCell(grid, block)){
                pendingTitle = firstFilled(grid[block.firstRow()],block);
                pendingTitleRow = block.firstRow();
                continue;
            }
            String title = (pendingTitle != null && (block.firstRow() - pendingTitleRow <= 2))? pendingTitle : null;
            pendingTitle = null;

            RawTable table = toTable(grid, block, title, sheet.getSheetName());
            if(table != null){
                found.add(table);
            }
        }
        return nameTables(found, fileName, sheet.getSheetName());
    }

    private String[][] readGrid(Sheet sheet, DataFormatter formatter, FormulaEvaluator evaluator){
        if(sheet.getPhysicalNumberOfRows() == 0){
            return new String[0][0];
        }
        int lastRow = Math.min(sheet.getLastRowNum(), MAX_SCAN_ROWS-1);
        int width =0;
        for(int r=0; r<= lastRow;r++){
            Row row = sheet.getRow(r);
            if(row != null && row.getLastCellNum() > width){
                width = row.getLastCellNum();
            }
        }
        width = Math.min(width, MAX_COLUMNS);
        if(width == 0){
            return new String[0][0];
        }
        String[][] grid = new String[lastRow +1][width];
        for(int r =0;r<=lastRow; r++){
            Arrays.fill(grid[r], "");
            Row row = sheet.getRow(r);
            if(row == null){
                continue;
            }
            for(int c =0; c< width;c++){
                grid[r][c] = text(row.getCell(c), formatter, evaluator);
            }
        }
        return grid;
    }

    private List<Block> findBlocks(String[][] grid){
        List<Block> bloks = new ArrayList<>();
        int rows = grid.length;
        int width = grid[0].length;

        int r =0;
        while(r < rows){
            if(rowIsBlank(grid[r],0, width-1)){
                r++;
                continue;
            }

            int bandStart = r;
            while(r <rows && !rowIsBlank(grid[r],0, width-1)){
                r++;
            }
            int bandEnd = r-1;

            int c =0;
            while(c<width){
                if(columnIsBlank(grid, bandStart, bandEnd,c)){
                    c++;
                    continue;
                }
                int colStart =c;
                while(c <width && !columnIsBlank(grid, bandStart, bandEnd, c)){
                    c++;
                }
                int colEnd = c-1;
                int top = bandStart;
                while (top <= bandEnd && rowIsBlank(grid[top], colStart, colEnd)){
                    top++;
                }
                int bottom = bandEnd;
                while(bottom>=top && rowIsBlank(grid[bottom], colStart, colEnd)){
                    bottom--;
                }
                if(top <= bottom){
                    bloks.add(new Block(top, bottom, colStart, colEnd));
                }
            }
        }
        return bloks;
    }
    private RawTable toTable(String[][] grid, Block block, String pendingTitle, String sheetName) {
        String title = pendingTitle;
        int headerRow = block.firstRow();
        int height = block.lastRow() - block.firstRow() + 1;

        // A title inside the block: a first row with ONE filled cell, followed by a row with several.
        if (height >= 3
                && countFilled(grid[block.firstRow()], block) == 1
                && countFilled(grid[block.firstRow() + 1], block) >= 2) {
            title = firstFilled(grid[block.firstRow()], block);
            headerRow = block.firstRow() + 1;
        }

        List<String> columns = headerFrom(grid[headerRow], block);
        if (columns == null) {
            // Typical cause: a blank row inside a table cut it in two, so this block starts with data.
            log.warn("Skipped a block on sheet '{}' near row {}: its first row looks like data, not a sheet", sheetName, headerRow + 1);
            return null;
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = headerRow + 1; r <= block.lastRow(); r++) {
            Map<String, String> values = new LinkedHashMap<>();
            boolean hasData = false;
            for (int i = 0; i < columns.size(); i++) {
                String value = grid[r][block.firstCol() + i];
                if (!value.isEmpty()) {
                    hasData = true;
                }
                values.put(columns.get(i), value);
            }
            if (hasData) {
                rows.add(values);
            }
        }
        if (rows.isEmpty()) {
            return null;
        }
        return new RawTable(title, columns, rows);
    }
    private List<String> headerFrom(String[] row, Block block) {
        int filled = 0;
        int numeric = 0;
        for (int c = block.firstCol(); c <= block.lastCol(); c++) {
            if (!row[c].isEmpty()) {
                filled++;
                if (looksNumeric(row[c])) {
                    numeric++;
                }
            }
        }
        if (filled == 0 || numeric * 2 > filled) {
            return null;
        }

        List<String> names = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int c = block.firstCol(); c <= block.lastCol(); c++) {
            String header = row[c].isEmpty() ? "Column " + (c + 1) : row[c];
            String unique = header;
            int n = 2;
            while (!seen.add(unique.toLowerCase())) { // two columns named "Name" would ov
                unique = header + " (" + n++ + ")";
            }
            names.add(unique);
        }
        return names;
    }

    private List<TableData> nameTables(List<RawTable> raw, String fileName, String sheetName) {
        List<TableData> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        String base = baseName(fileName) + "." + sheetName;

        int position = 0;
        for (RawTable table : raw) {
            position++;
            String id;
            if (raw.size() == 1) {
                id = base; // one table on the sheet: same id as before
            } else {
                String label = sanitize(table.title());
                if (label.isEmpty()) {
                    label = "Table" + position;
                }
                String candidate = base + "." + label;
                id = candidate;
                int n = 2;
                while (!used.add(id.toLowerCase())) {
                    id = candidate + "_" + n++;
                }
            }
            result.add(new TableData(id, fileName, sheetName,
                    List.copyOf(table.columns()), List.copyOf(table.rows())));
        }
        return result;
    }


    private static String sanitize(String title) {
        if (title == null) {
            return "";
        }
        String clean = title.trim().replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return clean.length() > 40 ? clean.substring(0, 40) : clean;
    }

    private static String baseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }


    private static boolean rowIsBlank(String[] row, int from, int to) {
        for (int c = from; c <= to; c++) {
            if (!row[c].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean columnIsBlank(String[][] grid, int fromRow, int toRow, int col) {
        for (int r = fromRow; r <= toRow; r++) {
            if (!grid[r][col].isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static int countFilled(String[] row, Block block) {
        int count = 0;
        for (int c = block.firstCol(); c <= block.lastCol(); c++) {
            if (!row[c].isEmpty()) {
                count++;
            }
        }
        return count;
    }

    private static String firstFilled(String[] row, Block block) {
        for (int c = block.firstCol(); c <= block.lastCol(); c++) {
            if (!row[c].isEmpty()) {
                return row[c];
            }
        }
        return "";
    }

    private static boolean isSingleCell(String[][] grid, Block block) {
        return block.firstRow() == block.lastRow() && countFilled(grid[block.firstRow()], block) == 1;
    }

    private static boolean looksNumeric(String value) {
        return value.matches("[-+]?[0-9][0-9,.%]*");
    }

    private static String text(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell == null) {
            return "";
        }
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (RuntimeException e) {
            return "#ERROR";
        }
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

    private static String tableId(String fileName, String sheetName){
        int dot = fileName.lastIndexOf('.');
        String base = dot>0 ? fileName.substring(0, dot): fileName;
        return base+ "."+ sheetName;
    }
}
