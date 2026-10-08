package com.bharath.internalassist.tabular;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.ai.document.Document;

public final class TableTextConverter {

    private TableTextConverter(){}

    public static List<Document> toDocuments(List<TableData> tables, int rowsPerChunk){
        int size = Math.max(1, rowsPerChunk);
        List<Document> documents = new ArrayList<>();

        for (TableData table: tables){
            for(int start =0; start<table.rows().size(); start += size){
                int end = Math.min(start+size, table.rows().size());

                StringBuilder text = new StringBuilder();
                for(int r = start; r<end;r++){
                    text.append("Table ").append(table.id())
                            .append(", row ").append(r+1).append(": ")
                            .append(rowText(table.rows().get(r)))
                            .append('\n');
                }

                Map<String, Object> metaData = new HashMap<>();
                metaData.put("source", table.fileName());
                metaData.put("table", table.id());
                metaData.put("sheet", table.sheetName());
                metaData.put("type", "table-row");
                metaData.put("rowFrom", start+1);
                metaData.put("rowTo", end);

                documents.add(new Document(text.toString().trim(), metaData));
            }
        }
        return documents;
    }

    private static String rowText(Map<String, String> row){
        return row.entrySet().stream()
                .filter(e -> !e.getValue().isBlank())
                .map(e -> e.getKey()+ " = "+ e.getValue())
                .collect(Collectors.joining("; "));
    }
}
