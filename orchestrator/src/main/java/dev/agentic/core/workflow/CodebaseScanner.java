package dev.agentic.core.workflow;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import java.util.stream.*;

/** Lightweight brownfield reconnaissance: classes, endpoints and entities. Output feeds the impact-analysis agent. */
public final class CodebaseScanner {
    private static final Pattern TYPE = Pattern.compile("\\b(class|interface|record|enum)\\s+(\\w+)");
    private static final Pattern MAPPING = Pattern.compile("@(Get|Post|Put|Delete|Patch|Request)Mapping(\\([^)]*\\))?");
    private CodebaseScanner() {}

    public static String scan(Path root) throws IOException {
        StringBuilder sb = new StringBuilder("# Codebase snapshot: ").append(root).append('\n');
        try (Stream<Path> s = Files.walk(root)) {
            List<Path> files = s.filter(p -> p.toString().endsWith(".java")).sorted().limit(200).toList();
            for (Path f : files) {
                String src = Files.readString(f);
                sb.append("\n## ").append(root.relativize(f)).append('\n');
                Matcher t = TYPE.matcher(src);
                if (t.find()) sb.append("- ").append(t.group(1)).append(' ').append(t.group(2)).append('\n');
                if (src.contains("@Entity")) sb.append("- JPA entity\n");
                Matcher m = MAPPING.matcher(src);
                while (m.find()) sb.append("- endpoint ").append(m.group()).append('\n');
            }
            sb.append("\nFiles scanned: ").append(files.size()).append('\n');
        }
        return sb.toString();
    }
}
