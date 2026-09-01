package it.icanhazmatt;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalUnit;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.MessageParam.Content;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.Tool.InputSchema;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.ToolUseBlock;
import com.anthropic.models.messages.ToolUseBlockParam;

import static java.util.stream.Collectors.joining;
import static com.anthropic.models.messages.MessageParam.Role.ASSISTANT;
import static com.anthropic.models.messages.MessageParam.Role.USER;
import static com.anthropic.models.messages.Model.CLAUDE_HAIKU_4_5;

import lombok.extern.java.Log;

/**
 * Hello world!
 */
@Log
public class Tools {
    private final AnthropicClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public Tools() {
        client = AnthropicOkHttpClient.fromEnv();
    }
    
    private void addUserMessage(List<MessageParam> messageParams, String text) {
        addMessage(USER, messageParams, text);
    }

    private void addMessage(Role role, List<MessageParam> messageParams, String text) {
        messageParams.add(MessageParam.builder().role(role).content(text).build());
    }

    private void addUserMessage(List<MessageParam> messageParams, Message message) {
        addMessage(USER, messageParams, contentFromMessage(message));
    }

    private void addUserMessage(List<MessageParam> messages, List<ToolResultBlockParam> toolResults) {
        messages.add(MessageParam.builder()
                        .role(MessageParam.Role.USER)
                        .contentOfBlockParams(toolResults.stream().map(ContentBlockParam::ofToolResult).toList())
                    .build()
        );
    }

    private void addAssistantMessage(List<MessageParam> messageParams, Message message) {
        addMessage(ASSISTANT, messageParams, contentFromMessage(message));
    }

    private void addMessage(Role role, List<MessageParam> messageParams, Content content) {
        messageParams.add(MessageParam.builder().role(role).content(content).build());
    }

    private Content contentFromMessage(Message message) {
        return Content.ofBlockParams(
                message.content().stream()
                        .map(ContentBlock::toParam)
                        .toList());
    }

    private Message chat(List<MessageParam> messageParams, String system,
                         Double temperature, List<String> stopSequences,
                         List<Tool> tools) {

        var messageBuilder = MessageCreateParams.builder()
                .maxTokens(1000L)
                .model(CLAUDE_HAIKU_4_5)
                .messages(messageParams);

        if (system != null) {
            messageBuilder.system(system);
        }

        if (temperature != null) {
            messageBuilder.temperature(temperature);
        }

        if (stopSequences != null) {
            messageBuilder.stopSequences(stopSequences);
        }

        if (tools != null) {
            tools.forEach(messageBuilder::addTool);
        }
        
        return client.messages().create(messageBuilder.build());
    }

    private String textFromMessage(Message message) {
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .map(TextBlock::text)
                .collect(joining("\n"));
    }        


    private String addDurationToDate(String dateTimeStr, long duration,
         ChronoUnit unit, DateTimeFormatter inputFormat
    ) {
        var originalDate = LocalDateTime.parse(dateTimeStr, inputFormat);
        var newDate = originalDate.plus(duration, unit);
        return inputFormat.format(newDate);
    }

    private Tool addDurationToDateTool() {
        return Tool.builder()
            .name("addDurationToDate")
            .description("Adds a specified duration to a datetime string and returns the resulting datetime in a detailed format. This tool converts an input datetime string to a Python datetime object, adds the specified duration in the requested unit, and returns a formatted string of the resulting datetime. It handles various time units including seconds, minutes, hours, days, weeks, months, and years, with special handling for month and year calculations to account for varying month lengths and leap years. The output is always returned in a detailed format that includes the day of the week, month name, day, year, and time with AM/PM indicator (e.g., 'Thursday, April 03, 2025 10:30:00 AM').")
            .inputSchema(addDurationToDateSchema())
            .build();        
    }

