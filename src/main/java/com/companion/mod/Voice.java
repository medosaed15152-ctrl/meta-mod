package com.companion.mod;

import com.google.gson.*;
import de.maxhenkel.voicechat.api.Position;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.audiochannel.AudioPlayer;
import de.maxhenkel.voicechat.api.audiochannel.LocationalAudioChannel;
import net.minecraft.server.level.ServerLevel;

import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;

/** ElevenLabs text-to-speech, played from her body through Simple Voice Chat. */
public class Voice {
    public static volatile VoicechatServerApi api; // set by CompanionVoicePlugin

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "companion-voice"); t.setDaemon(true); return t; });
    private static final int FRAME = 960; // 20 ms at 48 kHz, what Simple Voice Chat expects

    /** stability (low = more expressive), style (high = more dramatic) */
    private record Mood(double stability, double style) {}
    private static final Map<String, Mood> MOODS = Map.of(
            "happy",   new Mood(0.40, 0.55),
            "excited", new Mood(0.20, 0.85),
            "sad",     new Mood(0.75, 0.25),
            "angry",   new Mood(0.25, 0.90),
            "worried", new Mood(0.45, 0.45),
            "loving",  new Mood(0.50, 0.60),
            "sleepy",  new Mood(0.85, 0.10),
            "neutral", new Mood(0.50, 0.30));

    public static void speak(CompanionEntity who, String text, String emotion) {
        if (api == null || Config.elevenKey().isBlank() || Config.VOICE_ID.get().isBlank()) return;
        Mood m = MOODS.getOrDefault(emotion, MOODS.get("neutral"));

        JsonObject settings = new JsonObject();
        settings.addProperty("stability", m.stability());
        settings.addProperty("similarity_boost", 0.75);
        settings.addProperty("style", m.style());
        settings.addProperty("use_speaker_boost", true);
        JsonObject body = new JsonObject();
        body.addProperty("text", text);
        body.addProperty("model_id", "eleven_multilingual_v2");
        body.add("voice_settings", settings);

        HttpRequest req = HttpRequest.newBuilder(URI.create(
                        "https://api.elevenlabs.io/v1/text-to-speech/" + Config.VOICE_ID.get() + "?output_format=pcm_24000"))
                .timeout(Duration.ofSeconds(30))
                .header("xi-api-key", Config.elevenKey())
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofByteArray()).thenAccept(res -> {
            if (res.statusCode() != 200) {
                CompanionMod.LOG.warn("ElevenLabs error {}", res.statusCode());
                return;
            }
            short[] pcm48 = upsample(res.body());
            who.getServer().execute(() -> play(who, pcm48));
        }).exceptionally(ex -> { CompanionMod.LOG.warn("ElevenLabs failed", ex); return null; });
    }

    /** 24 kHz 16-bit little-endian mono -> 48 kHz shorts (linear interpolation) */
    private static short[] upsample(byte[] raw) {
        ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
        int n = raw.length / 2;
        short[] in = new short[n];
        for (int i = 0; i < n; i++) in[i] = bb.getShort();
        short[] out = new short[n * 2];
        for (int i = 0; i < n; i++) {
            short a = in[i], b = i + 1 < n ? in[i + 1] : a;
            out[2 * i] = a;
            out[2 * i + 1] = (short) ((a + b) / 2);
        }
        return out;
    }

    private static void play(CompanionEntity who, short[] pcm) {
        VoicechatServerApi a = api;
        if (a == null || !who.isAlive()) return;
        LocationalAudioChannel ch = a.createLocationalAudioChannel(UUID.randomUUID(),
                a.fromServerLevel((ServerLevel) who.level()), pos(a, who));
        if (ch == null) return;
        ch.setDistance(40f);

        int[] idx = {0};
        Supplier<short[]> frames = () -> {
            if (idx[0] >= pcm.length) return null;
            short[] f = new short[FRAME];
            System.arraycopy(pcm, idx[0], f, 0, Math.min(FRAME, pcm.length - idx[0]));
            idx[0] += FRAME;
            return f;
        };
        AudioPlayer player = a.createAudioPlayer(ch, a.createEncoder(), frames);

        // keep the voice attached to her body while she walks
        ScheduledFuture<?> follow = SCHED.scheduleAtFixedRate(() -> {
            if (who.isAlive()) ch.updateLocation(pos(a, who));
        }, 100, 100, TimeUnit.MILLISECONDS);
        long ms = pcm.length * 1000L / 48000 + 500;
        SCHED.schedule(() -> follow.cancel(false), ms, TimeUnit.MILLISECONDS);

        player.startPlaying();
    }

    /** Lists the voices on the ElevenLabs account: [id, "Name (gender)"] */
    public static void fetchVoices(java.util.function.Consumer<List<String[]>> done) {
        if (Config.elevenKey().isBlank()) return;
        HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.elevenlabs.io/v1/voices"))
                .timeout(Duration.ofSeconds(20)).header("xi-api-key", Config.elevenKey()).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
            if (res.statusCode() != 200) { CompanionMod.LOG.warn("ElevenLabs voices error {}", res.statusCode()); return; }
            List<String[]> out = new ArrayList<>();
            for (JsonElement el : JsonParser.parseString(res.body()).getAsJsonObject().getAsJsonArray("voices")) {
                JsonObject v = el.getAsJsonObject();
                String name = v.get("name").getAsString();
                if (v.has("labels") && v.get("labels").isJsonObject() && v.getAsJsonObject("labels").has("gender"))
                    name += " (" + v.getAsJsonObject("labels").get("gender").getAsString() + ")";
                out.add(new String[]{v.get("voice_id").getAsString(), name});
                if (out.size() >= 60) break;
            }
            done.accept(out);
        }).exceptionally(ex -> { CompanionMod.LOG.warn("voice list failed", ex); return null; });
    }

    private static Position pos(VoicechatServerApi a, CompanionEntity e) {
        return a.createPosition(e.getX(), e.getEyeY(), e.getZ());
    }
}
