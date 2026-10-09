package com.bharath.internalassist.chat;

import com.bharath.internalassist.tabular.TableRegistry;
import com.bharath.internalassist.tools.TableTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatService {

    public static final String EVIDENCE_KEY = "evidence";
    private static final String NOT_FOUND = "NOT_FOUND";
    private static final Set<String> GREETINGS =
            Set.of("hi", "hello", "hey", "thanks", "bye", "good bye", "good");
    private static final String GREETING =
            "Hello! I can answer questions from your documents, spreadsheets and approved web pages. "
            + "Try asking about a policy, a person or an event.";

    private static final PromptTemplate QA_TEMPLATE = new PromptTemplate("""
            {query}
            
            Context information is below, surrounded by -------------
            
            -------------------------
            {question_answer_context}
            -------------------------
            
            Answer using only the context above and any tool results. Do not user prior knowledge.
            If neither contains the answer, reply with exactly NOT_FOUND and nothing else.  
            """);
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
    private final VectorStore vectorStore;
    private final TableRegistry registry;
    private final double suggestMinScore;
    private final ConversationHistory history;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, TableTools tableTools, TableRegistry registry,
                       @Value("${app.rag.top-k:4}") int topK,
                       @Value("${app.rag.min-score:0.5}") double minScore,
                       @Value("${app.rag.suggest-min-score:0.4}") double suggestMinScore,
                       ConversationHistory history
                       ){

        this.registry = registry;
        this.vectorStore = vectorStore;
        this.suggestMinScore = suggestMinScore;
        SearchRequest search = SearchRequest.builder().topK(topK).similarityThreshold(minScore).build();
        this.chatClient = chatClientBuilder
                .defaultAdvisors(QuestionAnswerAdvisor.builder(vectorStore)
                        .searchRequest(search)
                        .promptTemplate(QA_TEMPLATE)
                        .build())
                .defaultTools(tableTools)
                .build();
        this.history = history;
    }

    public ChatAnswer ask(String question, String conversationId){
        if(question == null || question.isBlank()){
            return new ChatAnswer("Please type a question.", "empty", List.of(), conversationId );
        }

        String id;
        if(conversationId == null || conversationId.isBlank()){
            id = history.start();
        }else{
            history.requireExists(conversationId);
            id = conversationId;
        }

        ChatAnswer chatAnswer = generate(question, id);

        history.appendTurn(id, question, chatAnswer.answer());
        return chatAnswer.withConversationId(id);

//        List<Source> toolSources = Collections.synchronizedList(new ArrayList<>());
//
//        ChatResponse response = chatClient.prompt()
//                .system(RULES + registry.dictionary())
//                .user(question)
//                .toolContext(Map.of(EVIDENCE_KEY, toolSources))
//                .call()
//                .chatResponse();
//
//        String text = textOf(response);
//        List<Source> sources = new ArrayList<>(documentSources(response));
//        sources.addAll(toolSources);
//        sources = unique(sources);
//
//        boolean declined = text.isBlank() || text.trim().startsWith(NOT_FOUND);
//        if(declined || sources.isEmpty()){
//            return notFound(question);
//        }
//        return new ChatAnswer(text.trim(), "answered", sources);

    }

    public ChatAnswer generate(String question, String conversationId){
        if(question == null || question.isBlank()){
            return new ChatAnswer("Please type a question.", "empty", List.of(), conversationId );
        }

        List<Source> toolSources = Collections.synchronizedList(new ArrayList<>());

        ChatResponse response = chatClient.prompt()
                .system(RULES + registry.dictionary())
                .messages(history.recent(conversationId))
                .user(question)
                .toolContext(Map.of(EVIDENCE_KEY, toolSources))
                .call()
                .chatResponse();

        String text = textOf(response);
        List<Source> sources = new ArrayList<>(documentSources(response));
        sources.addAll(toolSources);
        sources = unique(sources);

        boolean declined = text.isBlank() || text.trim().startsWith(NOT_FOUND);
        if(declined || sources.isEmpty()){
            return notFound(question);
        }
        return new ChatAnswer(text.trim(), "answered", sources);

    }

    private ChatAnswer notFound(String question){

        List<Document> near = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(question).topK(3).similarityThreshold(suggestMinScore)
                .build());
        List<Source> suggestions = unique(near.stream().map(ChatService :: toSource).toList());

        StringBuilder message = new StringBuilder(
                "I could not find an answer to that in the docs,data tables or approved web pages"
        );

        if(!suggestions.isEmpty()){
            message.append(" The closest matches are listed below. They may help, or you could ask the team that owns that information.");
        }
        if(!registry.list().isEmpty()){
            message.append("\nData tables I can search: ")
                    .append(String.join(", ", registry.list().stream().map(TableRegistry.Summary:: id).toList()))
                    .append(".");

        }
        return new ChatAnswer(message.toString(), "not_found", suggestions);

    }

    private static String textOf(ChatResponse response){
        if(response == null || response.getResult() == null){
            return "";
        }
        String text = response.getResult().getOutput().getText();
        return text == null ? "" : text;
    }

    private static List<Source> documentSources(ChatResponse response){
        List<Source> found = new ArrayList<>();
        if(response == null){
            return found;
        }

        Object retrieved = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if(retrieved instanceof List<?> documents){
            for(Object item: documents){
                if(item instanceof  Document document){
                    found.add(toSource(document));
                }
            }
        }
        return found;
    }

    private static Source toSource(Document document){
        Map<String, Object> meta = document.getMetadata();
        String type = String.valueOf(meta.getOrDefault("type", "document"));
        String name = String.valueOf(meta.getOrDefault("source", "unknown"));
        String score = document.getScore() == null ? "" : String.format("similarity %.2f", document.getScore());
        return switch (type){
            case "table-row" -> new Source("spreadsheet", String.valueOf(meta.getOrDefault("table", name)), score);
            case "web" -> new Source("web", name, score);
            default -> new Source("document", name, score);
        };
    }

    private static List<Source> unique(List<Source> sources){
        Map<String,Source> byKey = new LinkedHashMap<>();
        for(Source source: sources){
            byKey.putIfAbsent(source.type()+"|"+source.name(), source);
        }
        return new ArrayList<>(byKey.values());
    }
}
