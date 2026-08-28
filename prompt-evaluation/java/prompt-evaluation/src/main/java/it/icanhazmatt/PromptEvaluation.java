package it.icanhazmatt;

import com.anthropic.client.AnthropicClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.helpers.MessageAccumulator;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.TextBlock;
import com.anthropic.models.messages.MessageParam.Role;

import static com.anthropic.models.messages.MessageParam.Role.ASSISTANT;
import static com.anthropic.models.messages.MessageParam.Role.USER;
import static com.anthropic.models.messages.Model.CLAUDE_SONNET_4_5;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static java.util.stream.Collectors.toList;

import lombok.extern.java.Log;

/**
 * Multiturn example!  This is just a simple example based 
 * off the Building with the Claude API chat exercise (written
 * in Python).
 */
@Log
public class PromptEvaluation {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final AnthropicClient client;
    private final PrintWriter writer;
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record EvaluationTask(String task, String format) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ModelGrade(List<String> strengths, List<String> weaknesses, String reasoning, Double score) {}

    private record TestOutcome(Optional<TextBlock> output, EvaluationTask task, Double score, String reasoning) {}

    private void addUserMessage(List<MessageParam> messageParams, String text) {
        addMessage(USER, messageParams, text);
    }

    private void addAssistantMessage(List<MessageParam> messageParams, String text) {
        addMessage(ASSISTANT, messageParams, text);
    }

    private void addMessage(Role role, List<MessageParam> messageParams, String text) {
        messageParams.add(MessageParam.builder().role(role).content(text).build());
    }

    private Optional<TextBlock> chat(List<MessageParam> messageParams, List<String> stopSequences) {
        
        var messageCreateParamsBuilder = MessageCreateParams.builder()
                .maxTokens(1000L)
                .model(CLAUDE_SONNET_4_5)
                .messages(messageParams)
        ;

        if (stopSequences != null) {
            messageCreateParamsBuilder.stopSequences(stopSequences);
        }

        var messageCreateParams = messageCreateParamsBuilder.build();
        var messageAccumulator = MessageAccumulator.create();

        try (var response = client.messages().createStreaming(messageCreateParams)) {
            response.stream().forEach(event -> {
                messageAccumulator.accumulate(event);

                if (event.isContentBlockDelta()) {
                    event.asContentBlockDelta().delta().text().ifPresent(textDelta -> {
                        reply(textDelta.text());
                        writer.flush();
                    });
                }
            });
        };

        return messageAccumulator.message().content().get(0).text();
    }

    private void reply(String text) {
        writer.println(text);
    }

    private void generateDataSet() {
        var prompt = """
Generate a evaluation dataset for a prompt evaluation. The dataset will be used to evaluate prompts
that generate Python, JSON, or Regex specifically for AWS-related tasks. Generate an array of JSON objects,
each representing task that requires Python, JSON, or a Regex to complete.

Example output:
```json
[
    {
        "task": "Description of task",
         "format": "json" or "python" or "regex"
    },
    ...additional
]
```

* Focus on tasks that can be solved by writing a single Python function, a single JSON object, or a regular expression.
* Focus on tasks that do not require writing much code

Please generate 3 objects.                
""";
        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, prompt);
        addAssistantMessage(messages, "```json");
        var text = chat(messages, Arrays.asList("```"));

        // The assistant reply was prefilled with "```json" and stopped at "```", so the
        // raw text is the JSON array itself.
        var json = text.map(TextBlock::text).orElse("[]");

        try {
            var dataSet = OBJECT_MAPPER.readValue(json, new TypeReference<List<EvaluationTask>>() {});

            // writeValue truncates an existing file, so each run replaces the previous dataset.
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter()
                    .writeValue(new File("dataset.json"), dataSet);
        } catch (JsonProcessingException e) {
            log.warning("Unable to parse the generated dataset as JSON: " + e.getMessage());
        } catch (IOException e) {
            log.warning("Unable to write dataset.json: " + e.getMessage());
        }
    }

    private ModelGrade gradeByModel(String task, String output) {
        var evaluationPrompt = """
    You are an expert AWS code reviewer. Your task is to evaluate the following AI-generated solution.

Original Task:
<task>
%s
</task>

Solution to Evaluate:
<solution>
%s
</solution>

Output Format
Provide your evaluation as a structured JSON object with the following fields, in this specific order:
- "strengths": An array of 1-3 key strengths
- "weaknesses": An array of 1-3 key areas for improvement
- "reasoning": A concise explanation of your overall assessment
- "score": A number between 1-10

Respond with JSON. Keep your response concise and direct.
Example response shape:
{{
    "strengths": string[],
    "weaknesses": string[],
    "reasoning": string,
    "score": number
}}
                """.formatted(task, output);
        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, evaluationPrompt);
        addAssistantMessage(messages, "```json");
        var evaluationText = chat(messages, Arrays.asList("```"));
        var json = evaluationText.map(TextBlock::text).orElse("{}");

        try {
            return OBJECT_MAPPER.readValue(json, new TypeReference<ModelGrade>() {});
        } catch (Exception e) {
            log.warning("Unable to parse the grader response as JSON: " + e.getMessage());
            return null;
        }
    }

    private Optional<TextBlock> runPrompt(EvaluationTask testCase) {
        var prompt = """
                Please solve the following task:

                %s
                """.formatted(testCase.task);
        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, prompt);
        return chat(messages, null);

    }

    private TestOutcome runTestCase(EvaluationTask testCase) {
        var output = runPrompt(testCase);
        var modelGrade = gradeByModel(testCase.task, output.map(TextBlock::text).orElse(""));
        return new TestOutcome(output, testCase,
                modelGrade != null ? modelGrade.score : null,
                modelGrade != null ? modelGrade.reasoning : null);
    }

    private List<TestOutcome> runEvaluation(List<EvaluationTask> tasks) {
        var testOutcomes = tasks.stream().map(this::runTestCase).collect(toList());
        reply("Average Score: " + 
            testOutcomes.stream().filter(t -> t != null && t.score != null).mapToDouble(TestOutcome::score).average().orElse(0.0)
        );

        return testOutcomes;
    }

    /**
     * Default constructor to initialize the Anthropic client.  The environment values containing
     * the Anthropic API key should be set external to the application runtime.
      */
    public PromptEvaluation() throws Exception {
        this.client = AnthropicOkHttpClient.fromEnv();
        this.writer = new PrintWriter(System.out, true);
    }

    private void evaluateDataSet() {
        // Open dataset.json
        var dataSetFile = new File("dataset.json");

        try {
            // Map to List<EvalationTask>
            var tasks = OBJECT_MAPPER.readValue(dataSetFile, new TypeReference<List<EvaluationTask>>() {});

            // Call runEvaluation()
            runEvaluation(tasks);
        } catch (JsonProcessingException e) {
            log.warning("Unable to parse dataset.json: " + e.getMessage());
        } catch (IOException e) {
            log.warning("Unable to read dataset.json: " + e.getMessage());
        }
    }

    public static void main(String...args) throws Exception {
        var app = new PromptEvaluation();
        app.generateDataSet();
        app.evaluateDataSet();
    }    

}
