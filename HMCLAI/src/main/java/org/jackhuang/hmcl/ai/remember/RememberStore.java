package org.jackhuang.hmcl.ai.remember;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// File-based global memory store (hermes style: one markdown file per fact,
/// frontmatter metadata, flat directory, no embedding model).
///
/// Layout: `{memDir}/` contains `.md` files each with YAML-like frontmatter:
/// ```markdown
/// ---
/// tags: [java, crash]
/// created: 2026-06-29T10:00:00
/// ---
/// # Title
/// Memory content in markdown.
/// ```
///
/// Recall (search) is done via full-text grep over the directory; no vector DB.
/// Each fact is a self-contained `.md` file so it's human-browsable and
/// git-trackable. This mirrors what claude-code's `memory/` directory does.
@NotNullByDefault
public final class RememberStore {

    /// A single memory entry in the store.
    public static final class Entry {
        @Nullable private String title;
        @Nullable private List<String> tags;
        @Nullable private String created;
        @Nullable private String updated;
        @Nullable private String content;
        @Nullable private Path file;

        public Entry() {}

        public Entry(String title, List<String> tags, String content) {
            this.title = title;
            this.tags = tags;
            this.content = content;
        }

        @Nullable public String getTitle() { return title; }
        public void setTitle(@Nullable String title) { this.title = title; }
        @Nullable public List<String> getTags() { return tags; }
        public void setTags(@Nullable List<String> tags) { this.tags = tags; }
        @Nullable public String getCreated() { return created; }
        public void setCreated(@Nullable String created) { this.created = created; }
        @Nullable public String getUpdated() { return updated; }
        public void setUpdated(@Nullable String updated) { this.updated = updated; }
        @Nullable public String getContent() { return content; }
        public void setContent(@Nullable String content) { this.content = content; }
        @Nullable public Path getFile() { return file; }
        public void setFile(@Nullable Path file) { this.file = file; }
    }

    private static final int MAX_RESULTS = 50;

    private final Path dir;

    public RememberStore(Path dir) {
        this.dir = dir;
    }

    /// Ensures the directory exists.
    public void init() throws IOException {
        Files.createDirectories(dir);
    }

    /// Returns the directory path.
    public Path getDir() {
        return dir;
    }

    // ---- CRUD ----

