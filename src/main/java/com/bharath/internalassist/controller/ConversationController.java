package com.bharath.internalassist.controller;

import com.bharath.internalassist.chat.ConversationHistory;
import com.bharath.internalassist.tenant.ApiKeyFilter;
import com.bharath.internalassist.tenant.Caller;
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
    public List<ConversationHistory.ConversationInfo> list(
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller
    ){
        return history.list(caller);
    }

    @GetMapping("/{id}/messages")
    public List<ConversationHistory.MessageView> messages(
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller,
            @PathVariable String id){
        return history.messages(caller,id);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> delete(
            @RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller,
            @PathVariable String id){
        history.delete(caller,id);
        return Map.of("status", "deleted", "id", id);
    }


}
