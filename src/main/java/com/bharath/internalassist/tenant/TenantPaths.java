package com.bharath.internalassist.tenant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
public class TenantPaths {
    private final Path baseDir;

    public TenantPaths(@Value("${app.ingestion.base-dir:data/tenants}") String baseDir){
        this.baseDir = Path.of(baseDir).toAbsolutePath().normalize();
    }

    public Path docsDir(String tenantId){
        return baseDir.resolve(tenantId).resolve("docs").normalize();
    }
}
