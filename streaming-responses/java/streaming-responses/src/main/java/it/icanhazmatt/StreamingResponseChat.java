package it.icanhazmatt;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.MessageParam.Role;

import static com.anthropic.models.messages.MessageParam.Role.ASSISTANT;
import static com.anthropic.models.messages.MessageParam.Role.USER;
import static com.anthropic.models.messages.Model.CLAUDE_SONNET_4_5;

import java.io.PrintWriter;
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
public class StreamingResponseChat {
    private final AnthropicClient client;
    private final PrintWriter writer;

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
        var messageCreateParams = MessageCreateParams.builder()
                .maxTokens(1000L)
                .model(CLAUDE_SONNET_4_5)
                .messages(messageParams)
                .build();
        var messageAccumulator = MessageAccumulator.create();

        try (var response = client.messages().createStreaming(messageCreateParams)) {
            response.stream().forEach(event -> {
                messageAccumulator.accumulate(event);

                if (event.isContentBlockDelta()) {
                    event.asContentBlockDelta().delta().text().ifPresent(textDelta -> {
                        reply(textDelta.text());
                        System.out.flush();
                    });
                }
            });
        };

        return messageAccumulator.message().content().get(0).text();
    }

    /**
     * Default constructor to initialize the Anthropic client.  The environment values containing
     * the Anthropic API key should be set external to the application runtime.
      */
    public StreamingResponseChat() {
        this.client = AnthropicOkHttpClient.fromEnv();
        this.writer = new PrintWriter(System.out, true);
    }

    public void startConversation() {
        var messages = new ArrayList<MessageParam>();
        var console = System.console();
        var finished = false;
        writer.println("What can I help you with today?\n (type exit or stop to end our conversation.");
        var text = "";
        
        do {
            text = console.readLine("> ");
            
            if((text.equals("exit") || text.equals("stop"))) {
                finished = true;
            } else {
                addUserMessage(messages, text);
            
                var answer = chat(messages);
                answer.ifPresent(block -> {
                    addAssistantMessage(messages, block.text());
                });
            }
        } while(!finished);
    }

    private void reply(TextBlock textBlock) {
        reply(textBlock.text());
    }

    private void reply(String text) {
        writer.println(text);
    }

    public static void main(String...args) {
        var app = new StreamingResponseChat();
        app.startConversation();    
    }    
}
