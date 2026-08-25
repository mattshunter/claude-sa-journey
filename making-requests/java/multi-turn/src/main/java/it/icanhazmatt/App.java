package it.icanhazmatt;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.MessageParam.Role;

import static com.anthropic.models.messages.MessageParam.Role.ASSISTANT;
import static com.anthropic.models.messages.MessageParam.Role.USER;
import static com.anthropic.models.messages.Model.CLAUDE_SONNET_4_5;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import lombok.extern.java.Log;

/**
 * Multiturn example!  This is just a simple example based 
 * off the Building with the Claude API chat exercise (written
 * in Python).
 */
@Log
public class App {
    private final AnthropicClient client;

    private void addUserMessage(List<MessageParam> messageParams, String text) {
        addMessage(USER, messageParams, text);
    }

    private void addAssistantMessage(List<MessageParam> messageParams, String text) {
        addMessage(ASSISTANT, messageParams, text);
    }

    private void addMessage(Role role, List<MessageParam> messageParams, String text) {
        messageParams.add(MessageParam.builder().role(role).content(text).build());
    }

    private Optional<TextBlock> chat(List<MessageParam> messageParams) {
        var message = client.messages().create(
            MessageCreateParams.builder()
                .maxTokens(1000L)
                .model(CLAUDE_SONNET_4_5)
                .messages(messageParams)
                .build()
        );

        return message.content().get(0).text();        
    }

    /**
     * Default constructor to initialize the Anthropic client.  The environment values containing
     * the Anthropic API key should be set external to the application runtime.
      */
    public App() {
        this.client = AnthropicOkHttpClient.fromEnv();
    }

    public void createConversationAboutQuantumPhysics() {
        var messages = new ArrayList<MessageParam>();
        
        addUserMessage(messages, "Define quantum computing in one sentence.");
        
        var answer = chat(messages);
        answer.ifPresent(block -> {
            addAssistantMessage(messages, block.text());
            reply(block);
        });

        addUserMessage(messages, "Write another sentence.");

        answer = chat(messages);
        answer.ifPresent(block -> reply(block));        
    }

    private void reply(TextBlock textBlock) {
        log.info(textBlock.text());
    }

    public static void main(String...args) {
        var app = new App();
        app.createConversationAboutQuantumPhysics();    
    }    
}
