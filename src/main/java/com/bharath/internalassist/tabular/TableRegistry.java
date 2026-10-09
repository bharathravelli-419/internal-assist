package com.bharath.internalassist.tabular;
import com.bharath.internalassist.tenant.TenantPaths;
import com.bharath.internalassist.tenant.TenantRegistry;
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

    private final Map<String, Map<String,List<TableData>>> byTenant = new ConcurrentHashMap<>();
    private final ExcelParser parser;
    private final TenantRegistry tenants;
    private final TenantPaths paths;

    public TableRegistry(ExcelParser parser, TenantRegistry tenants, TenantPaths paths){
        this.parser = parser;
        this.tenants = tenants;
        this.paths = paths;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void loadAll(){
        for(var tenant: tenants.all()){
            Path dir = paths.docsDir(tenant.id());
            if(!Files.isDirectory(dir))return;
            try(Stream<Path> files = Files.list(dir)){
                files.filter(Files :: isRegularFile).forEach(path -> {
                    String name = path.getFileName().toString();
                    if(!ExcelParser.isExcel(name)){
                        return;
                    }
                    try{
                        replace(tenant.id(),name, parser.parse(path, name));
                        log.info("Loaded tables from {}", name);
                    }catch (RuntimeException e){
                        log.warn("Could not read {} for tenant {} : {}", name,tenant.id(), e.getMessage());
                    }
                });

            }catch (IOException e){
                log.warn("Could not scan {}: {}", dir, e.getMessage());
            }
        }

    }

    public void replace(String tenantId,String fileName, List<TableData> tables){
        byTenant.computeIfAbsent(tenantId, t-> new ConcurrentHashMap<>()).put(fileName, List.copyOf(tables));
    }

    public void remove(String tenantId, String fileName){
        Map<String, List<TableData>> files = byTenant.get(tenantId);
        if(files != null){
            files.remove(fileName);
        }
    }

    public List<Summary> list(String tenantId){
        return all(tenantId)
                .map(t-> new Summary(t.id(), t.fileName(), t.sheetName(), t.columns(), t.rows().size()))
                .sorted(Comparator.comparing(Summary::id))
                .toList();
    }

    public Optional<TableData> find(String tenantId,String id){
        return all(tenantId).filter(t-> t.id().equalsIgnoreCase(id.trim())).findFirst();
    }

    public Stream<TableData> all(String tenantId){
        Map<String, List<TableData>> files = byTenant.get(tenantId);
        return files == null ? Stream.empty() : files.values().stream().flatMap(List::stream);
    }

    public String dictionary(String tenantId){
        List<Summary> tables = list(tenantId);
        if(tables.isEmpty())
            return "No data tables are loaded.";
        return tables.stream()
                .map(t->"- "+ t.id() + " ("+ t.rows()+ " rows), columns: "+String.join(", "+ t.columns))
                .collect(Collectors.joining("\n"));
    }
}
