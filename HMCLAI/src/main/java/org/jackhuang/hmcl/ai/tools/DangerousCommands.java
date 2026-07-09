package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// Heuristic detector for destructive / irreversible shell commands that the AI
/// agent should not run without explicit user confirmation. Conservative by
/// design: a false positive only costs one confirmation prompt.
///
/// # Encoding-bypass hardening
///
/// Raw regex scanning is trivially defeated by base64-encoded payloads — most
/// notably PowerShell's `-EncodedCommand <base64>` (where the base64 decodes to
/// **UTF-16LE** text), but also `-enc` / `-e` aliases and long bare base64 blobs
/// fed to `[Convert]::FromBase64String(...) | iex` or `base64 -d | sh`. Before
/// concluding a command is safe, {@link #scanEncodedPayloads} decodes any such
/// payload and re-runs the danger matcher on the decoded text. An explicit
/// encoding wrapper whose payload cannot be safely decoded is treated as
/// dangerous (fail-closed). The same helper is reused by {@link CriticalOperations}.
@NotNullByDefault
public final class DangerousCommands {

    private DangerousCommands() {
    }

    /// PowerShell indirect-invocation constructs: assembling the cmdlet/verb via a variable fed to
    /// the call operator (`& $v`), a parenthesized expression fed to the call operator (`& (...)`),
    /// `Invoke-Expression`/`iex`, string concatenation of quoted literals (`'Remo'+'ve-Item'`), or the
    /// `-f` format operator applied to a quoted template (`'{0}{1}' -f 'Remove','-Item'`). Every one
    /// of these lets a verb be assembled so that no dangerous verb ever appears contiguously in the
    /// source text, evading every verb-matching regex below. Resolving what these expressions
    /// actually evaluate to needs a real PowerShell parser/interpreter, which is out of scope — so,
    /// mirroring the existing fail-closed handling of an undecodable `-EncodedCommand` payload, any
    /// command containing one of these constructs is treated as dangerous outright.
    private static final Pattern POWERSHELL_INDIRECT_INVOCATION = Pattern.compile(
            "(?i)(?:&\\s*\\$)"
                    + "|(?:&\\s*\\()"
                    + "|(?:\\b(?:iex|invoke-expression)\\b)"
                    + "|(?:['\"][^'\"\\r\\n]{0,200}['\"]\\s*\\+\\s*['\"])"
                    + "|(?:['\"][^'\"\\r\\n]{0,200}['\"]\\s*-f\\b)");

