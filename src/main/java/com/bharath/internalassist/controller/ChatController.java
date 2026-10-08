package com.bharath.internalassist.controller;


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
    private final ChatClient chatClient;

    public ChatController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore){
        this.chatClient = chatClientBuilder
                .defaultSystem("""
                        Answer using only the context provided with the questions.
                        IF the context does not container the answer, say you do not know.
                        Do not guess.
                        """)
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore).build())
                .build();
    }

    @GetMapping
    public String chat(@RequestParam String q){
        return chatClient.prompt().user(q).call().content();
    }
}
