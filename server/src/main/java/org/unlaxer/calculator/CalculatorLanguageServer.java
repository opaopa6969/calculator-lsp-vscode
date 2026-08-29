package org.unlaxer.calculator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionItemKind;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.CodeLens;
import org.eclipse.lsp4j.CodeLensOptions;
import org.eclipse.lsp4j.CodeLensParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.MarkupContent;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InsertTextFormat;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.SetTraceParams;
import org.eclipse.lsp4j.SemanticTokens;
import org.eclipse.lsp4j.SemanticTokensLegend;
import org.eclipse.lsp4j.SemanticTokensParams;
import org.eclipse.lsp4j.SemanticTokensWithRegistrationOptions;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import org.unlaxer.Parsed;
import org.unlaxer.StringSource;
import org.unlaxer.context.ParseContext;
import org.unlaxer.parser.Parser;

/**
 * LSP server for calculator expressions.
 * Provides:
 * - Auto-completion for functions (sin, sqrt, cos, tan)
 * - Syntax validation with highlighting (valid=green, invalid=red)
 */
public class CalculatorLanguageServer implements LanguageServer, LanguageClientAware {

    private LanguageClient client;
    private final Map<String, DocumentState> documents = new HashMap<>();
    private final SuggestableParser suggestableParser = new CalculatorSuggestableParser();
    private final CalculatorAstAnalyzer astAnalyzer = new CalculatorAstAnalyzer();
    private final CalculatorTextDocumentService textDocumentService;

    public CalculatorLanguageServer() {
        this.textDocumentService = new CalculatorTextDocumentService(this);
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        ServerCapabilities capabilities = new ServerCapabilities();

        // Text document sync
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Full);

        // Completion support
        CompletionOptions completionOptions = new CompletionOptions();
        completionOptions.setTriggerCharacters(this.suggestableParser.getTriggerCharacters());
        completionOptions.setResolveProvider(false);
        capabilities.setCompletionProvider(completionOptions);

        // Semantic tokens for syntax highlighting
        SemanticTokensWithRegistrationOptions semanticTokensOptions =
            new SemanticTokensWithRegistrationOptions();
        semanticTokensOptions.setFull(true);
        semanticTokensOptions.setLegend(new SemanticTokensLegend(
            List.of("valid", "invalid"),
            List.of()
        ));
        capabilities.setSemanticTokensProvider(semanticTokensOptions);

        // Hover support
        capabilities.setHoverProvider(true);

        // CodeLens support
        CodeLensOptions codeLensOptions = new CodeLensOptions();
        codeLensOptions.setResolveProvider(false);
        capabilities.setCodeLensProvider(codeLensOptions);

