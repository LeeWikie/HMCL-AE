package org.jackhuang.hmcl.ai.tools;

import org.jetbrains.annotations.NotNullByDefault;

import java.util.regex.Pattern;

/// Heuristic detector for destructive / irreversible shell commands that the AI
/// agent should not run without explicit user confirmation. Conservative by
/// design: a false positive only costs one confirmation prompt.
@NotNullByDefault
public final class DangerousCommands {

    private DangerousCommands() {
    }

    private static final Pattern[] PATTERNS = {
            Pattern.compile("(?i)\\brm\\s+(-\\S+\\s+)*-?[rf]"),       // rm -rf / rm -r / rm -f
            Pattern.compile("(?i)\\brmdir\\s+/s"),                     // Windows rmdir /s
            Pattern.compile("(?i)\\bdel\\s+.*\\s/[sfq]"),             // del /s /f /q
            Pattern.compile("(?i)\\bRemove-Item\\b.*-Recurse"),       // PowerShell recursive delete
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
    public static boolean isDangerous(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        for (Pattern p : PATTERNS) {
            if (p.matcher(command).find()) {
                return true;
            }
        }
        return false;
    }
}
