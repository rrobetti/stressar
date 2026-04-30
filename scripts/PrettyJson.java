import java.io.*;
import java.nio.file.*;

/// Reads a JSON file (or stdin) and writes it to stdout with 2-space indentation.
///
/// Usage:
///   java scripts/PrettyJson.java path/to/file.json
///   cat file.json | java scripts/PrettyJson.java
///
/// Requires Java 26+ (JEP 495 – Simple Source Files and Instance Main Methods).
void main(String[] args) throws Exception {
    String json = args.length > 0
            ? Files.readString(Path.of(args[0]))
            : new String(System.in.readAllBytes());
    System.out.println(format(json.strip()));
}

String format(String json) {
    var sb = new StringBuilder();
    int indent = 0;
    boolean inString = false;
    char prev = 0;
    for (int i = 0; i < json.length(); i++) {
        char c = json.charAt(i);
        if (c == '"' && prev != '\\') {
            inString = !inString;
            sb.append(c);
        } else if (!inString) {
            switch (c) {
                case '{', '[' -> {
                    sb.append(c);
                    sb.append('\n');
                    sb.append("  ".repeat(++indent));
                }
                case '}', ']' -> {
                    sb.append('\n');
                    sb.append("  ".repeat(--indent));
                    sb.append(c);
                }
                case ',' -> {
                    sb.append(c);
                    sb.append('\n');
                    sb.append("  ".repeat(indent));
                }
                case ':' -> sb.append(": ");
                default -> {
                    if (c != ' ' && c != '\n' && c != '\r' && c != '\t') sb.append(c);
                }
            }
        } else {
            sb.append(c);
        }
        prev = c;
    }
    return sb.toString();
}
