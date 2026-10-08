package com.bharath.internalassist.ingestion;

import com.bharath.internalassist.config.VectorStorePersistence;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.*;
import java.util.stream.Stream;

@Service
public class IngestionService {

    public record Result(String file, String status, int chunks){}
    private final VectorStore vectorStore;
    private final VectorStorePersistence persistence;
    private final IngestionManifest manifest;
    private final Path docsDir;

    private static final Set<String> SUPPORTED = Set.of(".pdf", ".docx", ".doc", ".txt");

    public IngestionService(VectorStore vectorStore,
                            VectorStorePersistence persistence,
                            IngestionManifest manifest,
                            @Value("${app.ingestion.docs-dir}") String docsDir){
        this.vectorStore = vectorStore;
        this.persistence = persistence;
        this.manifest = manifest;
        this.docsDir = Path.of(docsDir).toAbsolutePath().normalize();
    }

    public synchronized Result ingest(String fileName){
        Path file = resolve(fileName);
        String hash = sha256(file);

        var existing = manifest.find(fileName);
        if(existing.isPresent() && existing.get().hash().equals(hash)){
            return new Result(fileName, "unchanged", existing.get().chunkIds().size());
        }

        List<Document> chunks = readAndSplit(file, fileName);
        if(chunks.isEmpty()){
            return new Result(fileName, "No-text-extracted", 0);
        }

        List <String> newIds  = chunks.stream().map(Document :: getId).toList();

        try{
            vectorStore.add(chunks);
        }
        catch (RuntimeException e){
            vectorStore.delete(newIds);
            throw e;
        }


        existing.ifPresent(old -> vectorStore.delete(old.chunkIds()));
        persistence.save();

        manifest.put(fileName, new IngestionManifest.Entry(hash, newIds));
        return new Result(fileName, existing.isPresent() ? "updated": "added", newIds.size());
    }

    public synchronized List<Result> ingestAll(){
        List<Result> results = new ArrayList<>();
        try(Stream<Path> files = Files.list(docsDir)) {
            files.filter(Files :: isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(IngestionService :: isSupported)
                    .forEach(name -> {
                        try{
                            results.add(ingest(name));
                        } catch (RuntimeException e){
                            results.add(new Result(name , "failed: "+ e.getMessage(),0));
                        }
                    });
        }catch (IOException e){
            throw new IllegalStateException(e);
        }
        return results;
    }

    public synchronized Result remove(String fileName){
        Path file = resolve(fileName);
        var existing = manifest.find(fileName);
        if(existing.isEmpty()){
            return new Result(fileName, "not-found", 0);
        }
        vectorStore.delete(existing.get().chunkIds());
        persistence.save();
        manifest.remove(fileName);
        return new Result(fileName, "removed", existing.get().chunkIds().size());
    }

    public synchronized List<Result> list(){
        return manifest.all().entrySet().stream()
                .map(e -> new Result(e.getKey(), "ingested", e.getValue().chunkIds().size()))
                .toList();
    }

    private List<Document> readAndSplit(Path file, String fileName){
        List<Document> raw = new TikaDocumentReader(new FileSystemResource(file)).get().stream()
                .map(d -> {
                    Map<String, Object> metadata = new HashMap<>(d.getMetadata());
                    metadata.put("source", fileName);
                    return new Document(d.getId(), metadata);
                })
                .toList();
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        return splitter.apply(raw);
    }

    private final String sha256(Path file){
        try{
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Could not fingerprint "+ file, e);
        }
    }

    public Path resolve(String fileName){
        Path file = docsDir.resolve(fileName).normalize();
        if(!file.startsWith(docsDir)|| !isSupported(fileName)){
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only .pdf, .docx, .doc and .txt files inside data/docs are allowed.");
        }

        if(!Files.isRegularFile(file)){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No such file: "+ fileName);
        }
        return file;
    }

    public static boolean isSupported(String fileName){
        String lower = fileName.toLowerCase();
        return !lower.startsWith("~$") && SUPPORTED.stream().anyMatch(lower::endsWith);
    }
}
