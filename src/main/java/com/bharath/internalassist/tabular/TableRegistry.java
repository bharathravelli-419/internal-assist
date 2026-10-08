package com.bharath.internalassist.tabular;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;


@Component
@Slf4j
public class TableRegistry {

    public record Summary(String id, String file, String sheet, List<String> columns, int rows){}

    private final Map<String, List<TableData>> byFile = new ConcurrentHashMap<>();
    private final ExcelParser parser;
    private final Path docsDir;

    public TableRegistry(ExcelParser parser, @Value("${app.ingestion.docs-dir}") String docsDir){
        this.parser = parser;
        this.docsDir = Path.of(docsDir).toAbsolutePath().normalize();
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAll(){
        if(!Files.isDirectory(docsDir))return;
        try(Stream<Path> files = Files.list(docsDir)){
            files.filter(Files :: isRegularFile).forEach(path -> {
                String name = path.getFileName().toString();
                if(!ExcelParser.isExcel(name)){
                    return;
                }
                try{
                    replace(name, parser.parse(path, name));
                    log.info("Loaded tables from {}", name);
                }catch (RuntimeException e){
                    log.warn("Could not read {}: {}", name, e.getMessage());
                }
            });

        }catch (IOException e){
            log.warn("Could not scan {}: {}", docsDir, e.getMessage());
        }

    }

    public void replace(String fileName, List<TableData> tables){
        byFile.put(fileName, List.copyOf(tables));
    }

    public void remove(String fileName){
        byFile.remove(fileName);
    }

    public List<Summary> list(){
        return all()
                .map(t-> new Summary(t.id(), t.fileName(), t.sheetName(), t.columns(), t.rows().size()))
                .sorted(Comparator.comparing(Summary::id))
                .toList();
    }

    public Optional<TableData> find(String id){
        return all().filter(t-> t.id().equalsIgnoreCase(id.trim())).findFirst();
    }
    public Stream<TableData> all(){
        return byFile.values().stream().flatMap(List:: stream);
    }

    public String dictionary(){
        List<Summary> tables = list();
        if(tables.isEmpty())
            return "No data tables are loaded.";
        return tables.stream()
                .map(t->"- "+ t.id() + " ("+ t.rows()+ " rows), columns: "+String.join(", "+ t.columns))
                .collect(Collectors.joining("\n"));
    }
}
