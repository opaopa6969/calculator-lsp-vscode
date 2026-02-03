package org.unlaxer.calculator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.Range;
import org.unlaxer.Token;
import org.unlaxer.TokenList;
import org.unlaxer.ast.ASTMapper;
import org.unlaxer.ast.ASTMapperContext;
import org.unlaxer.parser.Parser;
import org.unlaxer.parser.ascii.MinusParser;
import org.unlaxer.parser.ascii.PlusParser;
import org.unlaxer.parser.combinator.ZeroOrMore;
import org.unlaxer.parser.elementary.MultipleParser;

public final class CalculatorAstAnalyzer {

    public AnalysisResult analyze(String content, CalculatorLanguageServer.ParseResult parseResult) {
        List<AstError> errors = new ArrayList<>();
        errors.addAll(findParenthesisErrors(content));
        errors.addAll(findMissingOperandErrors(content));

        Token astRoot = null;
        Double value = null;

        if (parseResult != null
                && parseResult.parsed != null
                && parseResult.isFullyValid()
                && errors.isEmpty()) {
            Token rootToken = parseResult.parsed.getRootToken();
            ASTMapperContext context = ASTMapperContext.create(new CalculatorAstMapper());
            Token mapped = context.toAST(rootToken);
            astRoot = mapped;
            value = evaluate(mapped, content, errors);
        }

        if (false == errors.isEmpty()) {
            value = null;
        }

        return new AnalysisResult(errors, astRoot, value);
    }

