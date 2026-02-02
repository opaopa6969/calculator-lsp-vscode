package org.unlaxer.calculator;

import java.util.List;

import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;

/**
 * Provides completion suggestions based on document content and cursor position.
 */
public interface SuggestableParser {

    List<String> getTriggerCharacters();

    List<Suggestion> suggest(String content, Position position);

    record Suggestion(
            String label,
            String detail,
            CompletionItemKind kind,
            String insertText,
            InsertTextFormat insertTextFormat
    ) {

        public static Suggestion functionSnippet(String label, String detail, String snippet) {
            return new Suggestion(label, detail, CompletionItemKind.Function, snippet, InsertTextFormat.Snippet);
        }
    }
}
