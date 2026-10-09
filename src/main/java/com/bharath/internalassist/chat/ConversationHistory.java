package com.bharath.internalassist.chat;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Component
public class ConversationHistory {

    public record ConversationInfo(String id, String title, Instant updatedAt){}
    public record MessageView(String role, String text){}

    private static final String DEFAULT_TITLE = "New Conversation";

    private final ChatMemoryRepository repository;
    private final JdbcTemplate jdbcTemplate;
    private final int contextMessagesCount;

    public ConversationHistory(
            ChatMemoryRepository repository,
            JdbcTemplate jdbcTemplate,
            @Value("${app.chat.history-messages:10}") int contextMessagesCount
    ){
        this.repository = repository;
        this.jdbcTemplate = jdbcTemplate;
        this.contextMessagesCount = contextMessagesCount;
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS conversation (
                id  VARCHAR(36) PRIMARY KEY,
                title TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    public String start(){
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO conversation (id, title) VALUES (?,?)", id, DEFAULT_TITLE);
        return id;
    }

    public void requireExists(String id){
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM conversation WHERE id= ?",Integer.class,id);
        if(count == null || count == 0){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation: "+ id);
        }

    }

    public synchronized void appendTurn(String id, String question, String answer){
        List<Message> all = new ArrayList<>(repository.findByConversationId(id));
        all.add(new UserMessage(question));
        all.add(new AssistantMessage(answer));
        repository.saveAll(id, all);

        jdbcTemplate.update("""
                UPDATE conversation
                SET updated_at = now(),
                title = CASE WHEN title =? THEN ? ELSE title END
                WHERE id =?
                """,DEFAULT_TITLE, shorten(question), id);
    }

    public List<Message> recent(String id){
        List<Message> all = repository.findByConversationId(id);
        return new ArrayList<>(all.subList(Math.max(0, all.size()-contextMessagesCount), all.size()));
    }

    public List<MessageView> messages(String id){
        requireExists(id);
        return repository.findByConversationId(id).stream()
                .map(m-> new MessageView(m.getMessageType().getValue(), m.getText()))
                .toList();
    }

    public List<ConversationInfo> list(){
        return jdbcTemplate.query("SELECT id, title, updated_at FROM conversation ORDER BY updated_at DESC",
                (rs, row)-> new ConversationInfo(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant())
                );
    }

    public synchronized void delete(String id){
        requireExists(id);
        repository.deleteByConversationId(id);
        jdbcTemplate.update("DELETE FROM conversation WHERE id=?", id);
    }

    private static String shorten(String question){
        String oneLine = question.trim().replaceAll("\\s+", " ");
        return oneLine.length() <=60 ? oneLine : oneLine.substring(0,57)+ "...";
    }

}
