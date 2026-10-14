package dev.agentic.core.policy;

import java.util.*;
import java.util.regex.*;

/** Multi-file output convention: blocks introduced by a line "=== FILE: relative/path ===". */
public final class Bundles {
    private static final Pattern MARK = Pattern.compile("(?m)^=== FILE: (.+?) ===\\s*$");
    private Bundles() {}

    public static Map<String, String> split(String content) {
        Map<String, String> files = new LinkedHashMap<>();
        Matcher m = MARK.matcher(content);
        String path = null; int bodyStart = 0;
        while (m.find()) {
            if (path != null) files.put(path, content.substring(bodyStart, m.start()).strip() + "\n");
            path = m.group(1); bodyStart = m.end();
        }
        if (path != null) files.put(path, content.substring(bodyStart).strip() + "\n");
        return files;
    }
}
