package com.bharath.internalassist.ingestion;

import com.bharath.internalassist.tenant.ApiKeyFilter;
import com.bharath.internalassist.tenant.Caller;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.List;

@RestController
@RequestMapping("/api/ingest")
public class IngestionController {

    private static final Path DOCS_DIR = Path.of("data", "docs").toAbsolutePath().normalize();

    @Autowired
    private IngestionService ingestionService;

    @PostMapping
    public IngestionService.Result ingest(
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller,
            @RequestParam String fileName) {
        return ingestionService.ingest(caller.tenantId(), fileName);
    }

    @PostMapping("/all")
    public List<IngestionService.Result> ingestAll( @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller) {
        return ingestionService.ingestAll(caller.tenantId());
    }

    @GetMapping
    public List<IngestionService.Result> list( @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller) {
        return ingestionService.list(caller.tenantId());
    }

    @DeleteMapping
    public IngestionService.Result remove(@RequestParam String fileName,@RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller ){
        return ingestionService.remove(caller.tenantId(), fileName);
    }

    @PostMapping("/url")
    public IngestionService.Result ingestUrl( @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller,@RequestParam String url){
        return ingestionService.ingestUrl(caller.tenantId(),url);
    }

}
