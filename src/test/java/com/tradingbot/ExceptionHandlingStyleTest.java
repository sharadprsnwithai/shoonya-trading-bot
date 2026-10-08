package com.tradingbot;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Guard test for exception-handling conventions in {@code src/main/java}.
 *
 * <p>Fails the build when a new empty catch block (i.e. a swallowed exception that is neither
 * logged nor documented) or a {@code printStackTrace()} / {@code System.out|err} call is
 * introduced.
 */
class ExceptionHandlingStyleTest {

    /**
     * Source files allowed to contain empty catch blocks — each holds a deliberate, documented
     * format/parse-probe fallback where logging every miss would be noise.
     */
    private static final Set<String> ALLOWED_EMPTY_CATCH_FILES =
            Set.of(
                    "com/tradingbot/controller/LowestVolumeStrategyController.java",
                    "com/tradingbot/marketdata/ShoonyaMarketDataService.java",
                    "com/tradingbot/service/LowestVolumeReversalService.java",
                    "com/tradingbot/strategy/car/gtt/ZerodhaKiteGttGateway.java");

    private static final Pattern CATCH_HEADER =
            Pattern.compile("catch\\s*\\(\\s*(?:final\\s+)?[\\w.]+\\s+\\w+\\s*\\)\\s*\\{");

    private static final Pattern FORBIDDEN =
            Pattern.compile("printStackTrace\\s*\\(|System\\.(out|err)\\b");

    @Test
    void noSwallowedExceptionsInMainSources() throws IOException {
        Path main = mainSources();
        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(main)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String rel = main.relativize(file).toString().replace('\\', '/');
                String source = Files.readString(file);
                String cleaned = stripCommentsAndStrings(source);

                Matcher forbidden = FORBIDDEN.matcher(cleaned);
                while (forbidden.find()) {
                    violations.add(
                            rel
                                    + ":"
                                    + (lineNumberOf(cleaned, forbidden.start()) + 1)
                                    + " forbidden "
                                    + forbidden.group()
                                    + " — use the logger instead");
                }

                Matcher catchHeader = CATCH_HEADER.matcher(cleaned);
                while (catchHeader.find()) {
                    int bodyStart = catchHeader.end() - 1;
                    int bodyEnd = matchingBrace(cleaned, bodyStart);
                    if (bodyEnd < 0) {
                        continue;
                    }
                    boolean empty = cleaned.substring(bodyStart + 1, bodyEnd).isBlank();
                    if (empty && !ALLOWED_EMPTY_CATCH_FILES.contains(rel)) {
                        violations.add(
                                rel
                                        + ":"
                                        + (lineNumberOf(cleaned, catchHeader.start()) + 1)
                                        + " empty catch block — log the exception or document"
                                        + " the fallback in code");
                    }
                }
            }
        }
        assertTrue(
                violations.isEmpty(),
                () -> "Exception-handling violations found:\n" + String.join("\n", violations));
    }

    /** Locates {@code src/main/java} starting from the working directory, walking upwards. */
    private static Path mainSources() {
        Path dir = Path.of("").toAbsolutePath();
        for (int i = 0; i < 6 && dir != null; i++) {
            Path candidate = dir.resolve("src").resolve("main").resolve("java");
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "Could not locate src/main/java from " + Path.of("").toAbsolutePath());
    }

    /**
     * Returns a copy of {@code source} of identical length where every comment and string/char
     * literal is replaced by spaces (newlines preserved), so regex matches map to the original line
     * numbers.
     */
    static String stripCommentsAndStrings(String source) {
        char[] out = source.toCharArray();
        int i = 0;
        int n = out.length;
        while (i < n) {
            char c = out[i];
            if (c == '/' && i + 1 < n && out[i + 1] == '/') {
                while (i < n && out[i] != '\n') {
                    out[i++] = ' ';
                }
            } else if (c == '/' && i + 1 < n && out[i + 1] == '*') {
                while (i < n && !(out[i] == '*' && i + 1 < n && out[i + 1] == '/')) {
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                    if (i < n) {
                        out[i++] = ' ';
                    }
                }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out[i++] = ' ';
                while (i < n && out[i] != quote) {
                    if (out[i] == '\\') {
                        out[i] = ' ';
                        if (i + 1 < n && out[i + 1] != '\n') {
                            out[i + 1] = ' ';
                        }
                        i += 2;
                        continue;
                    }
                    if (out[i] != '\n') {
                        out[i] = ' ';
                    }
                    i++;
                }
                if (i < n) {
                    out[i++] = ' ';
                }
            } else {
                i++;
            }
        }
        return new String(out);
    }

    /** Returns the index of the brace matching the one at {@code openIndex}, or {@code -1}. */
    private static int matchingBrace(String source, int openIndex) {
        int depth = 0;
        for (int i = openIndex; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return i;
                }
            }
        }
        return -1;
    }

    private static int lineNumberOf(String source, int offset) {
        int line = 0;
        for (int i = 0; i < offset && i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
