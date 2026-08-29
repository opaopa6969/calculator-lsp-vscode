package org.unlaxer.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.junit.jupiter.api.Test;

public class CalculatorSemanticTokensTest {

    private List<Integer> tokensFor(String content) {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///test.calc";
        server.parseDocument(uri, content);

        CalculatorLanguageServer.CalculatorTextDocumentService service =
                (CalculatorLanguageServer.CalculatorTextDocumentService) server.getTextDocumentService();

        SemanticTokensParams params = new SemanticTokensParams();
        params.setTextDocument(new TextDocumentIdentifier(uri));

        CompletableFuture<SemanticTokens> future = service.semanticTokensFull(params);
        SemanticTokens tokens = future.join();
        return tokens.getData();
    }

    @Test
    public void singleLineValidProducesOneValidToken() {
        List<Integer> data = tokensFor("1+2");
        assertEquals(List.of(0, 0, 3, 0, 0), data);
    }

    @Test
    public void multiLineValidProducesTokensOnEachLine() {
        List<Integer> data = tokensFor("1+2\n3+4");

        // The calculator grammar parses a single expression, so only "1+2"
        // (line 0) is consumed as valid; "\n3+4" is the invalid tail.
        // [0,0,3,valid,0]   (line 0: "1+2")
        // [1,0,3,invalid,0] (line 1: "3+4", after skipping the newline)
        assertEquals(List.of(0, 0, 3, 0, 0, 1, 0, 3, 1, 0), data);
    }

    @Test
    public void multiLineWithInvalidPlacesInvalidOnSecondLine() {
        List<Integer> data = tokensFor("1+2\nabc");

        // [0,0,3,valid,0]  (line 0: "1+2")
        // [1,0,3,invalid,0] (line 1: "abc")
        assertEquals(List.of(0, 0, 3, 0, 0, 1, 0, 3, 1, 0), data);
    }

    @Test
    public void completelyInvalidSingleLine() {
        List<Integer> data = tokensFor("abc");
        // whole content is invalid
        assertTrue(data.size() == 5);
        assertEquals(List.of(0, 0, 3, 1, 0), data);
    }

    @Test
    public void emptyDocumentProducesNoTokens() {
        List<Integer> data = tokensFor("");
        assertTrue(data.isEmpty());
    }
}