    /// Stores a new fact. Creates one `.md` file with frontmatter.
    ///
    /// @param title   short slug/title (used as filename stem)
    /// @param tags    optional tags for later filtering
    /// @param content the fact body in markdown
    /// @return the created entry
    /// @throws IOException on I/O error
    public Entry remember(String title, List<String> tags, String content) throws IOException {
        String now = java.time.Instant.now().toString();
        String safe = title.replaceAll("[\\\\/:*?\"<>|]", "-").trim().toLowerCase(Locale.ROOT);
        if (safe.isEmpty()) safe = "memory";
        Path file = uniquePath(dir.resolve(safe + ".md"));

        String frontTags = tags != null && !tags.isEmpty()
                ? "[" + tags.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", ")) + "]"
                : "[]";

        String body = "---\n"
                + "title: \"" + title + "\"\n"
                + "tags: " + frontTags + "\n"
                + "created: " + now + "\n"
                + "---\n\n"
                + "# " + title + "\n\n"
                + (content != null ? content : "");

        Files.writeString(file, body, StandardCharsets.UTF_8);

        Entry entry = new Entry(title, tags, content);
        entry.setCreated(now);
        entry.setFile(file);
        return entry;
    }

    /// Recalls (searches) memories matching the given query.
    ///
    /// @param query free-text query; matched against title, tags and content (case-insensitive contains)
    /// @param tag   optional tag filter; if non-null, only entries with this tag are returned
    /// @param maxResults maximum results to return
    /// @return matching entries, newest first
    /// @throws IOException on I/O error
    public List<Entry> recall(String query, @Nullable String tag, int maxResults) throws IOException {
        Pattern titlePat = Pattern.compile("title:\\s*\"(.+?)\"");
        Pattern tagsPat = Pattern.compile("tags:\\s*\\[(.*?)\\]");
        Pattern createdPat = Pattern.compile("created:\\s*(.+)");
        String q = query.toLowerCase(Locale.ROOT);
        String t = tag != null ? tag.toLowerCase(Locale.ROOT) : null;
        int limit = Math.min(maxResults > 0 ? maxResults : MAX_RESULTS, MAX_RESULTS);

        List<Entry> results = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.sorted(Comparator.reverseOrder()).toList()) {
                if (!file.getFileName().toString().endsWith(".md")) continue;
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }

                String title = "";
                java.util.regex.Matcher tm = titlePat.matcher(text);
                if (tm.find()) title = tm.group(1);

                List<String> fileTags = new ArrayList<>();
                java.util.regex.Matcher tgm = tagsPat.matcher(text);
                if (tgm.find()) {
                    String raw = tgm.group(1).trim();
                    if (!raw.isEmpty()) {
                        for (String item : raw.split(",")) {
                            String cleaned = item.trim().replaceAll("^\"|\"$", "");
                            if (!cleaned.isEmpty()) fileTags.add(cleaned);
                        }
                    }
                }

                // Tag filter
                if (t != null && fileTags.stream().noneMatch(ft -> ft.toLowerCase(Locale.ROOT).contains(t))) {
                    continue;
                }

                // Content match
                if (!q.isEmpty() && !text.toLowerCase(Locale.ROOT).contains(q)) {
                    continue;
                }

                String created = "";
                java.util.regex.Matcher cm = createdPat.matcher(text);
                if (cm.find()) created = cm.group(1).trim();

                Entry entry = parseEntry(text, file);
                entry.setFile(file);
                entry.setCreated(created.isEmpty() ? null : created);
                results.add(entry);
                if (results.size() >= limit) break;
            }
        }
        return results;
    }

    /// Lists all entries, newest first.
    public List<Entry> listAll() throws IOException {
        return recall("", null, MAX_RESULTS);
    }

    /// Deletes the memory file with the given filename stem.
    ///
    /// @param stem the filename without `.md` extension
    /// @return true if deleted
    /// @throws IOException on I/O error
    public boolean forget(String stem) throws IOException {
        Path file = dir.resolve(stem.endsWith(".md") ? stem : stem + ".md");
        return Files.deleteIfExists(file);
    }

    /// Reads a single entry by filename stem.
    @Nullable
    public Entry get(String stem) throws IOException {
        Path file = dir.resolve(stem.endsWith(".md") ? stem : stem + ".md");
        if (!Files.exists(file)) return null;
        String text = Files.readString(file, StandardCharsets.UTF_8);
        Entry entry = parseEntry(text, file);
        entry.setFile(file);
        return entry;
    }

    // ---- helpers ----

    private static Path uniquePath(Path candidate) {
        if (!Files.exists(candidate)) return candidate;
        String base = candidate.getFileName().toString().replaceAll("\\.md$", "");
        for (int i = 1; i < 1000; i++) {
            Path alt = candidate.resolveSibling(base + "-" + i + ".md");
            if (!Files.exists(alt)) return alt;
        }
        return candidate.resolveSibling(base + "-" + UUID.randomUUID() + ".md");
    }

    private static Entry parseEntry(String text, Path file) {
        Entry entry = new Entry();
        // Parse frontmatter title
        Pattern titlePat = Pattern.compile("title:\\s*\"(.+?)\"");
        java.util.regex.Matcher tm = titlePat.matcher(text);
        if (tm.find()) entry.setTitle(tm.group(1));

        // Tags
        Pattern tagsPat = Pattern.compile("tags:\\s*\\[(.*?)\\]");
        java.util.regex.Matcher tgm = tagsPat.matcher(text);
        if (tgm.find()) {
            String raw = tgm.group(1).trim();
            List<String> parsed = new ArrayList<>();
            if (!raw.isEmpty()) {
                for (String item : raw.split(",")) {
                    String cleaned = item.trim().replaceAll("^\"|\"$", "");
                    if (!cleaned.isEmpty()) parsed.add(cleaned);
                }
            }
            entry.setTags(parsed);
        }

        // Split frontmatter from body
        String body = text;
        int close = text.indexOf("\n---\n");
        if (close > 0 && text.startsWith("---")) {
            body = text.substring(close + 5).trim();
        }
        entry.setContent(body);
        entry.setFile(file);
        return entry;
    }
}
