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
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
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
import org.unlaxer.CodePointIndex;
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
            List.of("valid", "invalid", "function", "number", "operator"),
            List.of()
        ));
        capabilities.setSemanticTokensProvider(semanticTokensOptions);

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

        DocumentState state = new DocumentState(uri, content, parseResult);
        documents.put(uri, state);

        context.close();

        // Publish diagnostics
        if (client != null) {
            publishDiagnostics(uri, content, parseResult);
        }

        return parseResult;
    }

    /**
     * Publish diagnostics (errors) to the client.
     */
    private void publishDiagnostics(String uri, String content, ParseResult result) {
        List<Diagnostic> diagnostics = new ArrayList<>();

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
     * Convert character offset to LSP Position.
     */
    private Position offsetToPosition(String content, int offset) {
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

        public DocumentState(String uri, String content, ParseResult parseResult) {
            this.uri = uri;
            this.content = content;
            this.parseResult = parseResult;
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
         */
        private List<Integer> buildSemanticTokens(String content, ParseResult result) {
            List<Integer> data = new ArrayList<>();

            if (content.isEmpty()) {
                return data;
            }

            int validEnd = result.consumedLength;

            // Valid portion (green)
            if (validEnd > 0) {
                // deltaLine=0, deltaStart=0, length=validEnd, tokenType=0 (valid), modifiers=0
                data.add(0);
                data.add(0);
                data.add(validEnd);
                data.add(0); // valid
                data.add(0);
            }

            // Invalid portion (red)
            if (validEnd < content.length()) {
                int invalidLength = content.length() - validEnd;
                // deltaLine=0, deltaStart=validEnd (relative to previous), length, tokenType=1 (invalid)
                data.add(0);
                data.add(validEnd);
                data.add(invalidLength);
                data.add(1); // invalid
                data.add(0);
            }

            return data;
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
