package com.bharath.internalassist.ingestion;

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

    @Scheduled(cron = "${app.ingestion.cron}")
    public void refreshIngestion(){
        ingestionService.ingestAll().forEach(result -> log.info("Scheduled Ingestion: {}", result));
    }

}
