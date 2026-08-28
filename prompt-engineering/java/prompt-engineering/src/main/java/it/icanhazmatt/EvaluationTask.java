package it.icanhazmatt;

import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record EvaluationTask(String task, Map<String, String> promptInputs, String format) {}
