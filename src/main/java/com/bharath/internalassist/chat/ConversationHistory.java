package com.bharath.internalassist.chat;

import com.bharath.internalassist.tenant.Caller;
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
                tenant_id TEXT NOT NULL,
                user_id TEXT NOT NULL,
                title TEXT NOT NULL,
                created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
                updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
                )
                """);
    }

    public String start(Caller caller){
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("INSERT INTO conversation (id,tenant_id,user_id, title) VALUES (?,?,?,?)", id,caller.tenantId(),caller.userId(), DEFAULT_TITLE);
        return id;
    }

    public void requireOwned(Caller caller, String id){
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM conversation WHERE id= ? AND tenant_id =? AND user_id =?",Integer.class,id,caller.tenantId(), caller.userId());
        if(count == null || count == 0){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation: "+ id);
        }
    }

    public void requireExists(String id){
        Integer count = jdbcTemplate.queryForObject("SELECT count(*) FROM conversation WHERE id= ?",Integer.class,id);
        if(count == null || count == 0){
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown conversation: "+ id);
        }

    }

    public synchronized void appendTurn(Caller caller,String id, String question, String answer){
        requireOwned(caller, id);
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

    public List<Message> recent(Caller caller,String id){
        requireOwned(caller,id);
        List<Message> all = repository.findByConversationId(id);
        return new ArrayList<>(all.subList(Math.max(0, all.size()-contextMessagesCount), all.size()));
    }

    public List<MessageView> messages(Caller caller,String id){
        requireOwned(caller,id);
        return repository.findByConversationId(id).stream()
                .map(m-> new MessageView(m.getMessageType().getValue(), m.getText()))
                .toList();
    }

    public List<ConversationInfo> list(Caller caller){
        return jdbcTemplate.query("SELECT id, title, updated_at FROM conversation WHERE tenant_id=? AND user_id=? ORDER BY updated_at DESC",
                (rs, row)-> new ConversationInfo(rs.getString(1), rs.getString(2), rs.getTimestamp(3).toInstant()),
                caller.tenantId(), caller.userId()
                );
    }

    public synchronized void delete(Caller caller,String id){
        requireOwned(caller,id);
        repository.deleteByConversationId(id);
        jdbcTemplate.update("DELETE FROM conversation WHERE id=?", id);
    }

    private static String shorten(String question){
        String oneLine = question.trim().replaceAll("\\s+", " ");
        return oneLine.length() <=60 ? oneLine : oneLine.substring(0,57)+ "...";
    }

}
