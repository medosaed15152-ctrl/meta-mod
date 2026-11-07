package com.companion.mod;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

public class CompanionEntity extends PathfinderMob {
    private static final EntityDataAccessor<String> SKIN = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Boolean> SLIM = SynchedEntityData.defineId(CompanionEntity.class, EntityDataSerializers.BOOLEAN);

    /** follow | stay | wander - set by the brain */
    public volatile String mode = "follow";

    public CompanionEntity(EntityType<? extends PathfinderMob> type, Level level) {
        super(type, level);
        setPersistenceRequired();
    }

    @Override protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SKIN, "default");
        entityData.define(SLIM, true);
    }

    public String getSkin() { return entityData.get(SKIN); }
    public void setSkin(String s) { entityData.set(SKIN, s); }
    public boolean isSlim() { return entityData.get(SLIM); }
    public void setSlim(boolean b) { entityData.set(SLIM, b); }

    @Override public void addAdditionalSaveData(CompoundTag t) {
        super.addAdditionalSaveData(t);
        t.putString("MetaSkin", getSkin());
        t.putBoolean("MetaSlim", isSlim());
        t.putString("MetaMode", mode);
    }
    @Override public void readAdditionalSaveData(CompoundTag t) {
        super.readAdditionalSaveData(t);
        if (t.contains("MetaSkin")) setSkin(t.getString("MetaSkin"));
        if (t.contains("MetaSlim")) setSlim(t.getBoolean("MetaSlim"));
        if (t.contains("MetaMode")) mode = t.getString("MetaMode");
    }

    public static AttributeSupplier.Builder attributes() {
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.3)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.FOLLOW_RANGE, 32.0);
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MeleeAttackGoal(this, 1.2, true));
        goalSelector.addGoal(3, new FollowGoal());
        goalSelector.addGoal(5, new WaterAvoidingRandomStrollGoal(this, 0.8) {
            @Override public boolean canUse() { return "wander".equals(mode) && super.canUse(); }
        });
        goalSelector.addGoal(6, new LookAtPlayerGoal(this, Player.class, 8.0f));
        goalSelector.addGoal(7, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal(this));
        targetSelector.addGoal(2, new net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal<>(
                this, net.minecraft.world.entity.monster.Monster.class, true));
    }

    @Override
    public boolean removeWhenFarAway(double d) { return false; }

    private class FollowGoal extends Goal {
        private Player target;
        @Override public boolean canUse() {
            if (!"follow".equals(mode)) return false;
            target = level().getNearestPlayer(CompanionEntity.this, 48);
            return target != null && distanceToSqr(target) > 16;
        }
        @Override public boolean canContinueToUse() {
return "follow".equals(mode) && target != null && target.isAlive() && distanceToSqr(target) > 6;
        }
        @Override public void tick() {
            getLookControl().setLookAt(target);
            if (distanceToSqr(target) > 900) {
                teleportTo(target.getX() + 1, target.getY(), target.getZ() + 1);
            } else {
                getNavigation().moveTo(target, 1.15);
            }
        }
        @Override public void stop() { getNavigation().stop(); }
    }
}
