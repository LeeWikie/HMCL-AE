package org.jackhuang.hmcl.ai.agent;

import org.jackhuang.hmcl.ai.AiSession;
import org.jackhuang.hmcl.ai.AiSettings;
import org.jackhuang.hmcl.ai.langchain4j.AiChatClient;
import org.jackhuang.hmcl.ai.llm.LlmException;
import org.jackhuang.hmcl.ai.llm.LlmMessage;
import org.jackhuang.hmcl.ai.llm.LlmStreamCallback;
import org.jackhuang.hmcl.ai.llm.LlmUsage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/// Orchestrates a single chat session by building the conversation context,
/// dispatching requests to the LLM, and storing responses.
///
/// Tool execution is handled transparently by the {@link AiChatClient} adapter
/// (via LangChain4j native function calling).
///
/// ## Streaming
///
/// Streaming uses the client's native streaming API; tokens are forwarded to
/// the UI callback as they arrive.
///
/// ## Prompt
///
/// The system prompt is built dynamically by {@link AiPromptBuilder} so that
/// enabled tools, search, skills, and policies are reflected in the model's
/// understanding.
@NotNullByDefault
public final class ChatAgent {

    private static final String FALLBACK_SYSTEM_PROMPT =
            "You are an AI assistant for Hello Minecraft! Launcher. "
            + "You can help users with Minecraft setup, mod management, "
            + "game launching issues, and log/crash analysis. "
            + "Use the provided tools when appropriate.";

    private final AiChatClient client;
    private final AiSession session;
    private final ExecutorService executor;
    private final AiSettings settings;
    private final AiPromptBuilder promptBuilder;

    public ChatAgent(AiChatClient client, AiSession session, AiSettings settings,
                     AiPromptBuilder promptBuilder) {
        this.client = client;
        this.session = session;
        this.settings = settings;
        this.promptBuilder = promptBuilder;
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "chat-agent");
            t.setDaemon(true);
            return t;
        });
    }

    public AiSession getSession() { return session; }
    public AiSettings getSettings() { return settings; }

    /// System instruction used to summarise the running conversation into a compact,
    /// continuation-style brief so the chat can continue with far fewer tokens.
    private static final String COMPACT_SYSTEM_PROMPT = String.join("\n",
            "You compress a long assistant/user conversation into a concise, continuation-style summary",
            "so the chat can continue with far less context. Reply in the user's language (Chinese if the",
            "conversation is mainly Chinese). Output ONLY the summary, structured with these headings:",
            "目标 (what the user ultimately wants) / 已完成 (what has been done, including tool results and",
            "concrete facts discovered: instance names, versions, mod lists, accounts, paths) / 关键发现",
            "(important findings, decisions, user preferences, constraints) / 下一步 (the immediate next",
            "action to take). Be specific and preserve names/versions/IDs; drop chit-chat and verbose logs.");

    /// Compresses the current session into a continuation-style summary using a single
    /// non-streaming model call, then replaces the session history with that summary.
    ///
    /// Runs on the agent's executor (off the FX thread). On success the session is
    /// {@link AiSession#clear() cleared} and a single assistant message holding the
    /// summary is added; the returned future completes with the raw summary text. If
    /// the conversation is empty (or the model returns nothing) the session is left
    /// untouched and the future completes with an empty string.
    public CompletableFuture<String> compact() {
        return CompletableFuture.supplyAsync(() -> {
            try { return doCompact(); }
            catch (LlmException e) { throw new RuntimeException(e); }
        }, executor);
    }

    private String doCompact() throws LlmException {
        List<LlmMessage> history = session.getMessages();
        if (history.isEmpty()) {
            return "";
        }
        List<LlmMessage> request = new ArrayList<>(history.size() + 2);
        request.add(new LlmMessage("system", COMPACT_SYSTEM_PROMPT));
        request.addAll(history);
        request.add(new LlmMessage("user",
                "请把以上整段对话压缩成续写式摘要（目标 / 已完成 / 关键发现 / 下一步），只输出摘要本身。"));
        String summary = client.sendMessage(Collections.unmodifiableList(request)).join();
        if (summary == null || summary.isBlank()) {
            return "";
        }
        summary = summary.strip();
        session.clear();
        session.addMessage(new LlmMessage("assistant", "【上下文已压缩】\n" + summary));
        return summary;
    }

    public CompletableFuture<String> send(String userInput) {
        return CompletableFuture.supplyAsync(() -> {
            try { return doSend(userInput); }
            catch (LlmException e) { throw new RuntimeException(e); }
        }, executor);
    }

    public CompletableFuture<Void> sendStreaming(String userInput, LlmStreamCallback callback) {
        return CompletableFuture.supplyAsync(() -> {
            try { doSendStreaming(userInput, callback); }
            catch (LlmException e) { throw new RuntimeException(e); }
            return null;
        }, executor);
    }

    private String doSend(String userInput) throws LlmException {
        session.addMessage(new LlmMessage("user", userInput));
        String response = client.sendMessage(buildMessages()).join();
        session.addMessage(new LlmMessage("assistant", response));
        return response;
    }

    private void doSendStreaming(String userInput, LlmStreamCallback callback) throws LlmException {
        session.addMessage(new LlmMessage("user", userInput));
        client.sendMessageStreaming(buildMessages(), new LlmStreamCallback() {
            private final StringBuilder full = new StringBuilder();
            @Nullable private LlmUsage usage;
            @Override public void onToken(String t) { full.append(t); callback.onToken(t); }
            @Override public void onUsage(LlmUsage u) { this.usage = u; callback.onUsage(u); }
            @Override public void onToolActivity(String name, String args) { callback.onToolActivity(name, args); }
            @Override public void onToolResult(String name, boolean success, String summary) { callback.onToolResult(name, success, summary); }
            @Override public void onComplete(String c) {
                String f = c != null && full.isEmpty() ? c : full.toString();
                LlmMessage aiMessage = new LlmMessage("assistant", f);
                if (usage != null) {
                    aiMessage.setUsage(usage);
                }
                session.addMessage(aiMessage);
                callback.onComplete(f);
            }
            @Override public void onError(LlmException e) { callback.onError(e); }
        });
    }

    private List<LlmMessage> buildMessages() {
        List<LlmMessage> all = new ArrayList<>();
        String prompt = promptBuilder != null ? promptBuilder.build() : FALLBACK_SYSTEM_PROMPT;
        all.add(new LlmMessage("system", prompt));
        all.addAll(session.getMessages());
        return Collections.unmodifiableList(all);
    }

    public void clearSession() { session.clear(); }
}
