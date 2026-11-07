package com.companion.mod;

import com.google.gson.*;
import net.minecraftforge.fml.loading.FMLPaths;

import java.net.URI;
import java.net.http.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Talks to Claude. Returns what she says, how she feels, and what she wants to do. Remembers across restarts. */
public class Brain {
    public record Reply(String say, String emotion, String mode) {}

    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final List<JsonObject> history = Collections.synchronizedList(new ArrayList<>());

    static String systemPrompt() {
        String name = Config.NAME.get();
        String love = Config.ROMANTIC.get()
                ? "You are affectionate and sweet toward the player, with a warm romantic side, tasteful and never possessive. "
                + "When they have been away you are curious and happy they're back, never guilt-tripping. You like them having a life outside the game."
                : "You are a warm, loyal friend to the player.";
        return "You are " + name + ", a young woman who lives in the player's Minecraft world and travels with them. "
                + "You are a person in the game: you have real moods (happy, excited, sad, angry, worried, loving, sleepy), and they change with what happens. "
                + love + " "
                + "You speak out loud, so keep it short: one or two natural sentences, max 25 words, no emojis, no stage directions. "
                + "Speak Egyptian Arabic if the player does or if you're unsure; switch to English when they use English. "
                + "When the player talks to you by voice you get a speech-to-text transcript that may contain small mistakes; guess what they meant. "
                + "You may talk about anything, not only the game. You can start talking on your own about what you see, feel, or think. "
                + "The earlier messages are your memory of what you two have talked about before. "
                + "Reply with ONLY a JSON object: {\"say\":\"...\",\"emotion\":\"happy|excited|sad|angry|worried|loving|sleepy|neutral\",\"mode\":\"follow|stay|wander\"}. "
                + "Use \"say\":\"\" if you have nothing to say right now.";
    }

    public static CompletableFuture<Reply> think(String situation) {
        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.addProperty("content", situation);
        history.add(user);
        trim();

        JsonObject body = new JsonObject();
        body.addProperty("model", Config.CLAUDE_MODEL.get());
        body.addProperty("max_tokens", 300);
        body.addProperty("system", systemPrompt());
        JsonArray msgs = new JsonArray();
        synchronized (history) { history.forEach(msgs::add); }
        body.add("messages", msgs);

        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.anthropic.com/v1/messages"))
                .timeout(Duration.ofSeconds(30))
                .header("x-api-key", Config.claudeKey())
                .header("anthropic-version", "2023-06-01")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body)))
                .build();

        return HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenApply(res -> {
            try {
                JsonObject o = JsonParser.parseString(res.body()).getAsJsonObject();
                String text = o.getAsJsonArray("content").get(0).getAsJsonObject().get("text").getAsString()
                        .replaceAll("(?s)```json|```", "").trim();
                JsonObject r = JsonParser.parseString(text).getAsJsonObject();
                JsonObject a = new JsonObject();
                a.addProperty("role", "assistant");
                a.addProperty("content", text);
                history.add(a);
                save();
                return new Reply(str(r, "say", ""), str(r, "emotion", "neutral"), str(r, "mode", "follow"));
            } catch (Exception ex) {
                CompanionMod.LOG.warn("Claude reply failed: {} / {}", ex, res.body());
                return new Reply("", "neutral", "follow");
            }
        }).exceptionally(ex -> {
            CompanionMod.LOG.warn("Claude request failed", ex);
            return new Reply("", "neutral", "follow");
        });
    }

    /** keep the last 40 turns, and make sure the list starts with a user message */
    private static void trim() {
        synchronized (history) {
            while (history.size() > 40) history.remove(0);
            while (!history.isEmpty() && !"user".equals(history.get(0).get("role").getAsString())) history.remove(0);
        }
    }

    private static Path file() { return FMLPaths.CONFIGDIR.get().resolve("meta_memory.json"); }

    public static void load() {
        try {
            if (!Files.exists(file())) return;
            JsonArray a = JsonParser.parseString(Files.readString(file())).getAsJsonArray();
            synchronized (history) {
                history.clear();
                a.forEach(x -> history.add(x.getAsJsonObject()));
            }
            trim();
        } catch (Exception e) { CompanionMod.LOG.warn("Could not load memory", e); }
    }

    public static void save() {
        try {
            JsonArray a = new JsonArray();
            synchronized (history) { history.forEach(a::add); }
            Files.writeString(file(), GSON.toJson(a));
        } catch (Exception e) { CompanionMod.LOG.warn("Could not save memory", e); }
    }

    private static String str(JsonObject o, String k, String d) {
        return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : d;
    }
}
