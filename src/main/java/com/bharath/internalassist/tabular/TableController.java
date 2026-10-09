package com.bharath.internalassist.tabular;

import com.bharath.internalassist.tenant.ApiKeyFilter;
import com.bharath.internalassist.tenant.Caller;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tables")
@RequiredArgsConstructor
public class TableController {

    private final TableRegistry registry;

    @GetMapping
    public List<TableRegistry.Summary> list( @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller){
        return registry.list(caller.tenantId());
    }

    @GetMapping("/rows")
    public List<Map<String, String>> rows(
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller,
            @RequestParam String table,
            @RequestParam(required = false) String column,
            @RequestParam(required = false) String value,
            @RequestParam(defaultValue = "20") int limit
    ){
        TableData data = registry.find(caller.tenantId(), table).orElseThrow(()-> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "Unknown table "+ table + " . Call GET /api/tables first."
        ));
        try {
            return data.where(column, value, Math.max(1, Math.min(limit,250)));
        }catch (IllegalArgumentException e){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

}
