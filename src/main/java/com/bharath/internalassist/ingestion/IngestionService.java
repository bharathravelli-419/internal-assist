package com.bharath.internalassist.ingestion;

import com.bharath.internalassist.tabular.ExcelParser;
import com.bharath.internalassist.tabular.TableData;
import com.bharath.internalassist.tabular.TableRegistry;
import com.bharath.internalassist.tabular.TableTextConverter;
import com.bharath.internalassist.web.WebPageFetcher;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.stream.Stream;

@Service
public class IngestionService {

    public record Result(String file, String status, int chunks){}
    private final VectorStore vectorStore;
    private final IngestionLedger ledger;
    private final TransactionTemplate transactions;
    private final Path docsDir;
    private final ExcelParser excelParser;
    private final TableRegistry tableRegistry;
    private final int rowsPerChunk;
    private final WebPageFetcher webPageFetcher;
    private final List<String> webPages;

    private static final Set<String> SUPPORTED = Set.of(".pdf", ".docx", ".doc", ".txt", "xlsx");

    public IngestionService(VectorStore vectorStore,
                            PlatformTransactionManager transactionManager,
                            IngestionLedger ledger,
                            @Value("${app.ingestion.docs-dir}") String docsDir,
                            ExcelParser excelParser,
                            TableRegistry tableRegistry,
                            @Value("${app.excel.rows-per-chunk:1}") int rowsPerChunk,
                            @Value("${app.web.pages}") List<String> webPages,
                            WebPageFetcher webPageFetcher

    ){
        this.vectorStore = vectorStore;
        this.ledger = ledger;
        this.transactions = new TransactionTemplate(transactionManager);
        this.docsDir = Path.of(docsDir).toAbsolutePath().normalize();
        this.excelParser = excelParser;
        this.tableRegistry = tableRegistry;
        this.rowsPerChunk = rowsPerChunk;
        this.webPageFetcher = webPageFetcher;
        this.webPages = webPages;
    }

    public synchronized Result ingest(String fileName){
        Path file = resolve(fileName);
        String hash = sha256(file);

        var existing = ledger.find(fileName);
        if(existing.isPresent() && existing.get().hash().equals(hash)){
            return new Result(fileName, "unchanged", existing.get().chunkCount());
        }

        List<TableData> tables = null;
        List<Document> chunks;
        if(ExcelParser.isExcel(fileName)){
            tables = excelParser.parse(file, fileName);
            chunks = TableTextConverter.toDocuments(tables, rowsPerChunk);
        }else{
            chunks = readAndSplit(file, fileName);
        }

        if(chunks.isEmpty()){
            return new Result(fileName, "no-text-extracted", 0);
        }
        return store(fileName, hash, chunks, tables, existing.isPresent());
    }

    public synchronized Result ingestUrl(String url){
        WebPageFetcher.Page page = webPageFetcher.fetch(url);

        String hash = sha256Text(page.text());

        var existing = ledger.find(url);
        if(existing.isPresent() && existing.get().hash().equals(hash))
            return new Result(url, "unchanged", existing.get().chunkCount());

        Map<String, Object>  metadata = new HashMap<>();
        metadata.put("source", url);
        metadata.put("type", "web");
        metadata.put("title", page.title());
        List<Document> chunks = TokenTextSplitter.builder().build().apply(List.of(new Document(page.text(), metadata)));

        return store(url, hash, chunks, null, existing.isPresent());

    }

    private Result store(String name, String hash, List<Document> chunks, List<TableData> tables, boolean existed){
        if(chunks.isEmpty())
            return new Result(name, "no-text-extracted",0);
        int count = chunks.size();
        transactions.executeWithoutResult(status ->{
            vectorStore.delete(sourceIs(name));
            vectorStore.add(chunks);
            ledger.upsert(name, hash, count);
        });

        if(tables != null){
            tableRegistry.replace(name, tables);
        }

        return new Result(name, existed? "update": "added", count);
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
        for(String page: webPages){
            String url = page.trim();
            if(url.isEmpty()) continue;
            try{
                results.add(ingestUrl(url));
            }catch (RuntimeException e){
                results.add(new Result(url, "failed: "+ e.getMessage(),0));
            }
        }
        return results;
    }

    public synchronized Result remove(String fileName){
        var entry = ledger.find(fileName);

        if(entry.isEmpty()){
            return new Result(fileName, "not-found", 0);
        }
        transactions.executeWithoutResult(status ->{
            vectorStore.delete(sourceIs(fileName));
            ledger.remove(fileName);
        });
        tableRegistry.remove(fileName);
        return new Result(fileName, "removed", entry.get().chunkCount());
    }

    public synchronized List<Result> list(){
        return ledger.all().stream()
                .map(e -> new Result(e.fileName(), "ingested", e.chunkCount()))
                .toList();
    }

    private List<Document> readAndSplit(Path file, String fileName){
        List<Document> raw = new TikaDocumentReader(new FileSystemResource(file)).get().stream()
                .filter(d-> d.getText() != null && !d.getText().isBlank())
                .map(d -> {
                    Map<String, Object> metadata = new HashMap<>(d.getMetadata());
                    metadata.put("source", fileName);
                    return new Document(d.getText(), metadata);
                })
                .toList();
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        return splitter.apply(raw);
    }

    private String sha256(Path file){
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

    private static String sha256Text(String text){
        return hex(text.getBytes(StandardCharsets.UTF_8));
    }
    private static String hex(byte[] bytes){
        try{
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        }catch (NoSuchAlgorithmException e){
            throw new IllegalStateException(e);
        }
    }

    private static Filter.Expression sourceIs(String fileName){
        return new FilterExpressionBuilder().eq("source", fileName).build();
    }
}
