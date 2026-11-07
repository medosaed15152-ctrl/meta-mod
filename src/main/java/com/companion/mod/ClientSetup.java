package com.companion.mod;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.event.RegisterClientCommandsEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.ScreenEvent;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import org.lwjgl.glfw.GLFW;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Client-only: player-model rendering, skins from <minecraft>/meta_skins, and /metaskins. */
public class ClientSetup {
    static File skinDir() { return new File(Minecraft.getInstance().gameDirectory, "meta_skins"); }

    public static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(CompanionMod.COMPANION.get(), Renderer::new);
    }

    static final KeyMapping MENU_KEY = new KeyMapping("key.meta.menu", GLFW.GLFW_KEY_M, "key.categories.meta");

    public static void keys(RegisterKeyMappingsEvent e) { e.register(MENU_KEY); }

    private static int applyIn = -1;

    public static void loggedIn(ClientPlayerNetworkEvent.LoggingIn e) { applyIn = 60; }

    /** a few seconds after joining, send the skin/voice saved in the Mods menu */
    private static void autoApply(Minecraft mc) {
        if (mc.getConnection() == null || !Net.CH.isRemotePresent(mc.getConnection().getConnection())) return;
        Net.CH.sendToServer(new Net.SetAppearance(ClientConfig.SKIN.get(), ClientConfig.SLIM.get()));
        if (!ClientConfig.VOICE.get().isBlank()) Net.CH.sendToServer(new Net.SetVoice(ClientConfig.VOICE.get(), false));
    }

    public static void tick(TickEvent.ClientTickEvent e) {
        if (e.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (applyIn > 0 && --applyIn == 0) autoApply(mc);
        while (MENU_KEY.consumeClick()) {
            if (mc.player != null && mc.screen == null) mc.setScreen(new MetaScreen(null));
        }
    }

    /** a "Meta" button on the pause menu */
    public static void pauseButton(ScreenEvent.Init.Post e) {
        if (e.getScreen() instanceof PauseScreen) {
            e.addListener(Button.builder(Component.literal("Meta"),
                    b -> Minecraft.getInstance().setScreen(new MetaScreen(e.getScreen()))).bounds(8, 8, 90, 20).build());
        }
    }

    /** /metaskins lists the .png files you can use with /meta skin <name> */
    public static void commands(RegisterClientCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("metaskins").executes(ctx -> {
            File dir = skinDir();
            dir.mkdirs();
            String[] files = dir.list((d, n) -> n.toLowerCase().endsWith(".png"));
            String msg = files == null || files.length == 0
                    ? "No skins yet. Put 64x64 .png skins in: " + dir.getAbsolutePath()
                    : "Skins: " + String.join(", ", Arrays.stream(files).map(n -> n.substring(0, n.length() - 4)).toList())
                      + "  (use: /meta skin <name>)";
            ctx.getSource().sendSuccess(() -> Component.literal(msg), false);
            return 1;
        }));
    }

    static class Renderer extends MobRenderer<CompanionEntity, PlayerModel<CompanionEntity>> {
        private static final ResourceLocation DEFAULT = new ResourceLocation(CompanionMod.ID, "textures/entity/companion.png");
        private static final Map<String, ResourceLocation> LOADED = new HashMap<>();
        private static final Map<String, Long> MISSING = new HashMap<>();
        private final PlayerModel<CompanionEntity> slim;
        private final PlayerModel<CompanionEntity> wide;

        Renderer(EntityRendererProvider.Context ctx) {
            super(ctx, new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER_SLIM), true), 0.5f);
            this.slim = this.model;
            this.wide = new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), false);
        }

        @Override
        public void render(CompanionEntity e, float yaw, float pt, PoseStack ps, MultiBufferSource buf, int light) {
            this.model = e.isSlim() ? slim : wide;
            super.render(e, yaw, pt, ps, buf, light);
        }

        @Override
        public ResourceLocation getTextureLocation(CompanionEntity e) {
            String name = e.getSkin();
            if (name == null || name.isBlank() || name.equals("default")) return DEFAULT;
            ResourceLocation rl = LOADED.get(name);
            if (rl != null) return rl;
            Long t = MISSING.get(name);
            if (t != null && System.currentTimeMillis() - t < 5000) return DEFAULT; // don't hit the disk every frame
            rl = load(name);
            if (rl == null) { MISSING.put(name, System.currentTimeMillis()); return DEFAULT; }
            LOADED.put(name, rl);
            return rl;
        }

        private static ResourceLocation load(String name) {
            File f = new File(skinDir(), name.toLowerCase().endsWith(".png") ? name : name + ".png");
            if (!f.isFile()) return null;
            try (InputStream in = new FileInputStream(f)) {
                NativeImage img = NativeImage.read(in);
                ResourceLocation rl = new ResourceLocation(CompanionMod.ID, "skin/" + name.toLowerCase().replaceAll("[^a-z0-9._-]", "_"));
                Minecraft.getInstance().getTextureManager().register(rl, new DynamicTexture(img));
                return rl;
            } catch (Exception ex) {
                CompanionMod.LOG.warn("Could not load skin {}", f, ex);
                return null;
            }
        }
    }
}
