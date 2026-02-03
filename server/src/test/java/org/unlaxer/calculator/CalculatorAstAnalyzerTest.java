package org.unlaxer.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class CalculatorAstAnalyzerTest {

    @Test
    public void evaluatesExpressionWithOperatorPrecedence() {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///test.calc";

        server.parseDocument(uri, "1+2*3");
        CalculatorLanguageServer.DocumentState state = server.getDocuments().get(uri);

        assertTrue(state.analysis.errors().isEmpty());
        assertNotNull(state.analysis.value());
        assertEquals(7.0d, state.analysis.value(), 0.0001d);
    }

    @Test
    public void reportsMissingRightOperand() {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///missing.calc";

        server.parseDocument(uri, "1+");
        CalculatorLanguageServer.DocumentState state = server.getDocuments().get(uri);

        assertTrue(false == state.analysis.errors().isEmpty());
        assertTrue(state.analysis.value() == null);
    }

    @Test
    public void reportsUnclosedParenthesis() {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///paren.calc";

        server.parseDocument(uri, "(1+2");
        CalculatorLanguageServer.DocumentState state = server.getDocuments().get(uri);

        assertTrue(false == state.analysis.errors().isEmpty());
        assertTrue(state.analysis.value() == null);
    }
}
