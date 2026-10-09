package com.bharath.internalassist.controller;

import com.bharath.internalassist.chat.ConversationHistory;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/conversations")
@RequiredArgsConstructor
public class ConversationController {

    private final ConversationHistory history;

    @GetMapping
    public List<ConversationHistory.ConversationInfo> list(){
        return history.list();
    }

    @GetMapping("/{id}/messages")
    public List<ConversationHistory.MessageView> messages(@PathVariable String id){
        return history.messages(id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(@PathVariable String id){
        history.delete(id);
        return Map.of("status", "deleted", "id", id);
    }


}
