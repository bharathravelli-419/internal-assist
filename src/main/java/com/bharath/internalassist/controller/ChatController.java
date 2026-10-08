package com.bharath.internalassist.controller;


import com.bharath.internalassist.tabular.TableRegistry;
import com.bharath.internalassist.tools.TableTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String RULES= """
            You are an internal assistant for our team.
            If the user greets you are makes small talk, reply briefly and say what you can help with.
            
            You have two sources of information:
            1. Document excerpts, provided with the questions. Use them for policies, processes an descriptions.
            2. Data tables, queried with the tools lookupRows and listTables.
               Use lookupRows when the question names one specific item, such as a person, an event, a sprint or
               an ID. Choose the table and column from the list below.
            
            
            Rules:
            - Answer company questions only from the document excerpts or from tool results.
            - If neither contains the answer, say you could not find it. Do not guess.
            - When you used a table, say which table the answer came from.
            
            Available data tables:
            """;


    private final ChatClient chatClient;
    private final TableRegistry registry;

    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, TableTools tableTools, TableRegistry registry){

        this.registry = registry;
        this.chatClient = chatClientBuilder
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .defaultTools(tableTools)
                .build();
    }

    @GetMapping
    public String chat(@RequestParam String q){
        return chatClient.prompt()
                .system(RULES + registry.dictionary())
                .user(q).call().content();
    }
}
