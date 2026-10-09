package com.bharath.internalassist.ingestion;

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
    public IngestionService.Result ingest(@RequestParam String fileName) {
        return ingestionService.ingest(fileName);
    }

    @PostMapping("/all")
    public List<IngestionService.Result> ingestAll() {
        return ingestionService.ingestAll();
    }

    @GetMapping
    public List<IngestionService.Result> list() {
        return ingestionService.list();
    }

    @DeleteMapping
    public IngestionService.Result remove(@RequestParam String fileName){
        return ingestionService.remove(fileName);
    }

    @PostMapping("/url")
    public IngestionService.Result ingestUrl(@RequestParam String url){
        return ingestionService.ingestUrl(url);
    }

}
