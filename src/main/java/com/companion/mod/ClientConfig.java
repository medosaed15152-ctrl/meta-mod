package com.companion.mod;

import net.minecraftforge.common.ForgeConfigSpec;

/** Saved on YOUR computer. Picked in the Mods menu (main screen) and applied automatically when you join your server. */
public class ClientConfig {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec.ConfigValue<String> SKIN = B.define("skin", "default");
    public static final ForgeConfigSpec.BooleanValue SLIM = B.define("slimArms", true);
    public static final ForgeConfigSpec.ConfigValue<String> VOICE = B.comment("ElevenLabs voice id ('' = leave the server's voice)").define("voiceId", "");
    public static final ForgeConfigSpec SPEC = B.build();
}
