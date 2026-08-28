package it.icanhazmatt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * One generated test case. The JSON property names match dataset.json as written by the
 * Python notebook, so the two implementations can read each other's datasets.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TestCase(
        @JsonProperty("prompt_inputs") Map<String, String> promptInputs,
        @JsonProperty("solution_criteria") List<String> solutionCriteria,
        @JsonProperty("task_description") String taskDescription,
        @JsonProperty("scenario") String scenario) {

    /**
     * The generator only returns prompt_inputs and solution_criteria; the caller knows the
     * task description and the idea that produced the case.
     */
    public TestCase describedBy(String taskDescription, String scenario) {
        return new TestCase(promptInputs, solutionCriteria, taskDescription, scenario);
    }
}
