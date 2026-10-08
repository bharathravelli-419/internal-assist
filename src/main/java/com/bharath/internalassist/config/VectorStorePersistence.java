package com.bharath.internalassist.config;

import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;

//@Component
public class VectorStorePersistence {

    private final VectorStore vectorStore;
    private final File file;

    public VectorStorePersistence(VectorStore vectorStore, @Value("${app.vector-store.file}") String filePath){
        this.vectorStore = vectorStore;
        this.file = new File(filePath);

    }

    public void save(){
        if(vectorStore instanceof SimpleVectorStore simpleVectorStore){
            File parent = file.getAbsoluteFile().getParentFile();
            if(parent != null){
                parent.mkdirs();
            }
            simpleVectorStore.save(file);
        }
    }
}
