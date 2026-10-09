package com.bharath.internalassist.chat;

import java.util.List;

public record ChatAnswer(String answer, String status, List<Source> sources, String conversationId) {
    public ChatAnswer(String answer, String status, List<Source> sources){
        this(answer, status,sources, null);
    }

    public ChatAnswer withConversationId(String id){
        return new ChatAnswer(answer,status, sources,id);
    }
}