    private List<AstError> findParenthesisErrors(String content) {
        List<AstError> errors = new ArrayList<>();
        Deque<Integer> stack = new ArrayDeque<>();
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '(') {
                stack.push(index);
            } else if (current == ')') {
                if (stack.isEmpty()) {
                    errors.add(new AstError(toRange(content, index, index + 1),
                            "閉じ括弧に対応する開き括弧がありません"));
                } else {
                    stack.pop();
                }
            }
        }

        while (false == stack.isEmpty()) {
            int index = stack.pop();
            errors.add(new AstError(toRange(content, index, index + 1),
                    "開き括弧が閉じられていません"));
        }

        return errors;
    }

    private List<AstError> findMissingOperandErrors(String content) {
        List<AstError> errors = new ArrayList<>();
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (false == isBinaryOperator(current)) {
                continue;
            }

            int previous = findPreviousNonSpaceIndex(content, index - 1);
            if (previous < 0) {
                continue;
            }

            char previousChar = content.charAt(previous);
            if (false == isOperandEnd(previousChar)) {
                continue;
            }

            int next = findNextNonSpaceIndex(content, index + 1);
            if (next < 0) {
                errors.add(new AstError(toRange(content, index, index + 1),
                        "右辺のない二項演算子: " + current));
                continue;
            }

            char nextChar = content.charAt(next);
            if (nextChar == ')' || nextChar == '*' || nextChar == '/') {
                errors.add(new AstError(toRange(content, index, index + 1),
                        "右辺のない二項演算子: " + current));
            }
        }

        return errors;
    }

    private int findPreviousNonSpaceIndex(String content, int index) {
        int current = index;
        while (current >= 0) {
            if (false == Character.isWhitespace(content.charAt(current))) {
                return current;
            }
            current--;
        }
        return -1;
    }

    private int findNextNonSpaceIndex(String content, int index) {
        int current = index;
        while (current < content.length()) {
            if (false == Character.isWhitespace(content.charAt(current))) {
                return current;
            }
            current++;
        }
        return -1;
    }

    private boolean isBinaryOperator(char current) {
        return current == '+' || current == '-' || current == '*' || current == '/';
    }

    private boolean isOperandEnd(char current) {
        return Character.isDigit(current) || current == ')' || Character.isLetter(current) || current == '.';
    }

    private Double evaluate(Token astRoot, String content, List<AstError> errors) {
        if (astRoot == null) {
            return null;
        }

        if (astRoot.getParser().getClass() == CalculatorParsers.NumberParser.class) {
            return parseNumber(astRoot, content, errors);
        }

        TokenList children = astRoot.getAstNodeChildren();
        if (children.isEmpty()) {
            return null;
        }

        if (children.size() == 1) {
            Double operand = evaluate(children.get(0), content, errors);
            if (operand == null) {
                return null;
            }
            if (astRoot.getParser() instanceof PlusParser) {
                return operand;
            }
            if (astRoot.getParser() instanceof MinusParser) {
                return -operand;
            }
            if (astRoot.getParser() instanceof CalculatorParsers.FunctionSuggestable) {
                CalculatorParsers.FunctionSuggestable function =
                        (CalculatorParsers.FunctionSuggestable) astRoot.getParser();
                return evaluateFunction(function.getFunctionCompletion().name(), operand, astRoot, content, errors);
            }
            return operand;
        }

        if (children.size() == 2) {
            Double left = evaluate(children.get(0), content, errors);
            Double right = evaluate(children.get(1), content, errors);
            if (left == null || right == null) {
                return null;
            }
            return evaluateBinary(astRoot, left, right, content, errors);
        }

        Double aggregated = evaluate(children.get(0), content, errors);
        for (int index = 1; index < children.size(); index++) {
            Double right = evaluate(children.get(index), content, errors);
            if (aggregated == null || right == null) {
                return null;
            }
            aggregated = evaluateBinary(astRoot, aggregated, right, content, errors);
        }
        return aggregated;
    }

    private Double parseNumber(Token token, String content, List<AstError> errors) {
        if (token.getParser().getClass() != CalculatorParsers.NumberParser.class) {
            return null;
        }
        try {
            return Double.parseDouble(token.getSource().sourceAsString());
        } catch (NumberFormatException ex) {
            errors.add(new AstError(toRange(content, token), "数値を解析できません"));
            return null;
        }
    }

    private Double evaluateBinary(Token operator, Double left, Double right, String content, List<AstError> errors) {
        if (operator.getParser() instanceof PlusParser) {
            return left + right;
        }
        if (operator.getParser() instanceof MinusParser) {
            return left - right;
        }
        if (operator.getParser() instanceof MultipleParser) {
            return left * right;
        }
        if (operator.getParser() instanceof CalculatorParsers.DivisionParser) {
            if (right == 0.0d) {
                errors.add(new AstError(toRange(content, operator), "0 で除算できません"));
                return null;
            }
            return left / right;
        }

        errors.add(new AstError(toRange(content, operator), "不明な二項演算子"));
        return null;
    }

    private Double evaluateFunction(String name, Double operand, Token token, String content, List<AstError> errors) {
        if (name == null) {
            errors.add(new AstError(toRange(content, token), "不明な関数"));
            return null;
        }
        switch (name) {
            case "sin":
                return Math.sin(operand);
            case "sqrt":
                if (operand < 0.0d) {
                    errors.add(new AstError(toRange(content, token), "負の数の平方根は計算できません"));
                    return null;
                }
                return Math.sqrt(operand);
            case "cos":
                return Math.cos(operand);
            case "tan":
                return Math.tan(operand);
            case "log":
                if (operand <= 0.0d) {
                    errors.add(new AstError(toRange(content, token), "0 以下の対数は計算できません"));
                    return null;
                }
                return Math.log(operand);
            default:
                errors.add(new AstError(toRange(content, token), "不明な関数: " + name));
                return null;
        }
    }

    private Range toRange(String content, Token token) {
        int start = token.getSource().cursorRange().startIndexInclusive.position().value();
        int end = token.getSource().cursorRange().endIndexExclusive.position().value();
        return toRange(content, start, end);
    }

    private Range toRange(String content, int startOffset, int endOffset) {
        Position start = offsetToPosition(content, startOffset);
        Position end = offsetToPosition(content, endOffset);
        return new Range(start, end);
    }

    private Position offsetToPosition(String content, int offset) {
        int line = 0;
        int column = 0;
        for (int index = 0; index < offset && index < content.length(); index++) {
            if (content.charAt(index) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    public record AstError(Range range, String message) {}

    public record AnalysisResult(List<AstError> errors, Token astRoot, Double value) {
        public boolean hasValue() {
            return value != null;
        }
    }

    private static final class CalculatorAstMapper implements ASTMapper {

        @Override
        public boolean canASTMapping(Token parsedToken) {
            Class<? extends Parser> parserClass = parsedToken.getParser().getClass();
            return parserClass == CalculatorParsers.ExprParser.class
                    || parserClass == CalculatorParsers.TermParser.class
                    || parserClass == CalculatorParsers.UnaryParser.class
                    || parserClass == CalculatorParsers.FunctionParser.class
                    || parserClass == CalculatorParsers.ParenExprParser.class
                    || parserClass == CalculatorParsers.FactorParser.class;
        }

        @Override
        public Token toAST(ASTMapperContext context, Token parsedToken) {
            Class<? extends Parser> parserClass = parsedToken.getParser().getClass();
            if (parserClass == CalculatorParsers.ExprParser.class) {
                return buildBinaryAst(context, parsedToken, CalculatorParsers.TermParser.class);
            }
            if (parserClass == CalculatorParsers.TermParser.class) {
                return buildBinaryAst(context, parsedToken, CalculatorParsers.FactorParser.class);
            }
            if (parserClass == CalculatorParsers.UnaryParser.class) {
                return buildUnaryAst(context, parsedToken);
            }
            if (parserClass == CalculatorParsers.FunctionParser.class) {
                return buildFunctionAst(context, parsedToken);
            }
            if (parserClass == CalculatorParsers.ParenExprParser.class) {
                Token exprToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.ExprParser.class);
                if (exprToken == null) {
                    return parsedToken;
                }
                return context.toAST(exprToken);
            }
            if (parserClass == CalculatorParsers.FactorParser.class) {
                return buildFactorAst(context, parsedToken);
            }
            return parsedToken;
        }

        private Token buildBinaryAst(ASTMapperContext context, Token parsedToken,
                Class<? extends Parser> operandClass) {
            Token leftToken = findDirectChildByParserClass(parsedToken, operandClass);
            if (leftToken == null) {
                return parsedToken;
            }

            Token leftAst = context.toAST(leftToken);
            Token zeroOrMoreToken = findDirectChildByParserClass(parsedToken, ZeroOrMore.class);
            if (zeroOrMoreToken == null) {
                return leftAst;
            }

            Token current = leftAst;
            for (Token chainToken : zeroOrMoreToken.getAstNodeChildren()) {
                Token operatorToken = findOperatorToken(chainToken);
                Token rightToken = findFirstTokenByParserClass(chainToken, operandClass);
                if (operatorToken == null || rightToken == null) {
                    continue;
                }
                Token rightAst = context.toAST(rightToken);
                current = operatorToken.newCreatesOf(current, rightAst);
            }
            return current;
        }

        private Token buildUnaryAst(ASTMapperContext context, Token parsedToken) {
            Token operatorToken = findOperatorToken(parsedToken);
            Token operandToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.FactorParser.class);
            if (operatorToken == null || operandToken == null) {
                return parsedToken;
            }
            Token operandAst = context.toAST(operandToken);
            return operatorToken.newCreatesOf(operandAst);
        }

        private Token buildFunctionAst(ASTMapperContext context, Token parsedToken) {
            Token functionToken = findFirstFunctionToken(parsedToken);
            Token argumentToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.ExprParser.class);
            if (functionToken == null || argumentToken == null) {
                return parsedToken;
            }
            Token argumentAst = context.toAST(argumentToken);
            return functionToken.newCreatesOf(argumentAst);
        }

        private Token buildFactorAst(ASTMapperContext context, Token parsedToken) {
            Token functionToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.FunctionParser.class);
            if (functionToken != null) {
                return context.toAST(functionToken);
            }
            Token unaryToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.UnaryParser.class);
            if (unaryToken != null) {
                return context.toAST(unaryToken);
            }
            Token numberToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.NumberParser.class);
            if (numberToken != null) {
                return numberToken;
            }
            Token parenToken = findFirstTokenByParserClass(parsedToken, CalculatorParsers.ParenExprParser.class);
            if (parenToken != null) {
                return context.toAST(parenToken);
            }
            return parsedToken;
        }

        private Token findDirectChildByParserClass(Token token, Class<? extends Parser> parserClass) {
            for (Token child : token.getAstNodeChildren()) {
                if (child.getParser().getClass() == parserClass) {
                    return child;
                }
            }
            return null;
        }

        private Token findFirstTokenByParserClass(Token token, Class<? extends Parser> parserClass) {
            if (token.getParser().getClass() == parserClass) {
                return token;
            }
            for (Token child : token.getAstNodeChildren()) {
                Token found = findFirstTokenByParserClass(child, parserClass);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }

        private Token findOperatorToken(Token token) {
            Token operator = findFirstTokenByParserClass(token, PlusParser.class);
            if (operator != null) {
                return operator;
            }
            operator = findFirstTokenByParserClass(token, MinusParser.class);
            if (operator != null) {
                return operator;
            }
            operator = findFirstTokenByParserClass(token, MultipleParser.class);
            if (operator != null) {
                return operator;
            }
            return findFirstTokenByParserClass(token, CalculatorParsers.DivisionParser.class);
        }

        private Token findFirstFunctionToken(Token token) {
            if (token.getParser() instanceof CalculatorParsers.FunctionSuggestable) {
                return token;
            }
            for (Token child : token.getAstNodeChildren()) {
                Token found = findFirstFunctionToken(child);
                if (found != null) {
                    return found;
                }
            }
            return null;
        }
    }
}
