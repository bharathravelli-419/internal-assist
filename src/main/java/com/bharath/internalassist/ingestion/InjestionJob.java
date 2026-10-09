package com.bharath.internalassist.ingestion;

import com.bharath.internalassist.tenant.TenantRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

@Configuration
@EnableScheduling
@Slf4j
@RequiredArgsConstructor
public class InjestionJob {

    private final IngestionService ingestionService;
    private final TenantRegistry tenants;

    @Scheduled(cron = "${app.ingestion.cron}")
    public void refreshIngestion(){
       for(var tenant: tenants.all()){
           ingestionService.ingestAll(tenant.id()).forEach(result -> log.info("Scheduled Ingestion: [{}]: {}", tenant.id(), result));
       }
    }

}
