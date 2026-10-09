package com.bharath.internalassist.controller;


import com.bharath.internalassist.chat.ChatAnswer;
import com.bharath.internalassist.chat.ChatService;

import com.bharath.internalassist.dto.ChatRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

   private final ChatService chatService;

   @PostMapping
    public ChatAnswer chat(@RequestBody ChatRequest chatRequest){
       return chatService.ask(chatRequest.q(), chatRequest.conversationId());
   }
}
