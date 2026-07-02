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

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)\\brm\\s+(?:-\\S+\\s+)*(?:-[a-z]*[rf]|--(?:recursive|force|no-preserve-root))"), // rm -rf/-r/-f and --recursive/--force
            Pattern.compile("(?i)\\b(?:rmdir|rd)\\s+(?:/[a-z]+\\s+)*/s\\b"), // rmdir/rd /s (any switch order, e.g. rd /q /s)
            Pattern.compile("(?i)\\b(?:del|erase)\\b[^\\r\\n&|;]*/[sfq]\\b"), // del/erase with /s /f /q anywhere (before or after the path)
            // PowerShell recursive delete: Remove-Item and its built-in aliases (ri/rd/del/erase/rm),
            // with -Recurse or any unambiguous prefix abbreviation (-r/-re/-rec/…) PowerShell accepts.
            Pattern.compile("(?i)(?<![\\w-])(?:remove-item|ri|rd|del|erase|rm)\\b[^\\r\\n&|;]*\\s-r(?:e(?:c(?:u(?:r(?:s(?:e)?)?)?)?)?)?\\b"),
            Pattern.compile("(?i)\\bRemove-Item\\b.*(HKLM|HKCU|HKEY)"),
            Pattern.compile("(?i)\\bformat\\s+[a-z]:"),               // format C:
            Pattern.compile("(?i)\\bmkfs\\b"),
            Pattern.compile("(?i)\\bdd\\s+if="),
            Pattern.compile("(?i)\\bdiskpart\\b"),
            Pattern.compile("(?i)\\breg\\s+delete\\b"),
            Pattern.compile("(?i)\\b(shutdown|reboot|halt|poweroff)\\b"),
            Pattern.compile("(?i)\\bkill(all)?\\s+-9"),
            Pattern.compile("(?i)\\bchmod\\s+-R\\s+0*0\\b"),
            Pattern.compile(">\\s*/dev/sd"),
            Pattern.compile(":\\(\\)\\s*\\{.*\\}\\s*;\\s*:"),          // fork bomb :(){ :|:& };:
    };

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

    /// Runs the raw danger patterns against {@code command} (no decoding).
    private static boolean matchesPatterns(String command) {
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
