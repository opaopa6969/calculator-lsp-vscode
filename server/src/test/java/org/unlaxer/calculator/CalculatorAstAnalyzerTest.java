package org.unlaxer.calculator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.Token;
import org.unlaxer.TokenKind;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.elementary.MappedSingleCharacterParser;
import org.unlaxer.parser.elementary.WordParser;

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

    @Test
    public void reportsMissingOperandForOperatorSequence() {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///sequence.calc";

        server.parseDocument(uri, "1+*2");
        CalculatorLanguageServer.DocumentState state = server.getDocuments().get(uri);

        assertTrue(false == state.analysis.errors().isEmpty());
        assertTrue(state.analysis.errors().stream()
                .anyMatch(error -> error.message().contains("右辺のない二項演算子")));
    }

    @Test
    public void reportsUnmatchedClosingParenthesis() {
        CalculatorLanguageServer server = new CalculatorLanguageServer();
        String uri = "file:///closing.calc";

        server.parseDocument(uri, ")1");
        CalculatorLanguageServer.DocumentState state = server.getDocuments().get(uri);

        assertTrue(false == state.analysis.errors().isEmpty());
        assertTrue(state.analysis.errors().stream()
                .anyMatch(error -> error.message().contains("閉じ括弧に対応する開き括弧がありません")));
    }

    @Test
    public void reportsNumberParseFailure() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token token = createNumberToken("abc");

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("abc", token);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("abc", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("数値を解析できません")));
    }

    @Test
    public void reportsDivisionByZero() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token left = createNumberToken("10");
        Token right = createNumberToken("0");
        Token division = createOperatorToken(new CalculatorParsers.DivisionParser(), left, right);

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("10/0", division);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("10/0", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("0 で除算できません")));
    }

    @Test
    public void reportsUnknownBinaryOperator() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token left = createNumberToken("1");
        Token right = createNumberToken("2");
        Token operator = createOperatorToken(new UnknownOperatorParser(), left, right);

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("1?2", operator);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("1?2", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("不明な二項演算子")));
    }

    @Test
    public void reportsUnknownFunction() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token operand = createNumberToken("1");
        Token function = createOperatorToken(new UnknownFunctionParser(), operand);

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("mystery(1)", function);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("mystery(1)", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("不明な関数")));
    }

    @Test
    public void reportsNegativeSquareRoot() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token operand = createNumberToken("-1");
        Token function = createOperatorToken(new CalculatorParsers.SquareRootFunctionParser(), operand);

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("sqrt(-1)", function);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("sqrt(-1)", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("負の数の平方根は計算できません")));
    }

    @Test
    public void reportsNonPositiveLogarithm() {
        CalculatorAstAnalyzer analyzer = new CalculatorAstAnalyzer();
        Token operand = createNumberToken("0");
        Token function = createOperatorToken(new CalculatorParsers.LogarithmFunctionParser(), operand);

        CalculatorLanguageServer.ParseResult parseResult = createParseResult("log(0)", function);
        CalculatorAstAnalyzer.AnalysisResult result = analyzer.analyze("log(0)", parseResult);

        assertTrue(false == result.errors().isEmpty());
        assertTrue(result.errors().stream()
                .anyMatch(error -> error.message().contains("0 以下の対数は計算できません")));
    }

    private CalculatorLanguageServer.ParseResult createParseResult(String content, Token token) {
        Parsed parsed = new Parsed(token);
        return new CalculatorLanguageServer.ParseResult(true, content.length(), content.length(), parsed);
    }

    private Token createNumberToken(String value) {
        StringSource source = StringSource.createRootSource(value);
        Parser parser = new CalculatorParsers.NumberParser();
        return new Token(TokenKind.consumed, source, parser);
    }

    private Token createOperatorToken(Parser parser, Token... children) {
        return new Token(TokenKind.consumed, new org.unlaxer.TokenList(children), parser);
    }

    private static final class UnknownOperatorParser extends MappedSingleCharacterParser {
        public UnknownOperatorParser() {
            super('?');
        }
    }

    private static final class UnknownFunctionParser extends WordParser implements CalculatorParsers.FunctionSuggestable {
        public UnknownFunctionParser() {
            super("mystery");
        }

        @Override
        public CalculatorParsers.FunctionCompletion getFunctionCompletion() {
            return new CalculatorParsers.FunctionCompletion(
                    "mystery",
                    "Unknown function",
                    "mystery($1)"
            );
        }
    }
}
