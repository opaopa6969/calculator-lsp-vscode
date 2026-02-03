package org.unlaxer.calculator;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.eclipse.lsp4j.Position;
import org.junit.jupiter.api.Test;

public class CalculatorSuggestableParserTest {

    @Test
    public void getTriggerCharactersIncludesFunctionInitials() {
        CalculatorSuggestableParser parser = new CalculatorSuggestableParser();
        List<String> triggers = parser.getTriggerCharacters();

        assertTrue(triggers.contains("s"));
        assertTrue(triggers.contains("c"));
        assertTrue(triggers.contains("t"));
        assertTrue(triggers.contains("l"));
    }

    @Test
    public void suggestReturnsMatchingFunctionsForPrefix() {
        CalculatorSuggestableParser parser = new CalculatorSuggestableParser();
        Position position = new Position(0, 1);

        List<SuggestableParser.Suggestion> suggestions = parser.suggest("s", position);
        Set<String> labels = suggestions.stream()
                .map(SuggestableParser.Suggestion::label)
                .collect(Collectors.toSet());

        assertTrue(labels.contains("sin"));
        assertTrue(labels.contains("sqrt"));
    }

    @Test
    public void suggestReturnsLogForPrefixLo() {
        CalculatorSuggestableParser parser = new CalculatorSuggestableParser();
        Position position = new Position(0, 2);

        List<SuggestableParser.Suggestion> suggestions = parser.suggest("lo", position);
        Set<String> labels = suggestions.stream()
                .map(SuggestableParser.Suggestion::label)
                .collect(Collectors.toSet());

        assertTrue(labels.contains("log"));
    }
}
