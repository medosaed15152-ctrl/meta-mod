package com.companion.mod;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.event.RegisterCommandsEvent;

/**
 * /meta claudekey <key>   /meta elevenkey <key>   /meta voice <id>
 * /meta skin <name>       /meta slim <true|false> /meta status
 */
public class MetaCommands {
    public static void register(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("meta").requires(s -> s.hasPermission(2))
                .then(setter("claudekey", Config.CLAUDE_KEY))
                .then(setter("elevenkey", Config.ELEVEN_KEY))
                .then(setter("voice", Config.VOICE_ID))
                .then(Commands.literal("skin").then(Commands.argument("name", StringArgumentType.word())
                        .executes(c -> skin(c.getSource(), StringArgumentType.getString(c, "name"), null))))
                .then(Commands.literal("slim").then(Commands.argument("on", BoolArgumentType.bool())
                        .executes(c -> skin(c.getSource(), null, BoolArgumentType.getBool(c, "on")))))
                .then(Commands.literal("status").executes(c -> {
                    String s = "Claude key: " + (Config.claudeKey().isBlank() ? "MISSING" : "ok")
                            + " | ElevenLabs key: " + (Config.elevenKey().isBlank() ? "MISSING" : "ok")
                            + " | voice chat: " + (Voice.api == null ? "NOT LOADED" : "ok")
                            + " | skin: " + Config.SKIN.get();
                    c.getSource().sendSuccess(() -> Component.literal(s), false);
                    return 1;
                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> setter(String name, ForgeConfigSpec.ConfigValue<String> v) {
        return Commands.literal(name).then(Commands.argument("value", StringArgumentType.greedyString()).executes(c -> {
            v.set(StringArgumentType.getString(c, "value").trim());
            v.save();
            c.getSource().sendSuccess(() -> Component.literal("Saved."), false);
            return 1;
        }));
    }

    private static int skin(CommandSourceStack src, String file, Boolean slim) throws CommandSyntaxException {
        ServerPlayer p = src.getPlayerOrException();
        applyAppearance(p, file == null ? Config.SKIN.get() : file, slim == null ? Config.SLIM.get() : slim);
        src.sendSuccess(() -> Component.literal("Done. (skin files live in <minecraft>/meta_skins on YOUR computer; list them with /metaskins)"), false);
        return 1;
    }

    /** used by the commands and by the in-game menu */
    public static void applyAppearance(ServerPlayer p, String file, boolean slim) {
        for (CompanionEntity c : ((ServerLevel) p.level()).getEntitiesOfClass(CompanionEntity.class, p.getBoundingBox().inflate(128))) {
            c.setSkin(file);
            c.setSlim(slim);
        }
        Config.SKIN.set(file); Config.SKIN.save();
        Config.SLIM.set(slim); Config.SLIM.save();
    }
}
