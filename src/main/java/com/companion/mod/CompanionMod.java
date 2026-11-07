package com.companion.mod;

import com.mojang.logging.LogUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.monster.Monster;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;
import org.slf4j.Logger;

import java.util.List;
import java.util.Random;

@Mod(CompanionMod.ID)
public class CompanionMod {
    public static final String ID = "companion";
    public static final Logger LOG = LogUtils.getLogger();
    public static final Events EVENTS = new Events();

    private static final DeferredRegister<EntityType<?>> TYPES = DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ID);
    public static final RegistryObject<EntityType<CompanionEntity>> COMPANION = TYPES.register("companion",
            () -> EntityType.Builder.of(CompanionEntity::new, MobCategory.CREATURE)
                    .sized(0.6f, 1.8f).clientTrackingRange(10).build(ID + ":companion"));

    public CompanionMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        TYPES.register(bus);
        bus.addListener((net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent e) -> e.enqueueWork(Net::init));
        bus.addListener((EntityAttributeCreationEvent e) -> e.put(COMPANION.get(), CompanionEntity.attributes().build()));
        if (FMLEnvironment.dist == Dist.CLIENT) {
            bus.addListener(ClientSetup::renderers);
            bus.addListener(ClientSetup::keys);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::commands);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::tick);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::pauseButton);
            MinecraftForge.EVENT_BUS.addListener(ClientSetup::loggedIn);
            // the "Config" button next to the mod in the main-menu Mods screen opens the Meta menu
            ModLoadingContext.get().registerExtensionPoint(net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory.class,
                    () -> new net.minecraftforge.client.ConfigScreenHandler.ConfigScreenFactory((mc, parent) -> new MetaScreen(parent)));
        }
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, Config.SPEC, "companion.toml");
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC, "companion-client.toml");
        MinecraftForge.EVENT_BUS.register(EVENTS);
        MinecraftForge.EVENT_BUS.addListener(MetaCommands::register);
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> Brain.load());
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> Brain.save());
    }

    /** Everything that makes her live: join/leave, idle chatter, reactions. */
    public static class Events {
        private final Random rnd = new Random();
        private long tick = 0, nextTalk = 0, joinAt = -1, lastReaction = 0;
        private long awayMinutes = 0;
        private volatile boolean busy = false;
        private ServerPlayer pendingPlayer;
        private String pendingText;

        /** Called by Listener when the player spoke out loud. */
        public void hear(ServerPlayer p, String text) {
            CompanionEntity c = find(p);
            if (c == null || c.distanceTo(p) > 40) return;
            if (busy) { pendingPlayer = p; pendingText = text; return; }
            trigger(p, c, "EVENT: the player said to you out loud (speech-to-text, may contain small errors): \"" + text + "\"", true);
        }

        @SubscribeEvent
        public void onLogin(PlayerEvent.PlayerLoggedInEvent e) {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            long last = p.getPersistentData().getLong("companion_last_seen");
            awayMinutes = last == 0 ? 0 : (System.currentTimeMillis() - last) / 60000;
            joinAt = tick + 100; // wait for chunks to load
        }

        @SubscribeEvent
        public void onLogout(PlayerEvent.PlayerLoggedOutEvent e) {
            e.getEntity().getPersistentData().putLong("companion_last_seen", System.currentTimeMillis());
        }

        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent e) {
            if (e.phase != TickEvent.Phase.END) return;
            tick++;
            if (tick % 20 != 0) return;
            MinecraftServer server = e.getServer();
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                CompanionEntity c = find(p);
                if (joinAt >= 0 && tick >= joinAt) {
                    joinAt = -1;
                    if (c == null) c = spawn(p);
                    String away = awayMinutes < 5 ? "just a moment ago"
                            : awayMinutes < 120 ? awayMinutes + " minutes ago"
                            : awayMinutes < 2880 ? (awayMinutes / 60) + " hours ago"
                            : (awayMinutes / 1440) + " days ago";
                    trigger(p, c, "EVENT: the player just joined the world. They last left " + away
                            + ". React the way you honestly would after that long.", true);
                    schedule();
                } else if (c != null && tick >= nextTalk) {
                    trigger(p, c, "EVENT: a quiet moment. Say something on your own if you feel like it "
                            + "(about what you see, how you feel, a memory, a question) or stay silent.", false);
                    schedule();
                }
            }
        }

        @SubscribeEvent
        public void onChat(ServerChatEvent e) {
            ServerPlayer p = e.getPlayer();
            CompanionEntity c = find(p);
            if (c != null && c.distanceTo(p) < 32)
                trigger(p, c, "EVENT: the player said to you: \"" + e.getRawText() + "\"", true);
        }

        @SubscribeEvent
        public void onHurt(LivingHurtEvent e) {
            if (tick - lastReaction < 200) return;
            if (e.getEntity() instanceof ServerPlayer p && p.getHealth() - e.getAmount() < 8) {
                CompanionEntity c = find(p);
                if (c != null) trigger(p, c, "EVENT: the player is badly hurt by " + e.getSource().getMsgId() + "!", false);
            } else if (e.getEntity() instanceof CompanionEntity c && c.level() instanceof ServerLevel lvl) {
                ServerPlayer p = (ServerPlayer) lvl.getNearestPlayer(c, 40);
                if (p != null) trigger(p, c, "EVENT: you were just hurt by " + e.getSource().getMsgId() + ".", false);
            }
        }

        @SubscribeEvent
        public void onDimension(PlayerEvent.PlayerChangedDimensionEvent e) {
            if (!(e.getEntity() instanceof ServerPlayer p)) return;
            CompanionEntity c = find(p);
            if (c != null) trigger(p, c, "EVENT: the player is heading into a new dimension (" + e.getTo().location()
                    + "). This is an adventure!", true);
        }

        private void schedule() {
            int lo = Config.TALK_MIN.get(), hi = Math.max(lo + 1, Config.TALK_MAX.get());
            nextTalk = tick + 20L * (lo + rnd.nextInt(hi - lo));
        }

        private CompanionEntity find(ServerPlayer p) {
            List<CompanionEntity> l = ((ServerLevel) p.level()).getEntitiesOfClass(
                    CompanionEntity.class, p.getBoundingBox().inflate(64));
            return l.isEmpty() ? null : l.get(0);
        }

        private CompanionEntity spawn(ServerPlayer p) {
            ServerLevel lvl = (ServerLevel) p.level();
            CompanionEntity c = COMPANION.get().create(lvl);
            c.moveTo(p.getX() + 1, p.getY(), p.getZ() + 1, p.getYRot(), 0);
            c.setSkin(Config.SKIN.get());
            c.setSlim(Config.SLIM.get());
            c.setCustomName(Component.literal(Config.NAME.get()));
            c.setCustomNameVisible(true);
            lvl.addFreshEntity(c);
            return c;
        }

        private void trigger(ServerPlayer p, CompanionEntity c, String event, boolean force) {
            if (busy || Config.claudeKey().isBlank()) return;
            if (!force && tick - lastReaction < 100) return;
            busy = true;
            lastReaction = tick;
            Brain.think(describe(p, c, event)).thenAccept(r -> p.server.execute(() -> {
                busy = false;
                if (pendingText != null) { String t = pendingText; ServerPlayer pp = pendingPlayer; pendingText = null; pendingPlayer = null; hear(pp, t); }
                if (!c.isAlive()) return;
                c.mode = List.of("follow", "stay", "wander").contains(r.mode()) ? r.mode() : "follow";
                if (r.say().isBlank()) return;
                if (Config.SUBTITLES.get())
                    p.displayClientMessage(Component.literal(Config.NAME.get() + ": " + r.say()), true);
                Voice.speak(c, r.say(), r.emotion());
            }));
        }

        private String describe(ServerPlayer p, CompanionEntity c, String event) {
            ServerLevel lvl = (ServerLevel) p.level();
            long t = lvl.getDayTime() % 24000;
            String when = t < 1000 ? "sunrise" : t < 12000 ? "daytime" : t < 13000 ? "sunset" : "night";
            int hostiles = lvl.getEntitiesOfClass(Monster.class, c.getBoundingBox().inflate(16)).size();
            return event + "\nSITUATION: " + when + (lvl.isRaining() ? ", raining" : "")
                    + "; dimension " + lvl.dimension().location()
                    + "; biome " + lvl.getBiome(c.blockPosition()).unwrapKey().map(k -> k.location().toString()).orElse("?")
                    + "; player health " + (int) p.getHealth() + "/20, hunger " + p.getFoodData().getFoodLevel() + "/20"
                    + ", holding " + p.getMainHandItem().getHoverName().getString()
                    + "; your health " + (int) c.getHealth() + "/20; distance to player " + (int) c.distanceTo(p)
                    + "; hostile mobs near you: " + hostiles + "; your current mode: " + c.mode;
        }
    }
}
