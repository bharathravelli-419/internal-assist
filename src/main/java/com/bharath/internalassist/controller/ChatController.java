package com.bharath.internalassist.controller;


import com.bharath.internalassist.chat.ChatAnswer;
import com.bharath.internalassist.chat.ChatService;

import com.bharath.internalassist.dto.ChatRequest;
import com.bharath.internalassist.tenant.ApiKeyFilter;
import com.bharath.internalassist.tenant.Caller;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

   private final ChatService chatService;

   @PostMapping
    public ChatAnswer chat(@RequestAttribute(ApiKeyFilter.CALLER_ATTRIBUTE) Caller caller, @RequestBody ChatRequest chatRequest){
       return chatService.ask(caller,chatRequest.q(), chatRequest.conversationId());
   }
}
