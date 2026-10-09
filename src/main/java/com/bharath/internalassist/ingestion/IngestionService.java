package com.bharath.internalassist.ingestion;

import com.bharath.internalassist.tabular.ExcelParser;
import com.bharath.internalassist.tabular.TableData;
import com.bharath.internalassist.tabular.TableRegistry;
import com.bharath.internalassist.tabular.TableTextConverter;
import com.bharath.internalassist.tenant.TenantPaths;
import com.bharath.internalassist.tenant.TenantRegistry;
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
//    private final Path docsDir;
    private final ExcelParser excelParser;
    private final TableRegistry tableRegistry;
    private final int rowsPerChunk;
    private final WebPageFetcher webPageFetcher;
//    private final List<String> webPages;
    private final TenantPaths paths;
    private final TenantRegistry tenants;

    private static final Set<String> SUPPORTED = Set.of(".pdf", ".docx", ".doc", ".txt", ".xlsx");

    public IngestionService(VectorStore vectorStore,
                            PlatformTransactionManager transactionManager,
                            IngestionLedger ledger,
//                            @Value("${app.ingestion.docs-dir}") String docsDir,
                            ExcelParser excelParser,
                            TableRegistry tableRegistry,
                            @Value("${app.excel.rows-per-chunk:1}") int rowsPerChunk,
//                            @Value("${app.web.pages}") List<String> webPages,
                            WebPageFetcher webPageFetcher,
                            TenantRegistry tenants,
                            TenantPaths paths

    ){
        this.vectorStore = vectorStore;
        this.ledger = ledger;
        this.transactions = new TransactionTemplate(transactionManager);
        this.excelParser = excelParser;
        this.tableRegistry = tableRegistry;
        this.rowsPerChunk = rowsPerChunk;
        this.webPageFetcher = webPageFetcher;
        this.paths = paths;
        this.tenants = tenants;
    }

    public synchronized Result ingest(String tenantId, String fileName){
        Path file = resolve(tenantId, fileName);
        String hash = sha256(file);

        var existing = ledger.find(tenantId,fileName);
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
        return store(tenantId, fileName, hash, chunks, tables, existing.isPresent());
    }

    public synchronized Result ingestUrl(String tenantId, String url){
        var tenant = tenants.require(tenantId);
        WebPageFetcher.Page page = webPageFetcher.fetch(url, tenant.allowedHosts());

        String hash = sha256Text(page.text());

        var existing = ledger.find(tenantId,url);
        if(existing.isPresent() && existing.get().hash().equals(hash))
            return new Result(url, "unchanged", existing.get().chunkCount());

        Map<String, Object>  metadata = new HashMap<>();
        metadata.put("source", url);
        metadata.put("type", "web");
        metadata.put("title", page.title());
        List<Document> chunks = TokenTextSplitter.builder().build().apply(List.of(new Document(page.text(), metadata)));

        return store(tenantId,url, hash, chunks, null, existing.isPresent());

    }

    private Result store(String tenantId, String name, String hash, List<Document> chunks, List<TableData> tables, boolean existed){
        if(chunks.isEmpty())
            return new Result(name, "no-text-extracted",0);

        List<Document> stamped = chunks.stream().map(chunk ->{
            Map<String, Object> metaData = new HashMap<>(chunk.getMetadata());
            metaData.put("tenant", tenantId);
            return new Document(chunk.getId(), chunk.getText(), metaData);
        }).toList();
        int count = stamped.size();

        transactions.executeWithoutResult(status ->{
            vectorStore.delete(sourceIs(tenantId,name));
            vectorStore.add(stamped);
            ledger.upsert(tenantId,name, hash, count);
        });

        if(tables != null){
            tableRegistry.replace(tenantId, name, tables);
        }

        return new Result(name, existed? "updated": "added", count);
    }

    public synchronized List<Result> ingestAll(String tenantId){
        List<Result> results = new ArrayList<>();
        Path dir = paths.docsDir(tenantId);
        if(Files.isDirectory(dir)){
            try(Stream<Path> files = Files.list(dir)) {
                files.filter(Files :: isRegularFile)
                        .map(p -> p.getFileName().toString())
                        .filter(IngestionService :: isSupported)
                        .forEach(name -> {
                            try{
                                results.add(ingest(tenantId,name));
                            } catch (RuntimeException e){
                                results.add(new Result(name , "failed: "+ e.getMessage(),0));
                            }
                        });
            }catch (IOException e){
                throw new IllegalStateException(e);
            }
        }else{
            results.add(new Result(dir.toString(), "failed: tenant folder not found", 0));
        }
        for(String page: tenants.require(tenantId).webPages()){
            String url = page.trim();
            if(url.isEmpty()) continue;
            try{
                results.add(ingestUrl(tenantId,url));
            }catch (RuntimeException e){
                results.add(new Result(url, "failed: "+ e.getMessage(),0));
            }
        }
        return results;
    }

    public synchronized Result remove(String tenantId,String fileName){
        var entry = ledger.find(tenantId,fileName);

        if(entry.isEmpty()){
            return new Result(fileName, "not-found", 0);
        }
        transactions.executeWithoutResult(status ->{
            vectorStore.delete(sourceIs(tenantId, fileName));
            ledger.remove(tenantId,fileName);
        });
        tableRegistry.remove(tenantId,fileName);
        return new Result(fileName, "removed", entry.get().chunkCount());
    }

    public synchronized List<Result> list(String tenantId){
        return ledger.all(tenantId).stream()
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

    public Path resolve(String tenantId, String fileName){
        Path dir = paths.docsDir(tenantId);
        Path file = dir.resolve(fileName).normalize();
        if(!file.startsWith(dir)|| !isSupported(fileName)){
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

    private static Filter.Expression sourceIs(String tenantId,String fileName){
        FilterExpressionBuilder filterExpressionBuilder = new FilterExpressionBuilder();
        return filterExpressionBuilder.and(filterExpressionBuilder.eq("tenant", tenantId), filterExpressionBuilder.eq("source", fileName)).build();
    }
}
