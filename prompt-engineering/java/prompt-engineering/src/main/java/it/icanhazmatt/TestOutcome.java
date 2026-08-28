package it.icanhazmatt;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A graded run of a single test case, written to output.json and the HTML report. */
public record TestOutcome(
        @JsonProperty("output") String output,
        @JsonProperty("test_case") TestCase testCase,
        @JsonProperty("score") Double score,
        @JsonProperty("reasoning") String reasoning) {}
