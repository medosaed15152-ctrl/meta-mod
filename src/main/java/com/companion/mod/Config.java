package com.companion.mod;

import net.minecraftforge.common.ForgeConfigSpec;

public class Config {
    private static final ForgeConfigSpec.Builder B = new ForgeConfigSpec.Builder();

    // ---- KEYS: put them here, or use /meta claudekey <key> and /meta elevenkey <key> in game ----
    public static final ForgeConfigSpec.ConfigValue<String> CLAUDE_KEY = B
            .comment("Anthropic API key (or set env var ANTHROPIC_API_KEY, or use /meta claudekey <key>)")
            .define("claudeApiKey", "");
    public static final ForgeConfigSpec.ConfigValue<String> ELEVEN_KEY = B
            .comment("ElevenLabs API key (or env var ELEVENLABS_API_KEY, or /meta elevenkey <key>)")
            .define("elevenLabsApiKey", "");
    public static final ForgeConfigSpec.ConfigValue<String> VOICE_ID = B
            .comment("ElevenLabs voice id. Default is a ready-made female voice; change with /meta voice <id>")
            .define("elevenLabsVoiceId", "21m00Tcm4TlvDq8ikWAM");

    public static final ForgeConfigSpec.ConfigValue<String> CLAUDE_MODEL = B.define("claudeModel", "claude-sonnet-5-5");
    public static final ForgeConfigSpec.ConfigValue<String> NAME = B.define("companionName", "Meta");
    public static final ForgeConfigSpec.BooleanValue ROMANTIC = B.comment("Affectionate / romantic personality").define("romantic", true);
    public static final ForgeConfigSpec.BooleanValue LISTEN = B.comment("Let her hear you through Simple Voice Chat (speech-to-text via ElevenLabs)").define("listen", true);
    public static final ForgeConfigSpec.IntValue TALK_MIN = B.comment("Min seconds between spontaneous remarks").defineInRange("talkMinSeconds", 60, 15, 3600);
    public static final ForgeConfigSpec.IntValue TALK_MAX = B.defineInRange("talkMaxSeconds", 180, 20, 7200);
    public static final ForgeConfigSpec.BooleanValue SUBTITLES = B.comment("Also show what she says on the action bar").define("subtitles", true);
    public static final ForgeConfigSpec.ConfigValue<String> SKIN = B.comment("Skin file name from <minecraft folder>/meta_skins (without .png), or 'default'. Use /meta skin <name>").define("skin", "default");
    public static final ForgeConfigSpec.BooleanValue SLIM = B.comment("true = slim (Alex) arms, false = classic (Steve) arms").define("slimArms", true);

    public static final ForgeConfigSpec SPEC = B.build();

    private static String keyOr(ForgeConfigSpec.ConfigValue<String> v, String env) {
        String k = v.get();
        if (k == null || k.isBlank()) k = System.getenv(env);
        return k == null ? "" : k.trim();
    }
    public static String claudeKey() { return keyOr(CLAUDE_KEY, "ANTHROPIC_API_KEY"); }
    public static String elevenKey() { return keyOr(ELEVEN_KEY, "ELEVENLABS_API_KEY"); }
}
