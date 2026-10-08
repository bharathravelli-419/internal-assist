package com.bharath.internalassist.tabular;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class TableController {

    private final TableRegistry registry;

    @GetMapping
    public List<TableRegistry.Summary> list(){
        return registry.list();
    }

    @GetMapping("/rows")
    public List<Map<String, String>> rows(
            @RequestParam String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) String value,
            @RequestParam(defaultValue = "20") int limit
    ){
        TableData data = registry.find(table).orElseThrow(()-> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Unknown table "+ table + " . Call GET /api/tables first."
        ));
        try {
            return data.where(column, value, Math.max(1, Math.min(limit,200)));
        }catch (IllegalArgumentException e){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

}
