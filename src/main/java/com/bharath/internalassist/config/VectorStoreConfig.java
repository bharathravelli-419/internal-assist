package com.bharath.internalassist.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.File;

//@Configuration
public class VectorStoreConfig {

//    @Bean
    VectorStore vectorStore(EmbeddingModel embeddingModel, @Value("${app.vector-store.file}") String filePath){
        SimpleVectorStore store = SimpleVectorStore.builder(embeddingModel).build();

        File file = new File(filePath);
        if(file.exists()){
            store.load(file);
        }
        return store;
    }
}
