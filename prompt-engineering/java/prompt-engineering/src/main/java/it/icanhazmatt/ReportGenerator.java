package it.icanhazmatt;

import java.util.List;

/**
 * Builds the HTML evaluation report, the Java equivalent of the notebook's
 * generate_prompt_evaluation_report function.
 *
 * <p>Note the escaping: model output lands inside the table, so anything that looks like
 * markup is escaped before it goes in.
 */
public class ReportGenerator {
    private static final double MAX_SCORE = 10.0;
    private static final double SCORE_THRESHOLD = 7.0;

    /** Default constructor. */
    public ReportGenerator() {}

    /** Renders the evaluation results as a standalone HTML document. */
    public String generateEvaluationReport(List<TestOutcome> testOutcomes) {
        if (testOutcomes == null) {
            testOutcomes = List.of();
        }

        var totalTests = testOutcomes.size();
        var averageScore = averageScore(testOutcomes);
        var scoresHittingThreshold = testOutcomes.stream()
                .filter(outcome -> outcome.score() != null && outcome.score() >= SCORE_THRESHOLD)
                .count();
        var passRate = totalTests == 0 ? 0.0 : (100.0 * scoresHittingThreshold) / totalTests;

        var html = new StringBuilder("""
                <!DOCTYPE html>
                <html lang="en">
                <head>
                    <meta charset="UTF-8">
                    <meta name="viewport" content="width=device-width, initial-scale=1.0">
                    <title>Prompt Evaluation Report</title>
                    <style>
                        body {
                            font-family: Arial, sans-serif;
                            line-height: 1.6;
                            margin: 0;
                            padding: 20px;
                            color: #333;
                        }
                        .header {
                            background-color: #f0f0f0;
                            padding: 20px;
                            border-radius: 5px;
                            margin-bottom: 20px;
                        }
                        .summary-stats {
                            display: flex;
                            justify-content: space-between;
                            flex-wrap: wrap;
                            gap: 10px;
                        }
                        .stat-box {
                            background-color: #fff;
                            border-radius: 5px;
                            padding: 15px;
                            box-shadow: 0 2px 5px rgba(0,0,0,0.1);
                            flex-basis: 30%;
                            min-width: 200px;
                        }
                        .stat-value {
                            font-size: 24px;
                            font-weight: bold;
                            margin-top: 5px;
                        }
                        table {
                            width: 100%;
                            border-collapse: collapse;
                            margin-top: 20px;
                        }
                        th {
                            background-color: #4a4a4a;
                            color: white;
                            text-align: left;
                            padding: 12px;
                        }
                        td {
                            padding: 10px;
                            border-bottom: 1px solid #ddd;
                            vertical-align: top;
                            width: 20%;
                        }
                        tr:nth-child(even) {
                            background-color: #f9f9f9;
                        }
                        .score {
                            font-weight: bold;
                            padding: 5px 10px;
                            border-radius: 3px;
                            display: inline-block;
                        }
                        .score-high {
                            background-color: #c8e6c9;
                            color: #2e7d32;
                        }
                        .score-medium {
                            background-color: #fff9c4;
                            color: #f57f17;
                        }
                        .score-low {
                            background-color: #ffcdd2;
                            color: #c62828;
                        }
                        .output {
                            overflow: auto;
                            white-space: pre-wrap;
                        }
                        .output pre {
                            background-color: #f5f5f5;
                            border: 1px solid #ddd;
                            border-radius: 4px;
                            padding: 10px;
                            margin: 0;
                            font-family: 'Consolas', 'Monaco', 'Courier New', monospace;
                            font-size: 14px;
                            line-height: 1.4;
                            color: #333;
                            box-shadow: inset 0 1px 3px rgba(0, 0, 0, 0.1);
                            overflow-x: auto;
                            white-space: pre-wrap;
                            word-wrap: break-word;
                        }
                        .score-col {
                            width: 80px;
                        }
                    </style>
                </head>
                <body>
                """);

        html.append("""
                    <div class="header">
                        <h1>Prompt Evaluation Report</h1>
                        <div class="summary-stats">
                            <div class="stat-box">
                                <div>Total Test Cases</div>
                                <div class="stat-value">%d</div>
                            </div>
                            <div class="stat-box">
                                <div>Average Score</div>
                                <div class="stat-value">%.1f / %.0f</div>
                            </div>
                            <div class="stat-box">
                                <div>Pass Rate (&ge;%.0f)</div>
                                <div class="stat-value">%.1f%%</div>
                            </div>
                        </div>
                    </div>

                    <table>
                        <thead>
                            <tr>
                                <th>Scenario</th>
                                <th>Prompt Inputs</th>
                                <th>Solution Criteria</th>
                                <th>Output</th>
                                <th>Score</th>
                                <th>Reasoning</th>
                            </tr>
                        </thead>
                        <tbody>
                """.formatted(totalTests, averageScore, MAX_SCORE, SCORE_THRESHOLD, passRate));

        for (var outcome : testOutcomes) {
            var testCase = outcome.testCase();
            var promptInputs = testCase.promptInputs().entrySet().stream()
                    .map(entry -> "<strong>%s:</strong> %s"
                            .formatted(escapeHtml(entry.getKey()), escapeHtml(entry.getValue())))
                    .reduce((left, right) -> left + "<br>" + right)
                    .orElse("");
            var criteria = testCase.solutionCriteria().stream()
                    .map(ReportGenerator::escapeHtml)
                    .reduce((left, right) -> left + "<br>&bull; " + right)
                    .orElse("");

            html.append("""
                            <tr>
                                <td>%s</td>
                                <td class="prompt-inputs">%s</td>
                                <td class="criteria">&bull; %s</td>
                                <td class="output"><pre>%s</pre></td>
                                <td class="score-col"><span class="score %s">%s</span></td>
                                <td class="reasoning">%s</td>
                            </tr>
                    """.formatted(
                            escapeHtml(testCase.scenario()),
                            promptInputs,
                            criteria,
                            escapeHtml(outcome.output()),
                            scoreClass(outcome.score()),
                            outcome.score() == null ? "n/a" : String.valueOf(outcome.score()),
                            escapeHtml(outcome.reasoning())));
        }

        return html.append("""
                        </tbody>
                    </table>
                </body>
                </html>
                """).toString();
    }

    /** Mean score across the graded outcomes; ungraded outcomes are ignored. */
    public double averageScore(List<TestOutcome> testOutcomes) {
        return testOutcomes.stream()
                .filter(outcome -> outcome.score() != null)
                .mapToDouble(TestOutcome::score)
                .average()
                .orElse(0.0);
    }

    private static String scoreClass(Double score) {
        if (score == null || score <= 5) {
            return "score-low";
        }

        return score >= 8 ? "score-high" : "score-medium";
    }

    private static String escapeHtml(String text) {
        return text == null
                ? ""
                : text.replace("&", "&amp;")
                        .replace("<", "&lt;")
                        .replace(">", "&gt;")
                        .replace("\"", "&quot;");
    }
}
