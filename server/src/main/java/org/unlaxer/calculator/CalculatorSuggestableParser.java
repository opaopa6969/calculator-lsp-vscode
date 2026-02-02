package org.unlaxer.calculator;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.lsp4j.Position;

/**
 * Suggestion provider for the calculator DSL.
 *
 * <p>This implementation suggests known function names based on the current word
 * (letters directly before the cursor). Function definitions are sourced from {@link CalculatorParsers}.</p>
 */
public final class CalculatorSuggestableParser implements SuggestableParser {

    @Override
    public List<String> getTriggerCharacters() {
        return CalculatorParsers.getFunctionCompletions()
                .stream()
                .map(CalculatorParsers.FunctionCompletion::name)
                .filter(name -> false == name.isEmpty())
                .map(name -> name.substring(0, 1))
                .distinct()
                .toList();
    }

    @Override
    public List<Suggestion> suggest(String content, Position position) {
        int offset = positionToOffset(content, position);
        if (offset < 0) {
            return List.of();
        }
        if (offset > content.length()) {
            return List.of();
        }

        int wordStart = findWordStart(content, offset);
        String currentWord = content.substring(wordStart, offset).toLowerCase();

        List<Suggestion> suggestions = new ArrayList<>();
        for (CalculatorParsers.FunctionCompletion function : CalculatorParsers.getFunctionCompletions()) {
            if (function.name().startsWith(currentWord)) {
                suggestions.add(Suggestion.functionSnippet(
                        function.name(),
                        function.description(),
                        function.insertText()
                ));
            }
        }

        return suggestions;
    }

    private static int findWordStart(String content, int offset) {
        int wordStart = offset;
        while (wordStart > 0) {
            char previous = content.charAt(wordStart - 1);
            if (false == Character.isLetter(previous)) {
                break;
            }
            wordStart--;
        }
        return wordStart;
    }

    private static int positionToOffset(String content, Position position) {
        int offset = 0;
        int line = 0;
        int column = 0;

        int targetLine = position.getLine();
        int targetColumn = position.getCharacter();

        while (offset < content.length()) {
            if (line == targetLine && column == targetColumn) {
                return offset;
            }

            char current = content.charAt(offset);
            if (current == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
            offset++;
        }

        if (line == targetLine && column == targetColumn) {
            return offset;
        }

        return -1;
    }
}
