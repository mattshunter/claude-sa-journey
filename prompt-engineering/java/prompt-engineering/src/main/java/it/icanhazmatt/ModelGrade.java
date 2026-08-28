package it.icanhazmatt;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/** The grader model's verdict on a single output. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ModelGrade(
        List<String> strengths, List<String> weaknesses, String reasoning, Double score) {}
