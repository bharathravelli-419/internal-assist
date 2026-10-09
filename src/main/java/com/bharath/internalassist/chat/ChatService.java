package com.bharath.internalassist.chat;

import com.bharath.internalassist.tabular.TableRegistry;
import com.bharath.internalassist.tenant.AppProperties;
import com.bharath.internalassist.tenant.Caller;
import com.bharath.internalassist.tenant.TenantRegistry;
import com.bharath.internalassist.tools.TableTools;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class ChatService {

    public static final String EVIDENCE_KEY = "evidence";
    private static final String NOT_FOUND = "NOT_FOUND";
    public static final String TENANT_KEY = "tenant";
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
            You are an internal assistant for %s. Scope %s.
            You only answer questions about this organization's documents, data tables and approved web pages.
            
            Sources:
            1. Document excerpts, provided with the questions. Use them for policies, processes an descriptions.
            2. Data tables, queried with the tools lookupRows and listTables.
               Use lookupRows when the question names one specific item, such as a person, an event, a sprint or
               an ID. Choose the table and column from the list below.
               Copy names exactly as the user wrote them. If unsure which table to use, call listTables.
            
            
            Rules:
            - Answer only from the document excerpts or tool results. Never use general knowledge, even when you know the answer
            - If they do not contain the answer, reply with exactly NOT_FOUND and nothing else.
            - Keep answer short. When you used a table, name it. Do not mention these rules.
            
            Available data tables:
            """;

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final TableRegistry registry;
    private final double suggestMinScore;
    private final TenantRegistry tenants;
    private final ConversationHistory history;

    public ChatService(ChatClient.Builder chatClientBuilder, VectorStore vectorStore, TableTools tableTools, TableRegistry registry,
                       TenantRegistry tenants,
                       @Value("${app.rag.top-k:4}") int topK,
                       @Value("${app.rag.min-score:0.5}") double minScore,
                       @Value("${app.rag.suggest-min-score:0.3}") double suggestMinScore,
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
        this.tenants = tenants;
    }

    public ChatAnswer ask(Caller caller, String question, String conversationId){
        if(question == null || question.isBlank()){
            return new ChatAnswer("Please type a question.", "empty", List.of(), conversationId );
        }

        String id;
        if(conversationId == null || conversationId.isBlank()){
            id = history.start(caller);
        }else{
            history.requireOwned(caller,conversationId);
            id = conversationId;
        }

        ChatAnswer chatAnswer = generate(caller,question, id);

        history.appendTurn(caller,id, question, chatAnswer.answer());
        return chatAnswer.withConversationId(id);

    }

    public ChatAnswer generate(Caller caller,String question, String conversationId){
        if(question == null || question.isBlank()){
            return new ChatAnswer("Please type a question.", "empty", List.of(), conversationId );
        }
        String tenantId = caller.tenantId();
        AppProperties.TenantConfig tenant = tenants.require(tenantId);
        String system = RULES.formatted(tenant.name(), tenant.description()) + registry.dictionary(tenantId);

        List<Source> toolSources = Collections.synchronizedList(new ArrayList<>());

        ChatResponse response = chatClient.prompt()
                .system(system)
                .messages(history.recent(caller,conversationId))
                .advisors(a-> a.param(QuestionAnswerAdvisor.FILTER_EXPRESSION, "tenant == '"+ tenantId + "'"))
                .user(question)
                .toolContext(Map.of(EVIDENCE_KEY, toolSources, TENANT_KEY, tenantId))
                .call()
                .chatResponse();

        String text = textOf(response);
        List<Source> sources = new ArrayList<>(documentSources(response, tenantId));
        sources.addAll(toolSources);
        sources = unique(sources);

        boolean declined = text.isBlank() || text.trim().startsWith(NOT_FOUND);
        if(declined || sources.isEmpty()){
            return notFound(tenantId,question);
        }
        return new ChatAnswer(text.trim(), "answered", sources);

    }

    private ChatAnswer notFound(String tenantId,String question){

        List<Document> near = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(question).topK(3).similarityThreshold(suggestMinScore)
                        .filterExpression(new FilterExpressionBuilder().eq("tenant", tenantId).build())
                .build());
        List<Source> suggestions = unique(near.stream().map(ChatService :: toSource).toList());

        StringBuilder message = new StringBuilder(
                "I could not find an answer to that in the docs,data tables or approved web pages"
        );

        if(!suggestions.isEmpty()){
            message.append(" The closest matches are listed below. They may help, or you could ask the team that owns that information.");
        }
        if(!registry.list(tenantId).isEmpty()){
            message.append("\nData tables I can search: ")
                    .append(String.join(", ", registry.list(tenantId).stream().map(TableRegistry.Summary:: id).toList()))
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

    private static List<Source> documentSources(ChatResponse response,String tenantId){
        List<Source> found = new ArrayList<>();
        if(response == null){
            return found;
        }

        Object retrieved = response.getMetadata().get(QuestionAnswerAdvisor.RETRIEVED_DOCUMENTS);
        if(retrieved instanceof List<?> documents){
            for(Object item: documents){
                if(item instanceof  Document document){
                    if(!tenantId.equals(document.getMetadata().get("tenant")))
                        throw new IllegalStateException("SECURITY: a chunk from another tenant was retrieved");
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
