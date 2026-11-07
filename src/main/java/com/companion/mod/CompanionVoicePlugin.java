package com.companion.mod;

import de.maxhenkel.voicechat.api.ForgeVoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;

@ForgeVoicechatPlugin
public class CompanionVoicePlugin implements VoicechatPlugin {
    @Override public String getPluginId() { return CompanionMod.ID; }

    @Override public void registerEvents(EventRegistration reg) {
        reg.registerEvent(VoicechatServerStartedEvent.class, e -> Voice.api = e.getVoicechat());
        reg.registerEvent(MicrophonePacketEvent.class, Listener::onMic);
    }
}
