package com.bharath.internalassist.ingestion;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

//@Component
public class IngestionManifest {

    public record Entry(String hash, List<String> chunkIds){}

    private final ObjectMapper objectMapper;
    private final Path file;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public IngestionManifest(ObjectMapper objectMapper, @Value("${app.ingestion.manifest-file}") String filePath){
        this.objectMapper = objectMapper;
        this.file = Path.of(filePath);
        load();
    }

    public Optional<Entry> find(String fileName){
        return Optional.ofNullable(entries.get(fileName));
    }
    public Map<String, Entry> all(){
        return Map.copyOf(entries);
    }
    public void put(String fileName, Entry entry){
        entries.put(fileName, entry);
        save();
    }

    public void remove(String fileName){
        entries.remove(fileName);
        save();
    }

    public void load(){
        if(!Files.exists(file)){
            return;
        }
        try{
            entries.putAll(objectMapper.readValue(file.toFile(), new TypeReference<Map<String,Entry>>() {
            }));
        }
        catch (RuntimeException e){
            throw new IllegalStateException("Could not read "+ file, e);
        }
    }

    private void save(){
        try{
            Path parent = file.toAbsolutePath().getParent();
            if(parent != null){
                Files.createDirectories(parent);
            }
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), entries);
        }
        catch (IOException | RuntimeException e){
            throw new IllegalStateException("Could not write "+ file, e);
        }
    }
}
