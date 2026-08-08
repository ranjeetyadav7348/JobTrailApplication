package com.jobtrail.web;

import com.jobtrail.service.ChatAssistantService;
import com.jobtrail.web.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.chat.messages.Message;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

/** The assistant panel: ask a question, get an answer, keep the thread. */
@RestController
@RequestMapping("/api/chat")
@RequiredArgsConstructor
public class ChatController {

    private final ChatAssistantService assistant;

    public record AskRequest(String conversationId, String question) {
    }

    public record TurnView(String role, String text) {
    }

    public record ChatStatusView(boolean available, String conversationId, List<TurnView> history) {
    }

    @GetMapping
    public ChatStatusView status(@RequestParam(required = false) String conversationId) {
        return new ChatStatusView(assistant.available(), conversationId, history(conversationId));
    }

    @PostMapping
    public ChatAssistantService.Reply ask(@RequestBody AskRequest body) {
        return assistant.ask(body.conversationId(), body.question());
    }

    @DeleteMapping
    public Views.ActionResult clear(@RequestParam(required = false) String conversationId) {
        assistant.clear(conversationId);
        return new Views.ActionResult(true, "Conversation cleared.");
    }

    private List<TurnView> history(String conversationId) {
        return assistant.history(conversationId).stream()
                .map(m -> new TurnView(roleOf(m), m.getText()))
                .toList();
    }

    /** Spring AI's message types map onto the two roles the panel renders. */
    private static String roleOf(Message message) {
        return switch (message.getMessageType()) {
            case USER -> "user";
            case ASSISTANT -> "assistant";
            default -> "system";
        };
    }
}
