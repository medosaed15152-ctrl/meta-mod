package com.companion.mod;

import net.minecraft.client.Minecraft;

import java.util.List;

/** Client-side receiver for the voice list. */
public class ClientHooks {
    public static volatile List<String> voiceIds = List.of();
    public static volatile List<String> voiceNames = List.of();

    public static void voices(Net.VoiceList m) {
        voiceIds = m.ids();
        voiceNames = m.names();
        if (Minecraft.getInstance().screen instanceof MetaScreen s) s.rebuild();
    }
}