    private InputSchema addDurationToDateSchema() {
        return InputSchema.builder()
            .properties(
                InputSchema.Properties.builder()
                    .putAdditionalProperty("datetime_str", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "The input datetime string to which the duration will be added. This should be formatted according to the input_format parameter."
                    )))
                    .putAdditionalProperty("duration", JsonValue.from(Map.of(
                        "type", "number",
                        "description", "The amount of time to add to the datetime. Can be positive (for future dates) or negative (for past dates). Defaults to 0."
                    )))
                    .putAdditionalProperty("unit", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "The unit of time for the duration. Must be one of: 'SECONDS', 'MINUTES', 'HOURS', 'DAYS', 'WEEKS', 'MONTHS', or 'YEARS'. There is no default and the value must be upper case to align with the enumerations specified in the Java java.time.temporal.ChronoUnit enumerated type."
                    )))
                    .putAdditionalProperty("input_format", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "The format string for parsing the input datetime_str, using Python's strptime format codes. For example, '%Y-%m-%d' for ISO format dates like '2025-04-03'. Defaults to '%Y-%m-%d'."
                    )))
                    .build()
            )
            .required(List.of("datetime_str"))
            .build();
    }

    private void setReminder(String content, String dateTime) {
        log.info("Setting a reminder for %s:\n %s".formatted(dateTime, content));
    }

    private Tool setReminderTool() {
        return Tool.builder()
            .name("setReminder")
            .description("Creates a timed reminder that will notify the user at the specified time with the provided content. This tool schedules a notification to be delivered to the user at the exact timestamp provided. It should be used when a user wants to be reminded about something specific at a future point in time. The reminder system will store the content and timestamp, then trigger a notification through the user's preferred notification channels (mobile alerts, email, etc.) when the specified time arrives. Reminders are persisted even if the application is closed or the device is restarted. Users can rely on this function for important time-sensitive notifications such as meetings, tasks, medication schedules, or any other time-bound activities.")
            .inputSchema(setReminderToolSchema())
            .build();
    }

    private InputSchema setReminderToolSchema() {
        return InputSchema.builder()
            .properties(
                Tool.InputSchema.Properties.builder()
                    .putAdditionalProperty("content", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "The message text that will be displayed in the reminder notification. This should contain the specific information the user wants to be reminded about, such as 'Take medication', 'Join video call with team', or 'Pay utility bills'."
                    )))
                    .putAdditionalProperty("timestamp", JsonValue.from(Map.of(
                        "type", "string",
                        "description", "The exact date and time when the reminder should be triggered, formatted as an ISO 8601 timestamp according to the Java DateTimeFormatter standard. The system handles all timezone processing internally, ensuring reminders are triggered at the correct time regardless of where the user is located. Users can simply specify the desired time without worrying about timezone configurations."
                    )))
                    .build()
            )
            .required(List.of("content", "timestamp"))
            .build();
    }

    private InputSchema batchToolSchema() {
        return InputSchema.builder()
            .properties(
                InputSchema.Properties.builder()
                    .putAdditionalProperty("invocations", JsonValue.from(Map.of(
                        "type", "array",
                        "description", "The tool calls to invoke",
                        "items", Map.of(
                            "type", "object",
                            "properties", Map.of(
                                "name", Map.of(
                                    "type", "string",
                                    "description", "The name of the tool to invoke"
                                ),
                                "arguments", Map.of(
                                    "type", "string",
                                    "description", "The arguments to the tool, encoded as a JSON string"
                                )
                            ),
                            "required", List.of("name", "arguments")
                        )
                    )))
                    .build()
            )
            .required(List.of("invocations"))
            .build();
    }

    private String getCurrentDateTime(String format) {
        var formatter = DateTimeFormatter.ofPattern(format);
        return LocalDateTime.now().format(formatter);
    }    

    private InputSchema getCurrentDateTimeSchema() {
        return InputSchema.builder()
            .properties(
                InputSchema.Properties.builder()
                    .putAdditionalProperty(
                        "date_format", JsonValue.from(Map.of(
                            "type", "string",
                            "description", "A string specifying the format of the returned datetime." + 
                                           " Uses Java's DateTimeFormatter format codes. Java DateTimeFormatter" + 
                                           " pattern. Case matters: MM=month, mm=minute, ss=second," + 
                                           " SS=fraction-of-second. Examples: 'HH:mm:ss' for 24-hour time, " + 
                                           "'yyyy-MM-dd HH:mm:ss' for full timestamp.",
                            "default", "%Y-%m-%d %H:%M:%S"
                        ))
                    )
                .build()            
            ).build();
    }

    private Tool getCurrentDateTimeTool() {
        return Tool.builder()
            .name("getCurrentDateTime")
            .description("Returns the current date and time formatted according to the specified format")
            .inputSchema(getCurrentDateTimeSchema())
            .build();
    }

    private <T> T x(Function<String, T> f, String key) {
        return (T) f.apply(key);
    }

    private String runTool(String toolName, String jsonToolInput) throws Exception {
        var toolInput = mapper.readTree(jsonToolInput);
        Function<String, String> s = (String k) -> toolInput.path(k).asText();
        Function<String, Long> l = (String k) -> toolInput.path(k).asLong();

        return switch (toolName) {
            case "getCurrentDateTime" -> {
                log.info("==========Getting current date===========");
                var dateFormat = x(s, "date_format");
                yield getCurrentDateTime(dateFormat);
            }
            case "addDurationToDate" -> {
                log.info("==========Adding duration to current date===========");
                var dateTimeStr = x(s, "datetime_str");
                var duration = x(l, "duration").longValue();
                log.info("Raw chrono unit: %s".formatted(x(s, "unit")));
                var unit = ChronoUnit.valueOf(x(s, "unit"));            
                log.info("Raw input format: %s".formatted(x(s, "input_format")));
                var inputFormat = DateTimeFormatter.ofPattern(x(s, "input_format"));
                log.info("Calling addDurationToDate with: %s %d %s %s".formatted(dateTimeStr, duration, unit, inputFormat));
                yield addDurationToDate(dateTimeStr, duration, unit, inputFormat);
            }
            case "setReminder" -> {
                log.info("==========Setting a reminder===========");
                var content = x(s, "content");
                var timestamp = x(s, "timestamp");
                setReminder(content, timestamp);
                yield "Reminder set.";
            }

            default -> null;
        };
    }

    private List<ToolResultBlockParam> runTools(Message message) {
       var toolRequests = message.content()
                            .stream()
                            .filter(b -> b.isToolUse())
                            .map(b -> b.asToolUse())
                            .toList();

        var toolResultBlocks = toolRequests.stream().map(t -> {
            var toolResultBlock = ToolResultBlockParam.builder().toolUseId(t.id());

            try {
                var toolOutput = runTool(t.name(), mapper.writeValueAsString(t._input()));
                toolResultBlock
                    .content(mapper.writeValueAsString(toolOutput))
                    .isError(false);
            } catch (Exception e) {
                toolResultBlock
                    .content(String.format("Error: %s", e.getMessage()))
                    .isError(true);
            }

            return toolResultBlock.build();
        }).collect(Collectors.toList());
        
        return toolResultBlocks;
    }

    private void runConversation(List<MessageParam> messages) {     
        while (true) {
            var response = chat(messages, null, null, null, 
                List.of(getCurrentDateTimeTool(), 
                        addDurationToDateTool(),
                        setReminderTool()
                )
            );
            addAssistantMessage(messages, response);
            log.info(textFromMessage(response));

            if(!response.stopReason().map(StopReason.TOOL_USE::equals).orElse(false)) {
                break;
            }

            addUserMessage(messages, runTools(response));
        }
    }

    public static void main(String... args) {
        var tools = new Tools();
        var messages = new ArrayList<MessageParam>();
        tools.addUserMessage(messages, "Set a reminder for my doctor's appointment at 9:00 AM, 874 days from today.");
        tools.runConversation(messages);
    }
}
