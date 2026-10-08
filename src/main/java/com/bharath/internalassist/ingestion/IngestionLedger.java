package com.bharath.internalassist.ingestion;

import org.springframework.dao.support.DataAccessUtils;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

@Component
public class IngestionLedger {

    public record Entry(String fileName, String hash, int chunkCount){}

    private final JdbcTemplate jdbcTemplate;

    public IngestionLedger(JdbcTemplate jdbcTemplate){
        this.jdbcTemplate = jdbcTemplate;
        // FIXED: Added the missing closing parenthesis ')' at the end of the query
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS ingested_file (
                    file_name TEXT PRIMARY KEY,
                    hash TEXT NOT NULL,
                    chunk_count INTEGER NOT NULL,
                    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    public Optional<Entry> find(String fileName){
        // OPTIMIZED: Replaced stream().findFirst() with DataAccessUtils to cleanly fetch a single nullable result
        List<Entry> results = jdbcTemplate.query(
                "SELECT file_name, hash, chunk_count FROM ingested_file WHERE file_name = ?",
                (rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getInt(3)),
                fileName
        );
        return Optional.ofNullable(DataAccessUtils.singleResult(results));
    }

    public List<Entry> all(){
        return jdbcTemplate.query(
                "SELECT file_name, hash, chunk_count FROM ingested_file ORDER BY file_name",
                (rs, row) -> new Entry(rs.getString(1), rs.getString(2), rs.getInt(3))
        );
    }

    public void upsert(String fileName, String hash, int chunkCount){
        jdbcTemplate.update("""
                INSERT INTO ingested_file (file_name, hash, chunk_count, updated_at)
                VALUES(?, ?, ?, now())
                ON CONFLICT (file_name) DO UPDATE
                SET hash = EXCLUDED.hash, chunk_count = EXCLUDED.chunk_count, updated_at = now()
                """, fileName, hash, chunkCount);
    }

    public void remove(String fileName){
        jdbcTemplate.update("DELETE FROM ingested_file WHERE file_name = ?", fileName);
    }
}