    /// Package-visible so {@link CriticalOperations}'s path-deletion check can fail closed the same
    /// way: an indirect-invocation construct means we cannot rule out a delete verb being assembled
    /// through it, even when no literal delete verb appears in the text.
    static boolean hasIndirectInvocation(@Nullable String text) {
        return text != null && POWERSHELL_INDIRECT_INVOCATION.matcher(text).find();
    }

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)\\brm\\s+(?:-\\S+\\s+)*(?:-[a-z]*[rf]|--(?:recursive|force|no-preserve-root))"), // rm -rf/-r/-f and --recursive/--force
            Pattern.compile("(?i)\\b(?:rmdir|rd)\\s+(?:/[a-z]+\\s+)*/s\\b"), // rmdir/rd /s (any switch order, e.g. rd /q /s)
            Pattern.compile("(?i)\\b(?:del|erase)\\b[^\\r\\n&|;]*/[sfq]\\b"), // del/erase with /s /f /q anywhere (before or after the path)
            // PowerShell recursive delete: Remove-Item and its built-in aliases (ri/rd/del/erase/rm),
            // with -Recurse or any unambiguous prefix abbreviation (-r/-re/-rec/…) PowerShell accepts.
            // The exclusion class deliberately does NOT exclude \r\n (unlike the similar patterns
            // above) — a PowerShell backtick line-continuation (`Remove-Item `\n  -Recurse -Force`)
            // makes the following newline part of the SAME statement, so excluding it here let a
            // backtick-continued recursive delete evade this pattern entirely.
            Pattern.compile("(?i)(?<![\\w-])(?:remove-item|ri|rd|del|erase|rm)\\b[^&|;]*\\s-r(?:e(?:c(?:u(?:r(?:s(?:e)?)?)?)?)?)?\\b"),
            Pattern.compile("(?i)\\bRemove-Item\\b.*(HKLM|HKCU|HKEY)"),
            Pattern.compile("(?i)\\bformat\\s+[a-z]:"),               // format C:
            Pattern.compile("(?i)\\bmkfs\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile("(?i)\\bdiskpart\\b"),
            // Modern PowerShell Storage-module equivalents of format/diskpart — this launcher's own
            // ShellTool instructs the model to emit PowerShell on this Windows host, so these are at
            // least as reachable as the legacy cmd.exe verbs above.
            Pattern.compile("(?i)\\b(format-volume|clear-disk|remove-partition|initialize-disk)\\b"),
            Pattern.compile("(?i)\\breg\\s+delete\\b"),
            // shutdown/reboot/halt/poweroff cover Unix/cmd; stop-computer/restart-computer are the
            // native PowerShell cmdlet equivalents.
            Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff|stop-computer|restart-computer)\\b"),
            Pattern.compile("(?i)\\bkill(all)?\\s+-9"),
            Pattern.compile("(?i)\\bchmod\\s+-R\\s+0*0\\b"),
            Pattern.compile(">\\s*/dev/sd"),
            Pattern.compile(":\\(\\)\\s*\\{.*\\}\\s*;\\s*:"),          // fork bomb :(){ :|:& };:
            // Windows-specific catastrophic operations with no bash counterpart to generalize from:
            // vssadmin wipes ALL Volume Shadow Copy backups (the standard first step ransomware
            // takes to block System Restore/File History recovery); bcdedit disables Windows
            // Recovery Environment; wbadmin deletes Windows Server Backup catalogs.
            Pattern.compile("(?i)\\bvssadmin\\s+delete\\s+shadows\\b"),
            Pattern.compile("(?i)\\bbcdedit\\b.*\\brecoveryenabled\\s+no\\b"),
            Pattern.compile("(?i)\\bwbadmin\\s+delete\\b"),
            // PowerShell indirect invocation of an assembled verb (see the field javadoc above).
            POWERSHELL_INDIRECT_INVOCATION,
            // PowerShell enumerate-then-pipe-delete: Get-ChildItem/gci/ls/dir gathering a recursive
            // file list and piping it into Remove-Item/ri/del/erase/rm. The recurse flag lives on the
            // enumerate side of the pipe and the delete verb on the other side, so neither the
            // recursive Remove-Item pattern above (which excludes `|`) nor a plain verb match catches
            // it — and unlike most patterns here, it is dangerous against ANY directory, not just a
            // recognized critical path.
            Pattern.compile("(?i)\\b(?:get-childitem|gci|ls|dir)\\b[^\\r\\n;]*-r(?:e(?:c(?:u(?:r(?:s(?:e)?)?)?)?)?)?\\b[^\\r\\n;]*\\|[^\\r\\n;]*\\b(?:remove-item|ri|del|erase|rm)\\b"),
            // Bash variable indirection of the command word: `<name>=<dangerous-verb>; ... $<name> ...`.
            // General-purpose shell parsing (to resolve arbitrary variable indirection) is out of
            // scope; this is a pragmatic heuristic for the narrow, common case of a dangerous verb
            // assigned to a variable and later invoked through it.
            Pattern.compile("(?i)\\b([a-z_][a-z0-9_]*)\\s*=\\s*(?:rm|dd|mkfs|shutdown|reboot|halt|poweroff|rmdir|unlink|shred|format)\\b[^\\r\\n]{0,200}?\\$\\{?\\1\\b"),
            // Wildcard delete with -Force but no explicit recurse flag: `Remove-Item .../saves/* -Force`
            // deletes every file in the directory just as surely as -Recurse would, but the recursive
            // Remove-Item pattern above requires an explicit -r... flag and misses it.
            Pattern.compile("(?i)\\b(?:remove-item|ri|del|erase|rm)\\b[^\\r\\n&|;]*\\\\?\\*[^\\r\\n&|;]*-f(?:orce)?\\b"),
    };

    // ---------------------------------------------------------------------------------------------
    // Obfuscation normalization (mid-word verb splitting, $IFS whitespace substitution)
    // ---------------------------------------------------------------------------------------------

    /// bash `${IFS}` / `$IFS` used as a whitespace substitute for a literal space between a verb and
    /// its flags/arguments (defeats the `\s+` requirement in e.g. the GNU `rm` pattern), including the
    /// common further obfuscation `$IFS$9` (an empty positional parameter appended right after `$IFS`
    /// to break up the literal token without changing what the shell expands it to).
    private static final Pattern IFS_WHITESPACE = Pattern.compile("(?i)\\$\\{?IFS\\}?(?:\\$\\d+)?");

    /// bash backslash-escape-before-a-letter is a shell no-op (`r\m` expands to `rm`) but visually
    /// splits the verb across the escape so a literal-verb regex never matches the raw text.
    private static final Pattern BACKSLASH_ESCAPED_LETTER = Pattern.compile("\\\\(?=[A-Za-z])");

    /// bash empty `''`/`""` quote runs spliced into the middle of a word are a no-op to the shell
    /// (`r''m` / `r""m` both expand to `rm`) but split the verb the same way.
    private static final Pattern EMPTY_QUOTE_RUN = Pattern.compile("''|\"\"");

    /// cmd.exe `^` immediately before a character is a no-op escape (`r^d` expands to `rd`) used the
    /// same way to split a verb across the escape.
    private static final Pattern CARET_ESCAPED_CHAR = Pattern.compile("\\^(?=.)");

    /// Normalizes shell-level no-op obfuscation that splits a dangerous verb/flag across escape
    /// characters or IFS substitutions without changing what the shell actually executes, so the
    /// verb/flag regexes above can match it. This is for DETECTION ONLY — the normalized text is
    /// never executed, only pattern-matched. Callers should match the raw text FIRST (some patterns
    /// rely on structure this collapses, e.g. Windows path separators, so normalizing unconditionally
    /// could in principle turn a raw match into a miss) and only fall back to the normalized text if
    /// the raw text didn't match, so this pass can only ADD detections, never remove one. Shared by
    /// {@link #matchesPatterns} and {@link CriticalOperations}'s path-deletion check.
    static String normalizeObfuscation(String s) {
        if (s == null || s.isEmpty()) {
            return s;
        }
        String out = IFS_WHITESPACE.matcher(s).replaceAll(" ");
        out = BACKSLASH_ESCAPED_LETTER.matcher(out).replaceAll("");
        out = EMPTY_QUOTE_RUN.matcher(out).replaceAll("");
        out = CARET_ESCAPED_CHAR.matcher(out).replaceAll("");
        return out;
    }

    /// Returns true if the command looks destructive/irreversible and should be confirmed.
    ///
    /// In addition to scanning the raw command, this decodes any base64-encoded payload
    /// (PowerShell `-EncodedCommand` / `-enc` / `-e`, and long bare base64 blobs) and re-runs
    /// the patterns on the decoded text; an encoding wrapper that cannot be decoded is treated
    /// as dangerous (fail-closed). See {@link #scanEncodedPayloads}.
    public static boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        if (matchesPatterns(command)) {
            return true;
        }
        return scanEncodedPayloads(command, DangerousCommands::matchesPatterns) != EncodedScan.NONE;
    }

    /// Runs the raw danger patterns against {@code command} (no base64 decoding), first against the
    /// text as-is and then — only if that found nothing — again against the obfuscation-normalized
    /// text (see {@link #normalizeObfuscation}), so mid-word verb splitting / `$IFS` whitespace
    /// substitution cannot evade detection while every existing raw-text match keeps working exactly
    /// as before.
    private static boolean matchesPatterns(String command) {
        if (matchesPatternsRaw(command)) {
            return true;
        }
        String normalized = normalizeObfuscation(command);
        return !normalized.equals(command) && matchesPatternsRaw(normalized);
    }

    private static boolean matchesPatternsRaw(String command) {
        for (Pattern p : PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }

    // ---------------------------------------------------------------------------------------------
    // Shared base64 decode + rescan (used by DangerousCommands and CriticalOperations)
    // ---------------------------------------------------------------------------------------------

    /// Outcome of decoding + rescanning the base64-encoded payloads embedded in a command.
    public enum EncodedScan {
        /// No encoded payload was found, or every decoded payload was inspected and looked benign.
        NONE,
        /// A decoded payload matched the supplied danger/critical matcher.
        MATCH,
        /// An explicit encoding wrapper (e.g. PowerShell `-EncodedCommand`) was present but its
        /// payload could not be base64-decoded / inspected. Callers must treat this as dangerous
        /// (fail-closed): we cannot prove the hidden command is safe.
        UNDECODABLE
    }

    /// Matches an UNAMBIGUOUS PowerShell encoding flag (`-EncodedCommand` / `-EncodedArguments` and
    /// EVERY prefix abbreviation powershell.exe accepts, `-en` .. `-encodedcommand` — PowerShell
    /// parameter prefix matching means `-EncodedCo` / `-EncodedComman` etc. are all live spellings)
    /// and captures the following base64 token (group 1). The single-letter `-e` form is
    /// deliberately NOT here: it collides with grep/sed/echo `-e`, so it is left to
    /// {@link #BARE_BASE64} (match-only, never fail-closed). The flag must start at a token
    /// boundary so it does not match inside words/paths, and longest spellings come first so the
    /// alternation prefers the full flag.
    private static final Pattern ENCODED_FLAG_WITH_ARG = Pattern.compile(
            "(?i)(?<![A-Za-z0-9])-en(?:c(?:o(?:d(?:e(?:d(?:c(?:o(?:m(?:m(?:a(?:n(?:d)?)?)?)?)?)?"
                    + "|a(?:r(?:g(?:u(?:m(?:e(?:n(?:t(?:s)?)?)?)?)?)?)?)?)?)?)?)?)?)?"
                    + "(?=\\s|[:=]|$)\\s*[:=]?\\s*[\"']?([A-Za-z0-9+/=]*)");

    /// Matches a long "bare" base64 blob (no flag) such as the argument of
    /// `[Convert]::FromBase64String('...')`, `... | base64 -d | sh`, or `powershell -e <blob>`.
    /// Kept long (>= 16 chars) to avoid decoding every short alphanumeric token; bare blobs are
    /// match-only and never fail-closed (random ids/hashes/keys must not raise a false alarm).
    private static final Pattern BARE_BASE64 = Pattern.compile("[A-Za-z0-9+/]{16,}={0,2}");

    /// Safety bounds so a pathological input cannot blow up CPU/memory during scanning.
    private static final int MAX_PAYLOADS = 12;
    private static final int MAX_TOKEN_CHARS = 200_000;
    /// How many nested decode levels to inspect, so double-base64 / base64-inside-EncodedCommand
    /// obfuscation cannot slip a single layer past the scanner. Bounded with MAX_PAYLOADS.
    private static final int MAX_DECODE_DEPTH = 3;
    /// Minimum length for a flag argument to be considered a real encoded payload (rather than a
    /// short benign value like a charset name), used to gate the fail-closed path.
    private static final int PAYLOAD_MIN_LEN = 16;

    /// Shared helper used by both {@link DangerousCommands#isDangerous} and
    /// {@link CriticalOperations}: finds base64-encoded payloads in {@code text} — PowerShell
    /// `-EncodedCommand` / `-enc` / `-e` arguments (which are UTF-16LE base64) and long bare base64
    /// blobs — decodes them, and applies {@code matcher} to each decoded interpretation (it tries
    /// both UTF-16LE and UTF-8 so PowerShell- and bash-style encodings are both caught).
    ///
    /// This does **not** scan the raw {@code text} itself — callers run their own regexes on the raw
    /// text first and only fall back to this for the encoded case.
    ///
    /// @return {@link EncodedScan#MATCH} if any decoded payload matches; {@link EncodedScan#UNDECODABLE}
    ///         if an explicit encoding flag is present whose payload cannot be safely decoded
    ///         (fail-closed); {@link EncodedScan#NONE} for plain commands or benign decoded payloads.
    public static EncodedScan scanEncodedPayloads(String text, Predicate<String> matcher) {
        return scanEncodedPayloads(text, matcher, 0);
    }

    /// Depth-aware worker: after decoding a payload that does not directly match, it recurses into the
    /// decoded text (up to {@link #MAX_DECODE_DEPTH}) so double-base64 / base64-inside-EncodedCommand
    /// obfuscation cannot slip a single layer past the scanner.
    private static EncodedScan scanEncodedPayloads(String text, Predicate<String> matcher, int depth) {
        if (text == null || text.isEmpty()) {
            return EncodedScan.NONE;
        }
        boolean undecodable = false;
        int decoded = 0;

        // 1) Unambiguous encoding flags (-en .. -EncodedCommand). These FAIL CLOSED: a payload-like
        //    argument (>= PAYLOAD_MIN_LEN) that we cannot decode into inspectable text is exactly the
        //    obfuscation we must not wave through. Short args (e.g. "-enc utf8") are benign and ignored.
        Matcher fm = ENCODED_FLAG_WITH_ARG.matcher(text);
        while (fm.find() && decoded < MAX_PAYLOADS) {
            String token = fm.group(1);
            byte[] bytes = base64OrNull(token);
            if (bytes != null) {
                decoded++;
                String s16 = new String(bytes, StandardCharsets.UTF_16LE);
                String s8 = new String(bytes, StandardCharsets.UTF_8);
                if (matcher.test(s16) || matcher.test(s8)) {
                    return EncodedScan.MATCH;
                }
                // The decoded script may itself carry another encoded payload — recurse (text only).
                if (depth < MAX_DECODE_DEPTH) {
                    if (looksLikeText(s16)) {
                        EncodedScan inner = scanEncodedPayloads(s16, matcher, depth + 1);
                        if (inner == EncodedScan.MATCH) return EncodedScan.MATCH;
                        if (inner == EncodedScan.UNDECODABLE) undecodable = true;
                    }
                    if (looksLikeText(s8)) {
                        EncodedScan inner = scanEncodedPayloads(s8, matcher, depth + 1);
                        if (inner == EncodedScan.MATCH) return EncodedScan.MATCH;
                        if (inner == EncodedScan.UNDECODABLE) undecodable = true;
                    }
                }
                // A genuine -EncodedCommand decodes to readable UTF-16LE script. If a payload-like
                // token decodes only to opaque bytes (e.g. gzip then decompress|iex), we cannot
                // inspect it → fail closed.
                if (token.length() >= PAYLOAD_MIN_LEN && !looksLikeText(s16) && !looksLikeText(s8)) {
                    undecodable = true;
                }
            } else if (token != null && token.length() >= PAYLOAD_MIN_LEN) {
                undecodable = true;     // encoding flag with a payload-like but undecodable argument
            }
        }

        // 2) Long bare base64 blobs (no flag, or after the overloaded `-e`). Best-effort, match-only:
        //    a random long token (hash, id, key) that fails to decode or decodes to noise must NOT
        //    fail closed, or every such token would raise a false alarm.
        Matcher bm = BARE_BASE64.matcher(text);
        while (bm.find() && decoded < MAX_PAYLOADS) {
            byte[] bytes = base64OrNull(bm.group());
            if (bytes == null) {
                continue;
            }
            decoded++;
            if (matchesDecoded(bytes, matcher)) {
                return EncodedScan.MATCH;
            }
            // Recurse into a decoded bare blob too (double base64), text interpretations only.
            if (depth < MAX_DECODE_DEPTH) {
                String s8 = new String(bytes, StandardCharsets.UTF_8);
                if (looksLikeText(s8) && scanEncodedPayloads(s8, matcher, depth + 1) == EncodedScan.MATCH) {
                    return EncodedScan.MATCH;
                }
                String s16 = new String(bytes, StandardCharsets.UTF_16LE);
                if (looksLikeText(s16) && scanEncodedPayloads(s16, matcher, depth + 1) == EncodedScan.MATCH) {
                    return EncodedScan.MATCH;
                }
            }
        }

        return undecodable ? EncodedScan.UNDECODABLE : EncodedScan.NONE;
    }

    /// Decodes {@code bytes} as both UTF-16LE (PowerShell `-EncodedCommand`) and UTF-8
    /// (bash `base64 -d` and friends) and tests {@code matcher} against each interpretation.
    private static boolean matchesDecoded(byte[] bytes, Predicate<String> matcher) {
        if (bytes.length == 0) {
            return false;
        }
        if (matcher.test(new String(bytes, StandardCharsets.UTF_16LE))) {
            return true;
        }
        return matcher.test(new String(bytes, StandardCharsets.UTF_8));
    }

    /// Heuristic: does {@code s} read as plain command text rather than opaque binary? Counts
    /// printable characters (tab/newline/space and anything from U+0020 up, excluding DEL and the
    /// U+FFFD replacement char produced by misdecoding); treats >= 85% printable as text. Used only
    /// to decide whether a decoded encoding-flag payload is inspectable.
    private static boolean looksLikeText(String s) {
        int total = s.length();
        if (total == 0) {
            return false;
        }
        int printable = 0;
        for (int i = 0; i < total; i++) {
            char c = s.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || (c >= 0x20 && c != 0x7f && c != 0xFFFD)) {
                printable++;
            }
        }
        return printable * 100 >= total * 85;
    }

    /// Decodes a base64 token, tolerating missing padding, or returns {@code null} if it is not
    /// valid base64. Uses the lenient MIME decoder so stray whitespace inside the token is ignored.
    @Nullable
    private static byte[] base64OrNull(@Nullable String token) {
        if (token == null) {
            return null;
        }
        String t = token.trim();
        int len = t.length();
        if (len < 4 || len > MAX_TOKEN_CHARS) {
            return null;
        }
        int rem = len % 4;
        if (rem == 1) {
            return null;            // 4n+1 is never a valid base64 length
        }
        if (rem != 0) {
            t = t + "==".substring(0, 4 - rem);
        }
        try {
            return Base64.getMimeDecoder().decode(t);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
