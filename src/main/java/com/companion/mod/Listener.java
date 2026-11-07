package com.companion.mod;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.opus.OpusDecoder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.server.ServerLifecycleHooks;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

/** Her ears: collects your mic from Simple Voice Chat, and turns it into text with ElevenLabs speech-to-text. */
public class Listener {
    private static final int RATE = 48000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private static final class State {
        final ByteArrayOutputStream pcm = new ByteArrayOutputStream();
        OpusDecoder decoder;
        long last;
    }
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final ScheduledExecutorService SCHED = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "meta-ears"); t.setDaemon(true); return t; });
    static { SCHED.scheduleAtFixedRate(Listener::flush, 250, 250, TimeUnit.MILLISECONDS); }

    public static void onMic(MicrophonePacketEvent e) {
        if (!Config.LISTEN.get() || Config.elevenKey().isBlank()) return;
        VoicechatServerApi api = Voice.api;
        VoicechatConnection con = e.getSenderConnection();
        if (api == null || con == null || con.getPlayer() == null) return;
        byte[] opus = e.getPacket().getOpusEncodedData();
        if (opus == null || opus.length == 0) return;

        State s = STATES.computeIfAbsent(con.getPlayer().getUuid(), k -> {
            State n = new State();
            n.decoder = api.createDecoder();
            return n;
        });
        synchronized (s) {
            short[] samples = s.decoder.decode(opus);
            ByteBuffer bb = ByteBuffer.allocate(samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
            for (short v : samples) bb.putShort(v);
            s.pcm.writeBytes(bb.array());
            s.last = System.currentTimeMillis();
        }
    }

    /** A pause of ~0.8 s ends a sentence. */
    private static void flush() {
        long now = System.currentTimeMillis();
        STATES.forEach((id, s) -> {
            byte[] data = null;
            synchronized (s) {
                int size = s.pcm.size();
                if (size > 0 && (now - s.last > 800 || size > RATE * 2 * 25)) {
                    data = s.pcm.toByteArray();
                    s.pcm.reset();
                    s.decoder.resetState();
                }
            }
            if (data != null && data.length > RATE * 2 / 2) transcribe(id, data); // ignore clips < 0.5 s
        });
    }

    private static void transcribe(UUID player, byte[] pcm) {
        try {
            String b = "----meta" + System.nanoTime();
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"model_id\"\r\n\r\nscribe_v1\r\n").getBytes(StandardCharsets.UTF_8));
            o.writeBytes(("--" + b + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\nContent-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            o.writeBytes(wav(pcm));
            o.writeBytes(("\r\n--" + b + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create("https://api.elevenlabs.io/v1/speech-to-text"))
                    .timeout(Duration.ofSeconds(30))
                    .header("xi-api-key", Config.elevenKey())
                    .header("content-type", "multipart/form-data; boundary=" + b)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(o.toByteArray()))
                    .build();

            HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).thenAccept(res -> {
                if (res.statusCode() != 200) { CompanionMod.LOG.warn("STT error {}: {}", res.statusCode(), res.body()); return; }
                JsonObject j = JsonParser.parseString(res.body()).getAsJsonObject();
                String text = j.has("text") ? j.get("text").getAsString().trim() : "";
                if (text.length() < 2) return;
                MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
                if (server == null) return;
                server.execute(() -> {
                    ServerPlayer p = server.getPlayerList().getPlayer(player);
                    if (p != null) CompanionMod.EVENTS.hear(p, text);
                });
            }).exceptionally(ex -> { CompanionMod.LOG.warn("STT failed", ex); return null; });
        } catch (Exception ex) {
            CompanionMod.LOG.warn("STT failed", ex);
        }
    }

    private static byte[] wav(byte[] pcm) {
        ByteBuffer h = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()).putInt(36 + pcm.length).put("WAVE".getBytes());
        h.put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(RATE).putInt(RATE * 2).putShort((short) 2).putShort((short) 16);
        h.put("data".getBytes()).putInt(pcm.length).put(pcm);
        return h.array();
    }
}
