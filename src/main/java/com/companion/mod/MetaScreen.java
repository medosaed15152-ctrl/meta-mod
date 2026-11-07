package com.companion.mod;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Meta menu. Opens from: the Mods screen on the main menu (Config button), key M in game, or the pause menu button.
 * Choices are saved on your computer and sent to the server when you join (you must be OP there).
 */
public class MetaScreen extends Screen {
    private static final int PER_PAGE = 6;
    private final Screen parent;
    private int skinPage = 0, voicePage = 0;
    private boolean slim = ClientConfig.SLIM.get();
    private String currentSkin = ClientConfig.SKIN.get();
    private String voiceText = ClientConfig.VOICE.get();

    public MetaScreen(Screen parent) {
        super(Component.literal("Meta"));
        this.parent = parent;
    }

    private boolean inWorld() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level != null && mc.getConnection() != null && Net.CH.isRemotePresent(mc.getConnection().getConnection());
    }

    @Override protected void init() {
        if (inWorld()) Net.CH.sendToServer(new Net.RequestVoices());
        rebuild();
    }

    @Override public void onClose() { Minecraft.getInstance().setScreen(parent); }

    public void rebuild() {
        clearWidgets();
        int left = width / 2 - 210, right = width / 2 + 10, w = 200, top = 50;

        // ---- skins ----
        List<String> skins = new ArrayList<>();
        skins.add("default");
        File dir = ClientSetup.skinDir();
        dir.mkdirs();
        String[] files = dir.list((d, n) -> n.toLowerCase().endsWith(".png"));
        if (files != null) {
            Arrays.sort(files);
            for (String f : files) skins.add(f.substring(0, f.length() - 4));
        }
        page(left, top, w, skins, skinPage, p -> { skinPage = p; rebuild(); },
                this::pickSkin, s -> (s.equals(currentSkin) ? "> " : "") + s);

        // ---- voices ----
        List<String> ids = ClientHooks.voiceIds, names = ClientHooks.voiceNames;
        if (inWorld() && !ids.isEmpty()) {
            List<String> idx = new ArrayList<>();
            for (int i = 0; i < ids.size(); i++) idx.add(Integer.toString(i));
            page(right, top, w, idx, voicePage, p -> { voicePage = p; rebuild(); }, i -> {
                String id = ids.get(Integer.parseInt(i));
                voiceText = id;
                ClientConfig.VOICE.set(id); ClientConfig.VOICE.save();
                Net.CH.sendToServer(new Net.SetVoice(id, true));
            }, i -> names.get(Integer.parseInt(i)));
        } else {
            // main menu (no server yet): paste an ElevenLabs voice id; it is applied when you join
            EditBox box = new EditBox(font, right, top + 20, w, 20, Component.literal("Voice ID"));
            box.setMaxLength(64);
            box.setValue(voiceText);
            box.setResponder(t -> voiceText = t);
            addRenderableWidget(box);
            addRenderableWidget(Button.builder(Component.literal("Save voice id"), b -> {
                ClientConfig.VOICE.set(voiceText.trim()); ClientConfig.VOICE.save();
            }).bounds(right, top + 46, w, 20).build());
        }

        // ---- bottom row ----
        int y = height - 40;
        addRenderableWidget(Button.builder(Component.literal(slim ? "Arms: Slim (Alex)" : "Arms: Classic (Steve)"), b -> {
            slim = !slim;
            pickSkin(currentSkin);
        }).bounds(left, y, 130, 20).build());
        Button hear = Button.builder(Component.literal("Hear voice"),
                b -> Net.CH.sendToServer(new Net.TestVoice())).bounds(width / 2 - 65, y, 130, 20).build();
        hear.active = inWorld();
        addRenderableWidget(hear);
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
                .bounds(right + 70, y, 130, 20).build());
    }

    private void pickSkin(String skin) {
        currentSkin = skin;
        ClientConfig.SKIN.set(skin); ClientConfig.SKIN.save();
        ClientConfig.SLIM.set(slim); ClientConfig.SLIM.save();
        if (inWorld()) Net.CH.sendToServer(new Net.SetAppearance(skin, slim));
        rebuild();
    }

    private void page(int x, int y, int w, List<String> items, int page, java.util.function.IntConsumer setPage,
                      java.util.function.Consumer<String> onPick, java.util.function.Function<String, String> label) {
        int pages = Math.max(1, (items.size() + PER_PAGE - 1) / PER_PAGE);
        page = Math.min(page, pages - 1);
        for (int i = 0; i < PER_PAGE; i++) {
            int k = page * PER_PAGE + i;
            if (k >= items.size()) break;
            String item = items.get(k);
            String text = label.apply(item);
            addRenderableWidget(Button.builder(Component.literal(text.length() > 30 ? text.substring(0, 30) : text),
                    b -> onPick.accept(item)).bounds(x, y + i * 24, w, 20).build());
        }
        int cur = page;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> setPage.accept(Math.max(0, cur - 1)))
                .bounds(x, y + PER_PAGE * 24, 60, 20).build());
        addRenderableWidget(Button.builder(Component.literal(">"), b -> setPage.accept(Math.min(pages - 1, cur + 1)))
                .bounds(x + w - 60, y + PER_PAGE * 24, 60, 20).build());
    }

    @Override public void render(GuiGraphics g, int mx, int my, float pt) {
        renderBackground(g);
        g.drawCenteredString(font, "Meta", width / 2, 12, 0xFFFFFF);
        g.drawCenteredString(font, inWorld() ? "Applied live on the server" : "Saved here, applied when you join your server (you must be OP)",
                width / 2, 24, 0xAAAAAA);
        g.drawCenteredString(font, "Skin  (put .png files in meta_skins)", width / 2 - 110, 38, 0xFFD27F);
        g.drawCenteredString(font, "Voice", width / 2 + 110, 38, 0xFFD27F);
        super.render(g, mx, my, pt);
    }
}