        return CompletableFuture.completedFuture(new InitializeResult(capabilities));
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        return CompletableFuture.completedFuture(null);
    }

    @Override
    public void exit() {
        // Clean up
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return textDocumentService;
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return new CalculatorWorkspaceService();
    }

    @Override
    public void connect(LanguageClient client) {
        this.client = client;
    }

    public LanguageClient getClient() {
        return client;
    }

    public Map<String, DocumentState> getDocuments() {
        return documents;
    }

    /**
     * Parse document and update state.
     */
    public ParseResult parseDocument(String uri, String content) {
        Parser parser = CalculatorParsers.getRootParser();
        ParseContext context = new ParseContext(StringSource.createRootSource(content));

        Parsed result = parser.parse(context);

        int consumedLength = 0;
        if (result.isSucceeded()) {
            consumedLength = result.getConsumed().source.sourceAsString().length();
        }

        ParseResult parseResult = new ParseResult(
            result.isSucceeded(),
            consumedLength,
            content.length(),
            result
        );

        CalculatorAstAnalyzer.AnalysisResult analysis = astAnalyzer.analyze(content, parseResult);
        DocumentState state = new DocumentState(uri, content, parseResult, analysis);
        documents.put(uri, state);

        context.close();

        // Publish diagnostics
        if (client != null) {
            publishDiagnostics(state);
        }

        return parseResult;
    }

    /**
     * Publish diagnostics (errors) to the client.
     */
    private void publishDiagnostics(DocumentState state) {
        List<Diagnostic> diagnostics = new ArrayList<>();

        List<CalculatorAstAnalyzer.AstError> astErrors = state.analysis.errors();
        for (CalculatorAstAnalyzer.AstError astError : astErrors) {
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setRange(astError.range());
            diagnostic.setSeverity(DiagnosticSeverity.Error);
            diagnostic.setMessage(astError.message());
            diagnostic.setSource("calculator");
            diagnostics.add(diagnostic);
        }

        ParseResult result = state.parseResult;
        String content = state.content;
        String uri = state.uri;

        if (result.consumedLength < result.totalLength) {
            // Part of the input is invalid
            int errorStart = result.consumedLength;
            int errorEnd = result.totalLength;

            Position startPos = offsetToPosition(content, errorStart);
            Position endPos = offsetToPosition(content, errorEnd);

            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setRange(new Range(startPos, endPos));
            diagnostic.setSeverity(DiagnosticSeverity.Error);
            diagnostic.setMessage("Invalid expression: unexpected characters" + createParseFailureHint(result));
            diagnostic.setSource("calculator");
            diagnostics.add(diagnostic);
        } else if (false == result.succeeded && result.totalLength > 0) {
            // Entire input is invalid
            Diagnostic diagnostic = new Diagnostic();
            diagnostic.setRange(new Range(
                new Position(0, 0),
                offsetToPosition(content, content.length())
            ));
            diagnostic.setSeverity(DiagnosticSeverity.Error);
            diagnostic.setMessage("Invalid expression" + createParseFailureHint(result));
            diagnostic.setSource("calculator");
            diagnostics.add(diagnostic);
        }

        client.publishDiagnostics(new PublishDiagnosticsParams(uri, diagnostics));
    }

    
    private String createParseFailureHint(ParseResult result) {
        if (result.parsed == null) {
            return "";
        }
        List<String> expected = tryExtractExpectedTokens(result.parsed);
        if (expected.isEmpty()) {
            return "";
        }
        String joined = String.join(", ", expected);
        return " Expected: " + joined;
    }

    private List<String> tryExtractExpectedTokens(Parsed parsed) {
        try {
            // Common pattern: parsed.getErrors() -> List<Error>, where Error has getExpected()
            java.lang.reflect.Method getErrors = parsed.getClass().getMethod("getErrors");
            Object errorsObject = getErrors.invoke(parsed);
            if (errorsObject instanceof List) {
                List<?> errors = (List<?>) errorsObject;
                List<String> tokens = new ArrayList<>();
                for (Object error : errors) {
                    if (error == null) {
                        continue;
                    }
                    tokens.addAll(tryExtractExpectedFromError(error));
                }
                return tokens.stream().distinct().toList();
            }
        } catch (ReflectiveOperationException ignored) {
            // ignore
        }

        try {
            java.lang.reflect.Method getExpected = parsed.getClass().getMethod("getExpected");
            Object expectedObject = getExpected.invoke(parsed);
            return normalizeExpected(expectedObject);
        } catch (ReflectiveOperationException ignored) {
            // ignore
        }

        try {
            java.lang.reflect.Method expectedTokens = parsed.getClass().getMethod("expectedTokens");
            Object expectedObject = expectedTokens.invoke(parsed);
            return normalizeExpected(expectedObject);
        } catch (ReflectiveOperationException ignored) {
            // ignore
        }

        return List.of();
    }

    private List<String> tryExtractExpectedFromError(Object error) {
        try {
            java.lang.reflect.Method getExpected = error.getClass().getMethod("getExpected");
            Object expectedObject = getExpected.invoke(error);
            return normalizeExpected(expectedObject);
        } catch (ReflectiveOperationException ignored) {
            return List.of();
        }
    }

    private List<String> normalizeExpected(Object expectedObject) {
        if (expectedObject == null) {
            return List.of();
        }
        if (expectedObject instanceof List) {
            List<?> values = (List<?>) expectedObject;
            List<String> result = new ArrayList<>();
            for (Object value : values) {
                if (value == null) {
                    continue;
                }
                result.add(String.valueOf(value));
            }
            return result;
        }
        return List.of(String.valueOf(expectedObject));
    }

    /**
     * Check if a position is inside the range.
     */
    private static boolean isPositionInRange(Position position, Range range) {
        if (position.getLine() < range.getStart().getLine()) {
            return false;
        }
        if (position.getLine() > range.getEnd().getLine()) {
            return false;
        }
        if (position.getLine() == range.getStart().getLine()
                && position.getCharacter() < range.getStart().getCharacter()) {
            return false;
        }
        if (position.getLine() == range.getEnd().getLine()
                && position.getCharacter() > range.getEnd().getCharacter()) {
            return false;
        }
        return true;
    }

    /**
     * Convert character offset to LSP Position.
     */
    private static Position offsetToPosition(String content, int offset) {
        int line = 0;
        int column = 0;
        for (int i = 0; i < offset && i < content.length(); i++) {
            if (content.charAt(i) == '\n') {
                line++;
                column = 0;
            } else {
                column++;
            }
        }
        return new Position(line, column);
    }

    /**
     * Document state holder.
     */
    public static class DocumentState {
        public final String uri;
        public final String content;
        public final ParseResult parseResult;
        public final CalculatorAstAnalyzer.AnalysisResult analysis;

        public DocumentState(String uri, String content, ParseResult parseResult,
                CalculatorAstAnalyzer.AnalysisResult analysis) {
            this.uri = uri;
            this.content = content;
            this.parseResult = parseResult;
            this.analysis = analysis;
        }
    }

    /**
     * Parse result holder.
     */
    public static class ParseResult {
        public final boolean succeeded;
        public final int consumedLength;
        public final int totalLength;
        public final Parsed parsed;

        public ParseResult(boolean succeeded, int consumedLength, int totalLength, Parsed parsed) {
            this.succeeded = succeeded;
            this.consumedLength = consumedLength;
            this.totalLength = totalLength;
            this.parsed = parsed;
        }

        public boolean isFullyValid() {
            return succeeded && consumedLength == totalLength;
        }
    }

    /**
     * Text document service implementation.
     */
    public static class CalculatorTextDocumentService implements TextDocumentService {

        private final CalculatorLanguageServer server;

        public CalculatorTextDocumentService(CalculatorLanguageServer server) {
            this.server = server;
        }

        @Override
        public void didOpen(DidOpenTextDocumentParams params) {
            String uri = params.getTextDocument().getUri();
            String content = params.getTextDocument().getText();
            server.parseDocument(uri, content);
        }

        @Override
        public void didChange(DidChangeTextDocumentParams params) {
            String uri = params.getTextDocument().getUri();
            String content = params.getContentChanges().get(0).getText();
            server.parseDocument(uri, content);
        }

        @Override
        public void didClose(DidCloseTextDocumentParams params) {
            server.getDocuments().remove(params.getTextDocument().getUri());
        }

        @Override
        public void didSave(DidSaveTextDocumentParams params) {
            // No special handling needed
        }

        @Override
        public CompletableFuture<Hover> hover(HoverParams params) {
            String uri = params.getTextDocument().getUri();
            Position position = params.getPosition();

            DocumentState state = server.getDocuments().get(uri);
            if (state == null) {
                return CompletableFuture.completedFuture(null);
            }

            String hoverText = null;
            for (CalculatorAstAnalyzer.AstError error : state.analysis.errors()) {
                if (isPositionInRange(position, error.range())) {
                    hoverText = error.message();
                    break;
                }
            }

            if (hoverText == null && state.analysis.hasValue()) {
                hoverText = "= " + state.analysis.value();
            }

            if (hoverText == null) {
                return CompletableFuture.completedFuture(null);
            }

            MarkupContent content = new MarkupContent();
            content.setKind("plaintext");
            content.setValue(hoverText);
            Hover hover = new Hover(content);
            return CompletableFuture.completedFuture(hover);
        }

        @Override
        public CompletableFuture<List<? extends CodeLens>> codeLens(CodeLensParams params) {
            String uri = params.getTextDocument().getUri();
            DocumentState state = server.getDocuments().get(uri);
            if (state == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            if (state.content.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            String title = null;
            if (false == state.analysis.errors().isEmpty()) {
                title = "Error: " + state.analysis.errors().get(0).message();
            } else if (state.analysis.hasValue()) {
                title = "= " + state.analysis.value();
            }

            if (title == null) {
                return CompletableFuture.completedFuture(Collections.emptyList());
            }

            Range range = new Range(new Position(0, 0), new Position(0, 0));
            Command command = new Command(title, "calculator.showResult");
            CodeLens lens = new CodeLens(range);
            lens.setCommand(command);
            return CompletableFuture.completedFuture(List.of(lens));
        }

        @Override
        public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(
                CompletionParams params) {

            String uri = params.getTextDocument().getUri();
            Position position = params.getPosition();

            DocumentState state = server.getDocuments().get(uri);
            if (state == null) {
                return CompletableFuture.completedFuture(Either.forLeft(Collections.emptyList()));
            }

            List<CompletionItem> items = getCompletionItems(state.content, position);
            return CompletableFuture.completedFuture(Either.forLeft(items));
        }

        /**
         * Get completion items based on current position.
         */
        private List<CompletionItem> getCompletionItems(String content, Position position) {
            List<CompletionItem> items = new ArrayList<>();

            // Get the text before cursor
            int offset = positionToOffset(content, position);
            String textBefore = content.substring(0, offset);

            // Find the start of current word
            int wordStart = offset;
            while (wordStart > 0 && Character.isLetter(content.charAt(wordStart - 1))) {
                wordStart--;
            }
            String currentWord = content.substring(wordStart, offset).toLowerCase();

            // Function completions
            List<CalculatorParsers.FunctionCompletion> functions =
                    CalculatorParsers.getFunctionCompletions();

            for (CalculatorParsers.FunctionCompletion func : functions) {
                if (func.name().startsWith(currentWord)) {
                    CompletionItem item = new CompletionItem(func.name());
                    item.setKind(CompletionItemKind.Function);
                    item.setDetail(func.description());
                    item.setInsertText(func.insertText());
                    item.setInsertTextFormat(InsertTextFormat.Snippet);
                    items.add(item);
                }
            }

            return items;
        }

        /**
         * Convert LSP Position to character offset.
         */
        private int positionToOffset(String content, Position position) {
            int offset = 0;
            int line = 0;
            int column = 0;

            while (offset < content.length()) {
                if (line == position.getLine() && column == position.getCharacter()) {
                    return offset;
                }
                if (content.charAt(offset) == '\n') {
                    line++;
                    column = 0;
                } else {
                    column++;
                }
                offset++;
            }

            return offset;
        }

        @Override
        public CompletableFuture<SemanticTokens> semanticTokensFull(SemanticTokensParams params) {
            String uri = params.getTextDocument().getUri();
            DocumentState state = server.getDocuments().get(uri);

            if (state == null) {
                return CompletableFuture.completedFuture(new SemanticTokens(Collections.emptyList()));
            }

            List<Integer> data = buildSemanticTokens(state.content, state.parseResult);
            return CompletableFuture.completedFuture(new SemanticTokens(data));
        }

        /**
         * Build semantic tokens data.
         * Format: [deltaLine, deltaStart, length, tokenType, tokenModifiers]
         * tokenType: 0=valid, 1=invalid
         *
         * <p>Per the LSP specification, a token must not span multiple lines.
         * A span that crosses a newline is split into one token per line.
         * deltaLine and deltaStart are relative to the previous emitted token.</p>
         */
        private List<Integer> buildSemanticTokens(String content, ParseResult result) {
            List<Integer> data = new ArrayList<>();

            if (content.isEmpty()) {
                return data;
            }

            int validEnd = result.consumedLength;
            int prevLine = 0;
            int prevChar = 0;

            // Valid portion (green)
            if (validEnd > 0) {
                int[] tail = appendSpanTokens(data, content, 0, validEnd, 0, prevLine, prevChar);
                prevLine = tail[0];
                prevChar = tail[1];
            }

            // Invalid portion (red)
            if (validEnd < content.length()) {
                appendSpanTokens(data, content, validEnd, content.length(), 1, prevLine, prevChar);
            }

            return data;
        }

        /**
         * Emit one or more 5-int tokens for a [start, end) character span of the
         * given tokenType, splitting at newlines. Newline characters themselves
         * are not emitted as tokens (they carry no highlight).
         *
         * @return int[2] = { line of last emitted token, char offset after last
         *                   emitted token on that line (for chaining) }
         */
        private static int[] appendSpanTokens(List<Integer> data, String content,
                int start, int end, int tokenType, int prevLine, int prevChar) {
            int line = prevLine;

            int segStart = start;
            // Skip a leading newline so a span that starts with '\n' does not
            // emit a zero-length token; it simply begins on the next line.
            while (segStart < end && isLineBreak(content.charAt(segStart))) {
                segStart++;
            }
            if (segStart >= end) {
                return new int[] { line, prevChar };
            }
            Position segStartPos = offsetToPosition(content, segStart);
            int segStartLine = segStartPos.getLine();
            int segStartChar = segStartPos.getCharacter();

            for (int i = segStart; i < end; i++) {
                boolean newline = isLineBreak(content.charAt(i));
                int next = i + 1;
                if (newline || next == end) {
                    int segEnd = newline ? i : next;
                    int len = segEnd - segStart;
                    if (len > 0) {
                        int deltaLine = segStartLine - line;
                        int deltaStart = (deltaLine == 0)
                                ? (segStartChar - prevChar)
                                : segStartChar;
                        data.add(deltaLine);
                        data.add(deltaStart);
                        data.add(len);
                        data.add(tokenType);
                        data.add(0);
                        line = segStartLine;
                        prevChar = segStartChar + len;
                    }
                    if (newline) {
                        segStart = next;
                        while (segStart < end && isLineBreak(content.charAt(segStart))) {
                            segStart++;
                        }
                        if (segStart < end) {
                            segStartPos = offsetToPosition(content, segStart);
                            segStartLine = segStartPos.getLine();
                            segStartChar = segStartPos.getCharacter();
                        }
                    }
                }
            }
            return new int[] { line, prevChar };
        }

        private static boolean isLineBreak(char c) {
            return c == '\n' || c == '\r';
        }
    }

    /**
     * Workspace service implementation.
     */
    public static class CalculatorWorkspaceService implements WorkspaceService {
        @Override
        public void didChangeConfiguration(org.eclipse.lsp4j.DidChangeConfigurationParams params) {
        }

        @Override
        public void didChangeWatchedFiles(org.eclipse.lsp4j.DidChangeWatchedFilesParams params) {
        }
    }


@Override
public void setTrace(SetTraceParams params) {
    // VS Code sends $/setTrace notifications; ignoring is sufficient.
}
}
