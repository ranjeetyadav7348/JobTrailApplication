package com.jobtrail.service;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Whether an LLM is actually usable.
 *
 * <p>The presence of a {@link ChatModel} bean is not enough on its own: Spring
 * AI's starters create one whether or not a credential was supplied, so a bean
 * check alone reports "ready" for a model that answers every request with a
 * 401. Both the bean and a non-blank key have to be there.
 *
 * <p>The key properties below are per-provider by nature. Adding a provider
 * means adding its property here — the one place in the app that has to know
 * provider names at all.
 */
@Component
public class AiAvailability {

    private static final List<String> KEY_PROPERTIES = List.of(
            "spring.ai.anthropic.api-key",
            "spring.ai.openai.api-key",
            "spring.ai.azure.openai.api-key",
            "spring.ai.mistralai.api-key",
            "spring.ai.vertex.ai.gemini.project-id",
            "spring.ai.ollama.base-url");

    private final ObjectProvider<ChatModel> chatModelProvider;
    private final Environment environment;

    public AiAvailability(ObjectProvider<ChatModel> chatModelProvider, Environment environment) {
        this.chatModelProvider = chatModelProvider;
        this.environment = environment;
    }

    /** A model bean exists and something was configured to authenticate it. */
    public boolean ready() {
        return model() != null && credentialPresent();
    }

    public ChatModel model() {
        return chatModelProvider.getIfAvailable();
    }

    private boolean credentialPresent() {
        return KEY_PROPERTIES.stream()
                .map(environment::getProperty)
                .anyMatch(value -> value != null && !value.isBlank());
    }
}
