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

/// File-based global memory store: one markdown file per fact, frontmatter
/// metadata, flat directory, no embedding model.
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
/// git-trackable — a plain on-disk memory folder.
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

    /// Hard caps on a single memory entry's size — remember() had no cap at all before this,
    /// so one call could write an arbitrarily large .md file; the explicit `recall` tool appends
    /// each matched entry's FULL content, relying entirely on the generic, tool-agnostic
    /// toolResultMaxChars cut as the only backstop against one huge memory blowing out a turn.
    private static final int MAX_TITLE_CHARS = 200;
    private static final int MAX_CONTENT_CHARS = 8000;

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
        // Redact secrets (API keys / tokens / passwords) BEFORE anything is written: memory files are
        // plaintext and long-lived, and untrusted text (a mod README, a log) can try to get a key
        // persisted here. Redacting at the store layer covers every caller.
        title = redactSecrets(title);
        content = redactSecrets(content != null ? content : "");
        if (title.length() > MAX_TITLE_CHARS) {
            title = title.substring(0, MAX_TITLE_CHARS);
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            content = content.substring(0, MAX_CONTENT_CHARS)
                    + "\n…[truncated — memory content capped at " + MAX_CONTENT_CHARS + " chars]";
        }

        // Dedup: if an existing memory already holds the same (normalized) content, return it instead
        // of writing a near-identical duplicate that would clutter recall.
        Entry existing = findDuplicate(normalize(content));
        if (existing != null) {
            return existing;
        }

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
        Pattern tagsPat = Pattern.compile("tags:\\s*\\[(.*?)\\]");
        Pattern createdPat = Pattern.compile("created:\\s*(.+)");
        String q = query.toLowerCase(Locale.ROOT);
        String t = tag != null ? tag.toLowerCase(Locale.ROOT) : null;
        int limit = Math.min(maxResults > 0 ? maxResults : MAX_RESULTS, MAX_RESULTS);

        // Collect every MATCHING entry first (with its parsed `created` timestamp), then sort
        // newest-first by that timestamp at the end. Sorting by Path/filename (the previous
        // behavior) does not actually correlate with creation order once entries have
        // non-timestamped, title-based filenames (the normal case — see remember()'s `safe` slug),
        // silently breaking the documented "newest first" contract.
        List<Entry> matches = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.toList()) {
                if (!file.getFileName().toString().endsWith(".md")) continue;
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }

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
                matches.add(entry);
            }
        }
        matches.sort(Comparator.comparing(RememberStore::sortKey).reversed());
        return matches.size() > limit ? new ArrayList<>(matches.subList(0, limit)) : matches;
    }

    /// Sort key for recall()'s "newest first" contract: the parsed `created` ISO-instant string
    /// when present (ISO-8601 instants sort correctly as plain strings), falling back to the
    /// file's own name so an entry with a missing/corrupt timestamp still sorts deterministically.
    private static String sortKey(Entry e) {
        String created = e.getCreated();
        if (created != null) {
            return created;
        }
        Path f = e.getFile();
        return f != null ? f.getFileName().toString() : "";
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

    // ---- redaction & dedup ----

    /// Scrubs common secret shapes from text so keys/tokens never land in a plaintext memory file.
    /// Delegates to the shared {@link org.jackhuang.hmcl.ai.util.Redactor} (single source of truth,
    /// also used by the agent trace); keeps this method's null→"" contract for existing callers/tests.
    static String redactSecrets(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return org.jackhuang.hmcl.ai.util.Redactor.redact(s);
    }

    /// Drops a leading Markdown H1 line (`# ...`) — the title heading remember() prepends — so the
    /// remaining text is the fact body. Only the first H1 is removed; the fact's own headings survive.
    private static String stripLeadingH1(@Nullable String s) {
        if (s == null) {
            return "";
        }
        String t = s.stripLeading();
        if (t.startsWith("# ")) {
            int nl = t.indexOf('\n');
            return nl >= 0 ? t.substring(nl + 1).stripLeading() : "";
        }
        return s;
    }

    /// Whitespace/case-insensitive normal form used for duplicate detection.
    private static String normalize(@Nullable String s) {
        if (s == null) {
            return "";
        }
        return s.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /// Returns an existing entry whose body matches {@code normalizedContent}, or null if none.
    @Nullable
    private Entry findDuplicate(String normalizedContent) throws IOException {
        if (normalizedContent.isEmpty() || !Files.isDirectory(dir)) {
            return null;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            for (Path file : stream.filter(f -> f.getFileName().toString().endsWith(".md")).toList()) {
                String text;
                try {
                    text = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    continue;
                }
                Entry e = parseEntry(text, file);
                // parseEntry's content still carries the "# <title>" heading that remember() prepends;
                // strip it so we compare the actual fact body against the incoming (headerless) content.
                if (normalize(stripLeadingH1(e.getContent())).equals(normalizedContent)) {
                    return e;
                }
            }
        }
        return null;
    }

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
