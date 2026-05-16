package com.consilens.ai.conversation.engine;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Loads and indexes example configuration templates from the classpath {@code examples/} resource directory.
 * Also optionally loads from a filesystem path (e.g. the distribution's {@code examples/} folder) so that
 * user-added examples are picked up at runtime.
 * <p>
 * Templates are available for use in planner prompts and as direct template responses to users.
 */
public class ExampleTemplateStore {

    private static final String CLASSPATH_EXAMPLES_DIR = "examples/";

    private final List<ExampleTemplate> templates;

    /**
     * Load from classpath {@code examples/} directory only.
     */
    public ExampleTemplateStore() {
        this((Path) null);
    }

    /**
     * Load from the given filesystem {@code examplesDir} (the install home's {@code examples/} folder).
     * Falls back to classpath resources when the directory is absent or empty (dev/test environments).
     */
    public ExampleTemplateStore(Path extraPath) {
        List<ExampleTemplate> loaded = new ArrayList<>();
        if (extraPath != null && java.nio.file.Files.isDirectory(extraPath)) {
            loaded.addAll(loadFromDirectory(extraPath, Collections.emptyList()));
        }
        if (loaded.isEmpty()) {
            // Fallback: classpath bundled examples (for dev/test or single-jar deployments)
            loaded.addAll(loadFromClasspath());
        }
        this.templates = Collections.unmodifiableList(loaded);
    }

    /**
     * For testing: provide a fixed list.
     */
    public ExampleTemplateStore(List<ExampleTemplate> templates) {
        this.templates = Collections.unmodifiableList(new ArrayList<>(templates));
    }

    public List<ExampleTemplate> getAll() {
        return templates;
    }

    public boolean isEmpty() {
        return templates.isEmpty();
    }

    /**
     * Find the best matching example for a user query (by scanning names, titles, types).
     */
    public Optional<ExampleTemplate> findBestMatch(String query) {
        if (query == null || query.isBlank() || templates.isEmpty()) {
            return Optional.empty();
        }
        String lower = query.toLowerCase();
        // Exact name match first
        Optional<ExampleTemplate> byName = templates.stream()
                .filter(t -> lower.contains(t.getName().toLowerCase()))
                .findFirst();
        if (byName.isPresent()) {
            return byName;
        }
        // Score by keyword overlap
        return templates.stream()
                .map(t -> Map.entry(t, score(t, lower)))
                .filter(e -> e.getValue() > 0)
                .max(Map.Entry.comparingByValue())
                .map(Map.Entry::getKey);
    }

    /**
     * Build a compact listing suitable for injection into the planner system prompt.
     */
    public String buildSummaryForPrompt() {
        if (templates.isEmpty()) {
            return "";
        }
        return "Available example configurations (use name to reference in your answer or as YAML basis):\n"
                + templates.stream()
                        .map(ExampleTemplate::toSummaryLine)
                        .collect(Collectors.joining("\n"));
    }

    // -----------------------------------------------------------------------

    private List<ExampleTemplate> loadFromClasspath() {
        List<ExampleTemplate> result = new ArrayList<>();
        try {
            Enumeration<URL> resources = Thread.currentThread().getContextClassLoader()
                    .getResources(CLASSPATH_EXAMPLES_DIR);
            while (resources.hasMoreElements()) {
                URL url = resources.nextElement();
                String protocol = url.getProtocol();
                if ("file".equals(protocol)) {
                    Path dir = Path.of(url.toURI());
                    if (Files.isDirectory(dir)) {
                        Files.list(dir)
                                .filter(p -> p.getFileName().toString().endsWith(".yaml")
                                        || p.getFileName().toString().endsWith(".yml"))
                                .sorted()
                                .forEach(p -> {
                                    try {
                                        String content = Files.readString(p, StandardCharsets.UTF_8);
                                        String name = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                                        result.add(parse(name, content));
                                    } catch (Exception ignored) {
                                    }
                                });
                    }
                } else if ("jar".equals(protocol)) {
                    // Inside a JAR: scan via ClassLoader resource listing is unreliable for directories.
                    // Fall back to a known list from a generated index (see below) or use the jar URL directly.
                    loadFromJarUrl(url, result);
                }
            }
        } catch (Exception ignored) {
        }
        // If classpath loading found nothing (common in plain-JAR mode), try the hardcoded names list
        if (result.isEmpty()) {
            loadKnownNames(result);
        }
        return result;
    }

    private void loadFromJarUrl(URL directoryUrl, List<ExampleTemplate> result) {
        // Try to read an index file that lists the available example names
        loadKnownNames(result);
    }

    /** Load each well-known example by name directly from ClassLoader. */
    private void loadKnownNames(List<ExampleTemplate> result) {
        String[] knownNames = {
                "minimal-mysql-to-pg",
                "mapped-mysql-to-postgres-checksum",
                "custom-sql-mysql-vs-postgres-checksum",
                "detail-to-aggregate-custom-sql",
                "large-table-mysql-to-starrocks",
                "mysql-to-doris-partitioned-checksum",
                "performance-test-mysql-vs-postgres",
                "performance-test-mysql-vs-postgres-exclude",
                "performance-test-mysql-vs-postgres-output-postgres",
                "performance-test-mysql-vs-starrocks",
                "same-db-mysql-comparison",
        };
        // Avoid duplicates from earlier classpath scan
        Map<String, Boolean> seen = new LinkedHashMap<>();
        result.forEach(t -> seen.put(t.getName(), Boolean.TRUE));
        for (String name : knownNames) {
            if (seen.containsKey(name)) {
                continue;
            }
            try (InputStream in = Thread.currentThread().getContextClassLoader()
                    .getResourceAsStream(CLASSPATH_EXAMPLES_DIR + name + ".yaml")) {
                if (in != null) {
                    String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    result.add(parse(name, content));
                }
            } catch (Exception ignored) {
            }
        }
    }

    private List<ExampleTemplate> loadFromDirectory(Path dir, List<ExampleTemplate> existing) {
        Map<String, Boolean> existingNames = new LinkedHashMap<>();
        existing.forEach(t -> existingNames.put(t.getName(), Boolean.TRUE));
        List<ExampleTemplate> extras = new ArrayList<>();
        try {
            Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".yaml")
                            || p.getFileName().toString().endsWith(".yml"))
                    .sorted()
                    .forEach(p -> {
                        String name = p.getFileName().toString().replaceFirst("\\.[^.]+$", "");
                        if (existingNames.containsKey(name)) {
                            return;
                        }
                        try {
                            String content = Files.readString(p, StandardCharsets.UTF_8);
                            extras.add(parse(name, content));
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception ignored) {
        }
        return extras;
    }

    private ExampleTemplate parse(String name, String content) {
        String title = "";
        StringBuilder descBuilder = new StringBuilder();
        String sourceType = null;
        String targetType = null;
        String resourceType = "table";
        int commentLines = 0;
        for (String line : content.split("\n")) {
            String stripped = line.stripLeading();
            if (stripped.startsWith("#")) {
                String commentText = stripped.substring(1).strip();
                if (commentLines == 0) {
                    title = commentText;
                } else if (commentLines < 3 && !commentText.isBlank()) {
                    if (descBuilder.length() > 0) {
                        descBuilder.append(" ");
                    }
                    descBuilder.append(commentText);
                }
                commentLines++;
            } else if (!stripped.isBlank()) {
                // Parse YAML key-value for type/resource detection
                if (sourceType == null && stripped.startsWith("type:") && content.contains("source:")) {
                    // Will be resolved below
                }
            }
        }
        // Simple regex-free extraction: find source.type and target.type
        sourceType = extractNestedType(content, "source:");
        targetType = extractNestedType(content, "target:");
        if (content.contains("type: sql") || content.contains("type: \"sql\"")) {
            resourceType = "sql";
        }
        return new ExampleTemplate(name, title, descBuilder.toString().trim(),
                sourceType, targetType, resourceType, content);
    }

    private String extractNestedType(String content, String section) {
        int sectionIdx = content.indexOf(section);
        if (sectionIdx < 0) {
            return null;
        }
        int typeIdx = content.indexOf("  type:", sectionIdx);
        if (typeIdx < 0 || typeIdx - sectionIdx > 200) {
            return null;
        }
        int lineEnd = content.indexOf('\n', typeIdx);
        String typeLine = lineEnd < 0 ? content.substring(typeIdx) : content.substring(typeIdx, lineEnd);
        String value = typeLine.replace("type:", "").trim();
        return value.isEmpty() ? null : value;
    }

    private int score(ExampleTemplate t, String lowerQuery) {
        int s = 0;
        if (t.getSourceType() != null && lowerQuery.contains(t.getSourceType())) s += 3;
        if (t.getTargetType() != null && lowerQuery.contains(t.getTargetType())) s += 3;
        if (t.getResourceType() != null && lowerQuery.contains(t.getResourceType())) s += 2;
        if (t.getTitle() != null) {
            for (String word : t.getTitle().split("[\\s\\-_]+")) {
                if (word.length() > 2 && lowerQuery.contains(word.toLowerCase())) {
                    s++;
                }
            }
        }
        if (lowerQuery.contains("large") && t.getName().contains("large")) s += 5;
        // aggregate / detail-to-aggregate scenarios (Chinese and English terms)
        boolean queryIsAggregate = lowerQuery.contains("aggregate") || lowerQuery.contains("汇总")
                || lowerQuery.contains("聚合") || lowerQuery.contains("明细") || lowerQuery.contains("detail");
        if (queryIsAggregate && t.getName().contains("aggregate")) s += 5;
        if (lowerQuery.contains("same") && t.getName().contains("same-db")) s += 5;
        if (lowerQuery.contains("minimal") && t.getName().contains("minimal")) s += 5;
        if (lowerQuery.contains("partition") && (lowerQuery.contains("分区") || lowerQuery.contains("partition"))
                && t.getName().contains("partition")) s += 5;
        if (lowerQuery.contains("performance") || lowerQuery.contains("性能")) {
            if (t.getName().contains("performance")) s += 5;
        }
        if (lowerQuery.contains("doris") && t.getName().contains("doris")) s += 5;
        if (lowerQuery.contains("starrocks") && t.getName().contains("starrocks")) s += 5;
        if ((lowerQuery.contains("sql") || lowerQuery.contains("自定义")) && t.getName().contains("custom-sql")) s += 3;
        if ((lowerQuery.contains("map") || lowerQuery.contains("mapping") || lowerQuery.contains("字段映射"))
                && t.getName().contains("mapped")) s += 3;
        return s;
    }
}
