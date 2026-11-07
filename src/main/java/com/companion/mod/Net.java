package com.companion.mod;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Packets used by the Meta menu (client <-> server). */
public class Net {
    private static final String PROTOCOL = "1";
    public static final SimpleChannel CH = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(CompanionMod.ID, "main"), () -> PROTOCOL, PROTOCOL::equals, PROTOCOL::equals);

    public record SetAppearance(String skin, boolean slim) {}
    public record SetVoice(String voiceId, boolean test) {}
    public record RequestVoices() {}
    public record TestVoice() {}
    public record VoiceList(List<String> ids, List<String> names) {}

    public static void init() {
        int i = 0;

        CH.registerMessage(i++, SetAppearance.class,
                (m, b) -> { b.writeUtf(m.skin(), 256); b.writeBoolean(m.slim()); },
                b -> new SetAppearance(b.readUtf(256), b.readBoolean()),
                (m, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer p = ctx.get().getSender();
                        if (p != null && p.hasPermissions(2)) MetaCommands.applyAppearance(p, m.skin(), m.slim());
                    });
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CH.registerMessage(i++, SetVoice.class,
                (m, b) -> { b.writeUtf(m.voiceId(), 128); b.writeBoolean(m.test()); },
                b -> new SetVoice(b.readUtf(128), b.readBoolean()),
                (m, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer p = ctx.get().getSender();
                        if (p == null || !p.hasPermissions(2)) return;
                        Config.VOICE_ID.set(m.voiceId());
                        Config.VOICE_ID.save();
                        if (m.test()) speakTest(p);
                    });
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CH.registerMessage(i++, RequestVoices.class,
                (m, b) -> {}, b -> new RequestVoices(),
                (m, ctx) -> {
                    ServerPlayer p = ctx.get().getSender();
                    if (p != null) Voice.fetchVoices(list -> p.server.execute(() -> {
                        List<String> ids = new ArrayList<>(), names = new ArrayList<>();
                        for (String[] v : list) { ids.add(v[0]); names.add(v[1]); }
                        CH.send(PacketDistributor.PLAYER.with(() -> p), new VoiceList(ids, names));
                    }));
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CH.registerMessage(i++, TestVoice.class,
                (m, b) -> {}, b -> new TestVoice(),
                (m, ctx) -> {
                    ctx.get().enqueueWork(() -> {
                        ServerPlayer p = ctx.get().getSender();
                        if (p != null) speakTest(p);
                    });
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_SERVER));

        CH.registerMessage(i++, VoiceList.class,
                (m, b) -> {
                    b.writeVarInt(m.ids().size());
                    for (int k = 0; k < m.ids().size(); k++) { b.writeUtf(m.ids().get(k), 128); b.writeUtf(m.names().get(k), 128); }
                },
                b -> {
                    int n = b.readVarInt();
                    List<String> ids = new ArrayList<>(), names = new ArrayList<>();
                    for (int k = 0; k < n; k++) { ids.add(b.readUtf(128)); names.add(b.readUtf(128)); }
                    return new VoiceList(ids, names);
                },
                (m, ctx) -> {
                    ctx.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientHooks.voices(m)));
                    ctx.get().setPacketHandled(true);
                }, Optional.of(NetworkDirection.PLAY_TO_CLIENT));
    }

    private static void speakTest(ServerPlayer p) {
        List<CompanionEntity> l = ((ServerLevel) p.level()).getEntitiesOfClass(
                CompanionEntity.class, p.getBoundingBox().inflate(64));
        if (!l.isEmpty()) Voice.speak(l.get(0), "أهلاً! ده صوتي. عاجبك؟ Hello, this is how I sound.", "happy");
    }
}
