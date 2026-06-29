/*
 * Hello Minecraft! Launcher - Agent Experience
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.ai.tools;

import javafx.application.Platform;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.ai.tools.ToolPermission;
import org.jackhuang.hmcl.ai.tools.ToolResult;
import org.jackhuang.hmcl.ai.tools.ToolSource;
import org.jackhuang.hmcl.ai.tools.ToolSpec;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.offline.Skin;
import org.jackhuang.hmcl.auth.yggdrasil.TextureModel;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.util.skin.InvalidSkinException;
import org.jackhuang.hmcl.util.skin.NormalizedSkin;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/// Sets the skin (and, for offline accounts, the cape) of a Minecraft account.
///
/// This reuses HMCL's own skin paths — it does NOT touch files by hand:
///   - Offline accounts: builds a [`Skin`] and calls
///     [`OfflineAccount#setSkin(Skin)`], exactly like the offline skin dialog
///     ([`org.jackhuang.hmcl.ui.account.OfflineAccountSkinPane`]). The skin is
///     injected at launch through authlib-injector. Supports a local PNG
///     (skin + optional cape), a LittleSkin / CustomSkinLoader skin-station, or
///     the built-in Steve/Alex presets. Model is auto-detected (slim vs wide)
///     from the PNG via [`NormalizedSkin`] unless overridden.
///   - Online accounts (Microsoft / authlib-injector): uploads a local PNG to
///     the real account via [`Account#uploadSkin(boolean, Path)`] — the same
///     call HMCL's account list uses. Only a local PNG skin is supported here;
///     capes and skin-station presets are NOT (HMCL's online upload API is
///     skin-only).
///
/// Honest limits:
///   - Offline skins are visible only to YOU (and on LAN / authlib-injector
///     servers); they are not your real Mojang skin and other players on
///     normal online-mode servers will not see them.
///   - For online accounts the upload changes your real Minecraft skin and
///     needs network + valid (non-expired) credentials.
///   - Cape upload is supported only for offline accounts (as a local file).
///
/// Permission level: CONTROLLED_WRITE.
@NotNullByDefault
public final class SetSkinTool implements ToolSpec {

    @Override
    public String getName() {
        return "set_skin";
    }

    @Override
    public String getDescription() {
        return "Sets the skin (and, for offline accounts, the cape) of a Minecraft account, reusing HMCL's own skin system. "
                + "Parameters: username (optional — target account profile name; defaults to the selected account); "
                + "source (optional — one of local / little_skin / csl_api / steve / alex / default; inferred as 'local' when "
                + "skinPath is given, or 'csl_api' when cslApi is given); "
                + "skinPath (absolute path to a local .png skin, for source=local); "
                + "capePath (optional absolute path to a local .png cape — OFFLINE accounts only); "
                + "cslApi (root URL of a CustomSkinLoader/Yggdrasil skin-station, for source=csl_api — OFFLINE only); "
                + "model (optional — auto / wide / slim; 'auto' detects slim vs wide from the PNG). "
                + "Offline accounts support every source; the skin is applied at next launch via authlib-injector and is local-only "
                + "(not your real Mojang skin). Online (Microsoft / authlib-injector) accounts support ONLY a local PNG upload — "
                + "this changes the real account skin and needs network + valid credentials; capes and skin-station presets are not "
                + "supported for online accounts. Use list_accounts to see account names and types.";
    }

    @Override
    public ToolPermission getPermission() {
        return ToolPermission.CONTROLLED_WRITE;
    }

    @Override
    public ToolSource getSource() {
        return ToolSource.LOCAL;
    }

    @Override
    public boolean supportsStructuredSchema() {
        return true;
    }

    @Override
    public String getInputSchemaJson() {
        return """
               {
                 "$schema": "https://json-schema.org/draft/2020-12/schema",
                 "type": "object",
                 "properties": {
                   "username": {
                     "type": "string",
                     "description": "Target account profile name (case-insensitive). Defaults to the currently selected account."
                   },
                   "source": {
                     "type": "string",
                     "enum": ["local", "little_skin", "csl_api", "steve", "alex", "default"],
                     "description": "Where the skin comes from. If omitted it is inferred: 'local' when skinPath is given, 'csl_api' when cslApi is given."
                   },
                   "skinPath": {
                     "type": "string",
                     "description": "Absolute path to a local PNG skin file (for source=local)."
                   },
                   "capePath": {
                     "type": "string",
                     "description": "Optional absolute path to a local PNG cape file. OFFLINE accounts only."
                   },
                   "cslApi": {
                     "type": "string",
                     "description": "Root URL of a CustomSkinLoader / Yggdrasil skin-station API (for source=csl_api). OFFLINE accounts only."
                   },
                   "model": {
                     "type": "string",
                     "enum": ["auto", "wide", "slim", "classic", "steve", "alex"],
                     "description": "Body model. 'auto' (default) detects slim vs wide from the PNG."
                   }
                 },
                 "required": []
               }
               """;
    }

    @Override
    public ToolResult execute(Map<String, Object> parameters) {
        String username = str(parameters, "username");
        String skinPathRaw = str(parameters, "skinPath");
        String capePathRaw = str(parameters, "capePath");
        String cslApi = str(parameters, "cslApi");

        try {
            // 1. Resolve the target account on the JavaFX thread (Accounts store is UI-owned).
            Object[] resolved = resolveAccount(username);
            Account account = (Account) resolved[0];
            if (account == null) {
                return ToolResult.failure((String) resolved[1]);
            }

            boolean offline = account instanceof OfflineAccount;
            boolean canUpload = !offline && account.canUploadSkin();
            String accountName = account.getProfileName();

            // 2. Determine the skin source.
            Skin.Type source = resolveSource(str(parameters, "source"), skinPathRaw, cslApi);
            if (source == null) {
                return ToolResult.failure("Could not determine the skin source. Provide 'source', or give 'skinPath' "
                        + "for a local PNG, or 'cslApi' for a skin-station URL.");
            }

            TextureModel explicitModel = parseModel(str(parameters, "model"));

            if (offline) {
                return applyOffline((OfflineAccount) account, accountName, source, skinPathRaw, capePathRaw, cslApi, explicitModel);
            } else if (canUpload) {
                return applyOnline(account, accountName, source, skinPathRaw, capePathRaw, explicitModel);
            } else {
                String type = loginType(account);
                return ToolResult.failure("Account '" + accountName + "' [" + type + "] does not support changing its skin "
                        + "from HMCL. Skins can be set for offline accounts and for accounts whose server allows skin upload "
                        + "(Microsoft / authlib-injector).");
            }
        } catch (SkinToolException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Throwable e) {
            return ToolResult.failure("Failed to set skin: " + e);
        }
    }

    // ---- Offline accounts: build a Skin and setSkin() (applied at launch via authlib-injector) ----

    private static ToolResult applyOffline(OfflineAccount account, String accountName, Skin.Type source,
                                           @Nullable String skinPathRaw, @Nullable String capePathRaw,
                                           @Nullable String cslApi, @Nullable TextureModel explicitModel)
            throws SkinToolException {
        Skin skin;
        String summary;

        switch (source) {
            case LOCAL_FILE: {
                Path skinPng = requirePng(skinPathRaw, "skin");
                String capeAbs = null;
                if (capePathRaw != null) {
                    capeAbs = requirePng(capePathRaw, "cape").toString();
                }

                TextureModel model = explicitModel;
                String modelNote = "";
                if (model == null) {
                    try {
                        model = readSkin(skinPng, false).isSlim() ? TextureModel.SLIM : TextureModel.WIDE;
                    } catch (SkinToolException e) {
                        model = TextureModel.WIDE;
                        modelNote = " (model auto-detect failed; defaulting to wide)";
                    }
                }

                skin = new Skin(Skin.Type.LOCAL_FILE, null, model, skinPng.toString(), capeAbs);
                summary = "local PNG '" + skinPng + "' (model: " + model.modelName + modelNote + ")"
                        + (capeAbs != null ? " with cape '" + capeAbs + "'" : "");
                break;
            }
            case LITTLE_SKIN: {
                skin = new Skin(Skin.Type.LITTLE_SKIN, null, null, null, null);
                summary = "LittleSkin (littleskin.cn) — loaded by the account's username '" + accountName + "'";
                break;
            }
            case CUSTOM_SKIN_LOADER_API: {
                if (cslApi == null) {
                    throw new SkinToolException("source=csl_api requires the 'cslApi' parameter (the skin-station API root URL).");
                }
                skin = new Skin(Skin.Type.CUSTOM_SKIN_LOADER_API, cslApi, null, null, null);
                summary = "CustomSkinLoader API '" + cslApi + "' — loaded by the account's username '" + accountName + "'";
                break;
            }
            case STEVE:
            case ALEX:
            case DEFAULT: {
                skin = new Skin(source, null, null, null, null);
                summary = "built-in preset '" + source.name().toLowerCase(Locale.ROOT) + "'";
                break;
            }
            default:
                throw new SkinToolException("Unsupported skin source for an offline account: " + source);
        }

        applyOfflineSkin(account, skin);

        return ToolResult.success("Set the skin of offline account '" + accountName + "' to " + summary + ".\n"
                + "It is applied the next time the instance is launched (HMCL injects it via authlib-injector). "
                + "Note: offline skins are visible only to you and on LAN / authlib-injector servers — they are NOT your real "
                + "Mojang skin, so players on normal online-mode servers will not see them.");
    }

    private static void applyOfflineSkin(OfflineAccount account, Skin skin) throws SkinToolException {
        CompletableFuture<Void> future = new CompletableFuture<>();
        runOnFx(() -> {
            try {
                account.setSkin(skin);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SkinToolException("Failed to apply the skin: " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new SkinToolException("Timed out while applying the skin; the UI did not respond.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinToolException("Interrupted while applying the skin.");
        }
    }

    // ---- Online accounts: upload a local PNG to the real account (network) ----

    private static ToolResult applyOnline(Account account, String accountName, Skin.Type source,
                                          @Nullable String skinPathRaw, @Nullable String capePathRaw,
                                          @Nullable TextureModel explicitModel) throws SkinToolException {
        if (source != Skin.Type.LOCAL_FILE) {
            return ToolResult.failure("Account '" + accountName + "' is an online account. Online accounts can only have a "
                    + "local PNG skin uploaded (source=local with skinPath); skin-station presets (little_skin / csl_api) and "
                    + "the steve/alex presets are offline-only.");
        }

        Path skinPng = requirePng(skinPathRaw, "skin");
        // Match HMCL's own upload validation (account list): the Mojang/authlib API needs 64x32 or 64x64.
        NormalizedSkin normalized = readSkin(skinPng, true);
        boolean isSlim = explicitModel != null ? explicitModel == TextureModel.SLIM : normalized.isSlim();

        Throwable error = uploadOnWorker(account, isSlim, skinPng);
        if (error != null) {
            return ToolResult.failure("Failed to upload the skin to account '" + accountName + "': " + error.getMessage()
                    + ". Make sure you are online and the account's credentials have not expired (try re-logging in).");
        }

        String capeNote = capePathRaw != null
                ? "\nNote: the cape was ignored — HMCL's online skin upload supports skins only (no capes)."
                : "";
        return ToolResult.success("Uploaded the skin '" + skinPng + "' (model: " + (isSlim ? "slim" : "wide") + ") to online "
                + "account '" + accountName + "'. This is now your real Minecraft skin." + capeNote);
    }

    private static Throwable uploadOnWorker(Account account, boolean isSlim, Path file) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        Thread worker = new Thread(() -> {
            try {
                account.uploadSkin(isSlim, file);
                future.complete(null);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }, "ai-skin-upload");
        worker.setDaemon(true);
        worker.start();
        try {
            future.get(60, TimeUnit.SECONDS);
            return null;
        } catch (ExecutionException e) {
            return e.getCause() != null ? e.getCause() : e;
        } catch (TimeoutException e) {
            return new IOException("skin upload timed out after 60s");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return e;
        }
    }

    // ---- Account resolution ----

    /// Resolves the target account on the FX thread. Returns {@code [account, null]} on success
    /// or {@code [null, errorMessage]} when no matching account is found.
    private static Object[] resolveAccount(@Nullable String username) throws SkinToolException {
        CompletableFuture<Object[]> future = new CompletableFuture<>();
        runOnFx(() -> {
            try {
                List<Account> accounts = new ArrayList<>(Accounts.getAccounts());
                if (accounts.isEmpty()) {
                    future.complete(new Object[]{null, "No accounts are logged in. Use add_offline_account or microsoft_login first."});
                    return;
                }
                Account target;
                if (username != null) {
                    target = accounts.stream()
                            .filter(a -> username.equalsIgnoreCase(a.getProfileName()))
                            .findFirst().orElse(null);
                    if (target == null) {
                        StringBuilder names = new StringBuilder();
                        for (Account a : accounts) names.append("\n  - ").append(a.getProfileName());
                        future.complete(new Object[]{null, "No account named '" + username + "'. Available accounts:" + names});
                        return;
                    }
                } else {
                    target = Accounts.getSelectedAccount();
                    if (target == null) {
                        future.complete(new Object[]{null, "No account is selected and no 'username' was given. "
                                + "Use list_accounts to see names, or pass 'username'."});
                        return;
                    }
                }
                future.complete(new Object[]{target, null});
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new SkinToolException("Failed to resolve the account: " + cause.getMessage());
        } catch (TimeoutException e) {
            throw new SkinToolException("Timed out while resolving the account; the UI did not respond.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SkinToolException("Interrupted while resolving the account.");
        }
    }

    private static String loginType(Account account) {
        try {
            return Accounts.getLoginType(Accounts.getAccountFactory(account));
        } catch (Throwable t) {
            return "unknown";
        }
    }

    // ---- Parameter parsing / validation helpers ----

    @Nullable
    private static Skin.Type resolveSource(@Nullable String source, @Nullable String skinPath, @Nullable String cslApi)
            throws SkinToolException {
        if (source != null) {
            switch (source.toLowerCase(Locale.ROOT)) {
                case "local":
                case "local_file":
                case "file":
                case "png":
                    return Skin.Type.LOCAL_FILE;
                case "little_skin":
                case "littleskin":
                    return Skin.Type.LITTLE_SKIN;
                case "csl_api":
                case "csl":
                case "customskinloader":
                case "custom_skin_loader_api":
                case "yggdrasil":
                case "yggdrasil_api":
                    return Skin.Type.CUSTOM_SKIN_LOADER_API;
                case "steve":
                case "classic":
                case "wide":
                    return Skin.Type.STEVE;
                case "alex":
                case "slim":
                    return Skin.Type.ALEX;
                case "default":
                case "none":
                    return Skin.Type.DEFAULT;
                default:
                    throw new SkinToolException("Unknown source '" + source + "'. Use one of: "
                            + "local, little_skin, csl_api, steve, alex, default.");
            }
        }
        if (skinPath != null) {
            return Skin.Type.LOCAL_FILE;
        }
        if (cslApi != null) {
            return Skin.Type.CUSTOM_SKIN_LOADER_API;
        }
        return null;
    }

    @Nullable
    private static TextureModel parseModel(@Nullable String model) {
        if (model == null) {
            return null;
        }
        switch (model.toLowerCase(Locale.ROOT)) {
            case "slim":
            case "alex":
                return TextureModel.SLIM;
            case "wide":
            case "classic":
            case "steve":
            case "default":
                return TextureModel.WIDE;
            case "auto":
            default:
                return null;
        }
    }

    private static Path requirePng(@Nullable String raw, String label) throws SkinToolException {
        if (raw == null) {
            throw new SkinToolException("A " + label + " file path is required (the '" + label + "Path' parameter).");
        }
        Path path;
        try {
            path = Paths.get(raw);
        } catch (Exception e) {
            throw new SkinToolException("Invalid " + label + " path '" + raw + "': " + e.getMessage());
        }
        if (!Files.isRegularFile(path)) {
            throw new SkinToolException(capitalize(label) + " file not found: " + path);
        }
        if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png")) {
            throw new SkinToolException(capitalize(label) + " must be a .png file, got: " + path);
        }
        return path.toAbsolutePath();
    }

    /// Loads and validates a skin PNG. When {@code strict} is set, enforces the 64x32 / 64x64
    /// size that the online upload API requires (mirrors HMCL's account-list upload check).
    private static NormalizedSkin readSkin(Path png, boolean strict) throws SkinToolException {
        Image image;
        try (InputStream in = Files.newInputStream(png)) {
            image = new Image(in);
        } catch (IOException e) {
            throw new SkinToolException("Failed to read skin image '" + png + "': " + e.getMessage());
        }
        if (image.isError()) {
            Throwable ex = image.getException();
            throw new SkinToolException("Failed to decode skin PNG '" + png + "': " + (ex != null ? ex.getMessage() : "unknown error"));
        }
        if (strict) {
            int w = (int) image.getWidth();
            int h = (int) image.getHeight();
            if (w != 64 || (h != 32 && h != 64)) {
                throw new SkinToolException("Skin must be 64x32 or 64x64 pixels for online upload, got " + w + "x" + h + ".");
            }
        }
        try {
            return new NormalizedSkin(image);
        } catch (InvalidSkinException e) {
            throw new SkinToolException("Invalid skin image '" + png + "': " + e.getMessage());
        }
    }

    private static void runOnFx(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    @Nullable
    private static String str(Map<String, Object> parameters, String key) {
        Object value = parameters.get(key);
        if (value == null) {
            return null;
        }
        String text = value.toString().trim();
        return text.isEmpty() ? null : text;
    }

    private static String capitalize(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /// Internal control-flow exception carrying a user-facing failure message.
    private static final class SkinToolException extends Exception {
        SkinToolException(String message) {
            super(message);
        }
    }
}
