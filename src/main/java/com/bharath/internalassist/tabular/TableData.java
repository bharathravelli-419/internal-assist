package com.bharath.internalassist.tabular;

import java.util.List;
import java.util.Map;

public record  TableData(String id, String fileName, String sheetName, List<String> columns, List<Map<String, String>> rows){

    //Exact match (ignore upper/lower case) With no column given, returns the first rows
    public List<Map<String, String>> where(String column, String value, int limit){
        if(column == null || column.isBlank()){
            return rows.stream().limit(limit).toList();
        }

        String actual = columns.stream()
                .filter(c-> c.equalsIgnoreCase(column.trim()))
                .findFirst()
                .orElseThrow(()-> new IllegalArgumentException(
                        "No column "+ column + " . Available columns: "+ columns
                ));
        String wanted = value == null ? "" : value.trim();
        return rows.stream()
                .filter(r-> r.get(actual).trim().equalsIgnoreCase(wanted))
                .limit(limit)
                .toList();
    }

    //Partial match
    public List<Map<String, String>> containing(String column, String value, int limit){
        String actual = resolveColumn(column);
        String wanted = clean(value).toLowerCase();

        if(wanted.isEmpty())
            return List.of();
        return rows.stream()
                .filter(r-> r.get(actual).toLowerCase().contains(wanted))
                .limit(limit)
                .toList();
    }

    private String resolveColumn(String column){
        return columns.stream()
                .filter(c-> c.equalsIgnoreCase(column.trim()))
                .findFirst()
                .orElseThrow(()->new IllegalArgumentException(
                        "No column "+ column + " .Available columns: "+ columns
                ));
    }

    private static String clean(String value){
        return value==null ? "": value.trim();
    }

}
