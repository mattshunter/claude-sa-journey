package it.icanhazmatt;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.MessageParam.Role;
import com.anthropic.models.messages.Model;
import com.anthropic.models.messages.TextBlock;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import static com.anthropic.models.messages.MessageParam.Role.ASSISTANT;
import static com.anthropic.models.messages.MessageParam.Role.USER;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import lombok.extern.java.Log;

/**
 * Java port of the Claude API Python 001_prompting.ipynb prompt engineering notebook.
 */
@Log
public class PromptEngineering {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final Model MODEL = Model.CLAUDE_HAIKU_4_5;
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([^{}]+)\\}");
    private static final List<String> JSON_STOP_SEQUENCES = List.of("```");

    private final AnthropicClient client;
    private final PrintWriter writer;
    private final ReportGenerator reportGenerator;
    private final int maxConcurrentTasks;

    /**
     * The environment values containing the Anthropic API key should be set external to the
     * application runtime.
     *
     * @param maxConcurrentTasks how many model calls to keep in flight; raise it for speed,
     *     lower it if the API starts returning rate limit errors.
     */
    public PromptEngineering(int maxConcurrentTasks) {
        this.client = AnthropicOkHttpClient.fromEnv();
        this.writer = new PrintWriter(System.out, true);
        this.reportGenerator = new ReportGenerator();
        this.maxConcurrentTasks = maxConcurrentTasks;
    }

    private void addUserMessage(List<MessageParam> messageParams, String text) {
        addMessage(USER, messageParams, text);
    }

    private void addAssistantMessage(List<MessageParam> messageParams, String text) {
        addMessage(ASSISTANT, messageParams, text);
    }

    private void addMessage(Role role, List<MessageParam> messageParams, String text) {
        messageParams.add(MessageParam.builder().role(role).content(text).build());
    }

    private void reply(String text) {
        writer.println(text);
    }

    /**
     * Single non-streaming call. Streaming isn't much use here because several of these run
     * concurrently and nothing is displayed as it arrives.
     */
    private String chat(
            List<MessageParam> messageParams,
            String system,
            Double temperature,
            List<String> stopSequences) {
        var builder = MessageCreateParams.builder()
                .model(MODEL)
                .maxTokens(1000L)
                .messages(messageParams);

        if (system != null) {
            builder.system(system);
        }

        if (temperature != null) {
            builder.temperature(temperature);
        }

        if (stopSequences != null) {
            builder.stopSequences(stopSequences);
        }

        var message = client.messages().create(builder.build());

        // Every prompt here asks for a single text answer, so the first text block is the
        // whole response.
        return message.content().stream()
                .flatMap(block -> block.text().stream())
                .findFirst()
                .map(TextBlock::text)
                .orElse("");
    }

    /**
     * Substitutes {name} placeholders with the matching entry in {@code variables}, then
     * unescapes {{ and }} to literal braces. Placeholders with no matching variable are left
     * alone, which is what keeps the literal JSON examples inside the prompts intact.
     */
    private String render(String template, Map<String, String> variables) {
        var matcher = PLACEHOLDER.matcher(template);
        var result = new StringBuilder();

        while (matcher.find()) {
            var value = variables.get(matcher.group(1));
            matcher.appendReplacement(
                    result, Matcher.quoteReplacement(value != null ? value : matcher.group()));
        }
        matcher.appendTail(result);

        return result.toString().replace("{{", "{").replace("}}", "}");
    }

    /** Renders the input spec as the pseudo-JSON snippet the prompts show the model. */
    private String describeInputs(Map<String, String> promptInputsSpec, String format) {
        var described = new StringBuilder();
        promptInputsSpec.forEach((key, value) ->
                described.append(format.formatted(key, value.replace("\n", "\\n"))));

        return described.toString();
    }

    /** Generates a list of unique ideas for test cases based on the task description. */
    private List<String> generateUniqueIdeas(
            String taskDescription, Map<String, String> promptInputsSpec, int numCases) {
        var prompt = """
                Generate {num_cases} unique, diverse ideas for testing a prompt that accomplishes this task:

                <task_description>
                {task_description}
                </task_description>

                The prompt will receive the following inputs
                <prompt_inputs>
                {prompt_inputs}
                </prompt_inputs>

                Each idea should represent a distinct scenario or example that tests different aspects of the task.

                Output Format:
                Provide your response as a structured JSON array where each item is a brief description of the idea.

                Example:
                ```json
                [
                    "Testing with technical computer science terminology",
                    "Testing with medical research findings",
                    "Testing with complex mathematical concepts",
                    ...
                ]
                ```

                Ensure each idea is:
                - Clearly distinct from the others
                - Relevant to the task description
                - Specific enough to guide generation of a full test case
                - Quick to solve without requiring extensive computation or multi-step processing
                - Solvable with no more than 400 tokens of output

                Remember, only generate {num_cases} unique ideas
                """;

        var system =
                "You are a test scenario designer specialized in creating diverse, unique testing scenarios.";

        var rendered = render(prompt, Map.of(
                "task_description", taskDescription,
                "num_cases", String.valueOf(numCases),
                "prompt_inputs", describeInputs(promptInputsSpec, "\"%s\": str # %s,")));

        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, rendered);
        addAssistantMessage(messages, "```json");
        var text = chat(messages, system, 1.0, JSON_STOP_SEQUENCES);

        try {
            return OBJECT_MAPPER.readValue(text, new TypeReference<List<String>>() {});
        } catch (IOException e) {
            log.warning("Unable to parse the generated ideas as JSON: " + e.getMessage());
            return List.of();
        }
    }

    /** Generates a single test case based on the task description and a specific idea. */
    private TestCase generateTestCase(
            String taskDescription, String idea, Map<String, String> promptInputsSpec) {
        var exampleInputs = describeInputs(promptInputsSpec, "\"%s\": \"EXAMPLE_VALUE\", // %s\n");
        var allowedKeys = promptInputsSpec.keySet().stream()
                .map("\"%s\""::formatted)
                .reduce((left, right) -> left + ", " + right)
                .orElse("");

        var prompt = """
                Generate a single detailed test case for a prompt evaluation based on:

                <task_description>
                {task_description}
                </task_description>

                <specific_idea>
                {idea}
                </specific_idea>

                <allowed_input_keys>
                {allowed_keys}
                </allowed_input_keys>

                Output Format:
                ```json
                {{
                    "prompt_inputs": {{
                    {example_prompt_inputs}
                    }},
                    "solution_criteria": ["criterion 1", "criterion 2", ...] // Concise list of criteria for evaluating the solution, 1 to 4 items
                }}
                ```

                IMPORTANT REQUIREMENTS:
                - You MUST ONLY use these exact input keys in your prompt_inputs: {allowed_keys}
                - Do NOT add any additional keys to prompt_inputs
                - All keys listed in allowed_input_keys must be included in your response
                - Make the test case realistic and practically useful
                - Include measurable, concise solution criteria
                - The solution criteria should ONLY address the direct requirements of the task description and the generated prompt_inputs
                - Avoid over-specifying criteria with requirements that go beyond the core task
                - Keep solution criteria simple, focused, and directly tied to the fundamental task
                - The test case should be tailored to the specific idea provided
                - Quick to solve without requiring extensive computation or multi-step processing
                - Solvable with no more than 400 tokens of output
                - DO NOT include any fields beyond those specified in the output format

                Here's an example of a sample input with an ideal output:
                <sample_input>
                <sample_task_description>
                Extract topics out of a passage of text
                </sample_task_description>
                <sample_specific_idea>
                Testing with a text that contains multiple nested topics and subtopics (e.g., a passage about renewable energy that covers solar power economics, wind turbine technology, and policy implications simultaneously)
                </sample_specific_idea>

                <sample_allowed_input_keys>
                "content"
                </sample_allowed_input_keys>
                </sample_input>
                <ideal_output>
                ```json
                {
                    "prompt_inputs": {
                        "content": "The transition to renewable energy encompasses numerous interdependent dimensions. Solar photovoltaic technology has seen dramatic cost reductions, with panel efficiency improving 24% since 2010 while manufacturing costs declined by 89%, making it economically competitive with fossil fuels in many markets. Concurrently, wind energy has evolved through innovative turbine designs featuring carbon-fiber composite blades and advanced control systems that increase energy capture by 35% in low-wind conditions."
                    },
                    "solution_criteria": [
                        "Includes all topics mentioned"
                    ]
                }
                ```
                </ideal_output>
                This is ideal output because the solution criteria is concise and doesn't ask for anything outside of the scope of the task description.
                """;

        var system = "You are a test case creator specializing in designing evaluation scenarios.";

        var rendered = render(prompt, Map.of(
                "allowed_keys", allowedKeys,
                "task_description", taskDescription,
                "idea", idea,
                "example_prompt_inputs", exampleInputs));

        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, rendered);
        addAssistantMessage(messages, "```json");
        var text = chat(messages, system, 0.7, JSON_STOP_SEQUENCES);

        try {
            return OBJECT_MAPPER.readValue(text, TestCase.class).describedBy(taskDescription, idea);
        } catch (IOException e) {
            log.warning("Unable to parse the generated test case as JSON: " + e.getMessage());
            return null;
        }
    }

    /** Generates a test dataset based on the task description and saves it to a file. */
    public List<TestCase> generateDataset(
            String taskDescription,
            Map<String, String> promptInputsSpec,
            int numCases,
            String outputFile) {
        var ideas = generateUniqueIdeas(taskDescription, promptInputsSpec, numCases);
        var dataset = runConcurrently(
                ideas, idea -> generateTestCase(taskDescription, idea, promptInputsSpec), "Generated");

        try {
            // writeValue truncates an existing file, so each run replaces the previous dataset.
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(outputFile), dataset);
        } catch (IOException e) {
            log.warning("Unable to write " + outputFile + ": " + e.getMessage());
        }

        return dataset;
    }

    /** Grades the output of a test case using the model. */
    private ModelGrade gradeOutput(TestCase testCase, String output, String extraCriteria) {
        var extraCriteriaSection = "";

        if (extraCriteria != null && !extraCriteria.isBlank()) {
            var extraCriteriaTemplate = """
                    Mandatory Requirements - ANY VIOLATION MEANS AUTOMATIC FAILURE (score of 3 or lower):
                    <extra_important_criteria>
                    {extra_criteria}
                    </extra_important_criteria>
                    """;
            extraCriteriaSection =
                    render(extraCriteriaTemplate, Map.of("extra_criteria", extraCriteria));
        }

        var evalTemplate = """
                Your task is to evaluate the following AI-generated solution with EXTREME RIGOR.

                Original task description:
                <task_description>
                {task_description}
                </task_description>

                Original task inputs:
                <task_inputs>
                {{ {prompt_inputs} }}
                </task_inputs>

                Solution to Evaluate:
                <solution>
                {output}
                </solution>

                Criteria you should use to evaluate the solution:
                <criteria>
                {solution_criteria}
                </criteria>

                {extra_criteria_section}

                Scoring Guidelines:
                * Score 1-3: Solution fails to meet one or more MANDATORY requirements
                * Score 4-6: Solution meets all mandatory requirements but has significant deficiencies in secondary criteria
                * Score 7-8: Solution meets all mandatory requirements and most secondary criteria, with minor issues
                * Score 9-10: Solution meets all mandatory and secondary criteria

                IMPORTANT SCORING INSTRUCTIONS:
                * Grade the output based ONLY on the listed criteria. Do not add your own extra requirements.
                * If a solution meets all of the mandatory and secondary criteria give it a 10
                * Don't complain that the solution "only" meets the mandatory and secondary criteria. Solutions shouldn't go above and beyond - they should meet the exact listed criteria.
                * ANY violation of a mandatory requirement MUST result in a score of 3 or lower
                * The full 1-10 scale should be utilized - don't hesitate to give low scores when warranted

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
                """;

        var evalPrompt = render(evalTemplate, Map.of(
                "task_description", testCase.taskDescription(),
                "prompt_inputs", describeInputs(testCase.promptInputs(), "\"%s\":\"%s\",\n"),
                "output", output,
                "solution_criteria", String.join("\n", testCase.solutionCriteria()),
                "extra_criteria_section", extraCriteriaSection));

        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, evalPrompt);
        addAssistantMessage(messages, "```json");
        var evalText = chat(messages, null, 0.0, JSON_STOP_SEQUENCES);

        try {
            return OBJECT_MAPPER.readValue(evalText, ModelGrade.class);
        } catch (IOException e) {
            log.warning("Unable to parse the grader response as JSON: " + e.getMessage());
            return null;
        }
    }

    /** Runs a single test case through the prompt under test and grades the result. */
    private TestOutcome runTestCase(
            TestCase testCase,
            Function<Map<String, String>, String> runPromptFunction,
            String extraCriteria) {
        var output = runPromptFunction.apply(testCase.promptInputs());
        var modelGrade = gradeOutput(testCase, output, extraCriteria);

        return new TestOutcome(
                output,
                testCase,
                modelGrade != null ? modelGrade.score() : null,
                modelGrade != null ? modelGrade.reasoning() : null);
    }

    /** Runs the evaluation over every test case in the dataset and writes both reports. */
    public List<TestOutcome> runEvaluation(
            Function<Map<String, String>, String> runPromptFunction,
            String datasetFile,
            String extraCriteria,
            String jsonOutputFile,
            String htmlOutputFile) {
        List<TestCase> dataset;

        try {
            dataset = OBJECT_MAPPER.readValue(
                    new File(datasetFile), new TypeReference<List<TestCase>>() {});
        } catch (IOException e) {
            log.warning("Unable to read " + datasetFile + ": " + e.getMessage());
            return List.of();
        }

        var results = runConcurrently(
                dataset, testCase -> runTestCase(testCase, runPromptFunction, extraCriteria), "Graded");

        reply("Average score: " + reportGenerator.averageScore(results));

        try {
            OBJECT_MAPPER.writerWithDefaultPrettyPrinter().writeValue(new File(jsonOutputFile), results);
        } catch (IOException e) {
            log.warning("Unable to write " + jsonOutputFile + ": " + e.getMessage());
        }

        try (var htmlWriter = new PrintWriter(htmlOutputFile, StandardCharsets.UTF_8)) {
            htmlWriter.write(reportGenerator.generateEvaluationReport(results));
        } catch (IOException e) {
            log.warning("Unable to write " + htmlOutputFile + ": " + e.getMessage());
        }

        return results;
    }

    /**
     * Runs {@code work} over every item with at most {@code maxConcurrentTasks} calls in
     * flight, reporting progress at each 20% milestone. Items that fail are dropped, so one
     * bad response doesn't sink the whole run.
     */
    private <T, R> List<R> runConcurrently(List<T> items, Function<T, R> work, String progressLabel) {
        var completed = new ArrayList<R>();

        if (items.isEmpty()) {
            return completed;
        }

        var total = items.size();
        var lastReportedPercentage = 0;

        try (var executor = Executors.newFixedThreadPool(maxConcurrentTasks)) {
            var completionService = new ExecutorCompletionService<R>(executor);
            items.forEach(item -> completionService.submit(() -> work.apply(item)));

            for (var finished = 0; finished < total; finished++) {
                try {
                    var result = completionService.take().get();

                    if (result != null) {
                        completed.add(result);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warning("Interrupted while waiting on results: " + e.getMessage());
                    break;
                } catch (Exception e) {
                    log.warning("Task failed: " + e.getMessage());
                }

                var milestonePercentage = ((finished + 1) * 100 / total) / 20 * 20;

                if (milestonePercentage > lastReportedPercentage) {
                    reply("%s %d/%d test cases".formatted(progressLabel, finished + 1, total));
                    lastReportedPercentage = milestonePercentage;
                }
            }
        }

        return completed;
    }

    /** The prompt being evaluated. Runs once per test case and returns the raw model output. */
    private String runPrompt(Map<String, String> promptInputs) {
        var prompt = """
                Generate a one-day meal plan for an athlete that meets their dietary restrictions.

                - Height: %s
                - Weight: %s
                - Goal: %s
                - Dietary Restrictions: %s

                Guidelines:
                1. Include accurate daily calorie amount
                2. Show protein, fat, and carbohydrate quantities
                3. Specify the time of day to eat each meal (do not assign a meal time at night time when the athlete should be resting)
                4. Use only foods that fit within the restrictions (no pork if Muslim, kosher diet if Jewish, no sugar if diabetic, no meat if vegetarian, etc)
                5. List all portion sizes in both metric and imperial.  Liquids should use volumetric measurements, solids should be by weight.
                6. Keep the cost budget friendly if expense is mentioned as a concern
                """.formatted(
                        promptInputs.get("height"),
                        promptInputs.get("weight"),
                        promptInputs.get("goal"),
                        promptInputs.get("restrictions"));

        var messages = new ArrayList<MessageParam>();
        addUserMessage(messages, prompt);

        return chat(messages, null, 1.0, null);
    }

    public static void main(String... args) {
        // Raise the concurrency for a faster run, but beware of rate limit errors.
        var app = new PromptEngineering(1);

        // The inputs the prompt under test expects. LinkedHashMap so the generated test cases
        // and the report list them in the order written here.
        var promptInputsSpec = new LinkedHashMap<String, String>();
        promptInputsSpec.put("height", "Athlete's height in feet and inches (example 6' 2\")");
        promptInputsSpec.put("weight", "Athlete's weight in pounds (example: 210 lbs)");
        promptInputsSpec.put(
                "goal", "Athletic goal of the athlete (example: maintain weight, include agility, etc.)");
        promptInputsSpec.put(
                "restrictions",
                "Dietary concerns (religious constraints, allergies, health conditions like diabetes, etc)");

        app.generateDataset(
                "Write a compact, concise 1 day meal plan for a single male athlete in his 20s.",
                promptInputsSpec,
                5,
                "dataset.json");

        app.runEvaluation(
                app::runPrompt,
                "dataset.json",
                """
                Ensure the output includes:
                    - Daily caloric total
                    - Macronutrient breakdown
                    - Meals with exact foods, portions, and timing
                """,
                "output.json",
                "output.html");
    }
}
