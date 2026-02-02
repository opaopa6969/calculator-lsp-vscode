# Calculator DSL (LSP) - VS Code Extension

This is a minimal VS Code extension that launches a Java LSP server (LSP4J) via stdio.

## What is included
- VS Code client (TypeScript) using `vscode-languageclient`
- Minimal syntax highlighting for `.calc`
- A Maven module (`server/`) that builds a runnable jar for the LSP server
- A helper script that copies the built jar into `server-dist/`

## Quick start (dev)
1. Install prerequisites:
   - Node.js (LTS)
   - Java 21+
   - Maven

2. Install extension dependencies:
   ```bash
   npm install
   ```

3. Build the language server jar and copy it into the extension:
   ```bash
   npm run build:server
   ```

4. In VS Code, press `F5` to start the Extension Development Host.

5. Create a file `demo.calc` and type expressions like:
   ```
   1 + 2 * (3 + 4)
   ```

## Packaging to .vsix
```bash
npm run package
```

## Configuration
- `calculatorLsp.server.javaPath`: path to java executable (default: `java`)
- `calculatorLsp.server.jarPath`: optional path to an external server jar. If empty, uses the bundled jar in this extension.
- `calculatorLsp.server.jvmArgs`: extra JVM args (e.g. `-Xmx512m`)

## Notes for WSL / Windows
- If you develop in WSL but run VS Code on Windows, prefer launching the server jar with a Windows-side Java, or set `calculatorLsp.server.jarPath` to a jar reachable from Windows.


## Architecture (one picture)
![architecture](docs/architecture.png)

## Quick try (no build of the server required for users)
If you already have the `.vsix`, install it via:
- Extensions view → `...` → **Install from VSIX...**

Then open a `.calc` file. The extension will launch the bundled server jar via stdio.

## Development workflow
```bash
npm install
npm run build:server
npm run compile
# VS Code: Run and Debug → "Run Extension" (F5)
```

## Adding a new function (one place)
Functions are defined once in `server/src/main/java/.../CalculatorParsers.java`:

```java
public static List<FunctionCompletion> getFunctionCompletions() {
    return List.of(
        new FunctionCompletion("sin", "Sine function", "sin($1)"),
        // ...
        new FunctionCompletion("log", "Natural logarithm function", "log($1)")
    );
}
```

That single list drives:
- Grammar (`FunctionNameParser`)
- Completion (`SuggestableParser`)
- Documentation

## Diagnostics: show expected tokens
When parsing fails, the server publishes diagnostics. If unlaxer provides “expected tokens” metadata, the server adds it to the diagnostic message (best-effort extraction).

