package xyz.nucleoid.disguiselib.impl.mixin;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import com.mojang.datafixers.util.Pair;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.s2c.play.EntitiesDestroyS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityEquipmentUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityPositionS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySetHeadYawS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusEffectS2CPacket;
import net.minecraft.network.packet.s2c.play.EntityTrackerUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ExperienceBarUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.HealthUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerRespawnS2CPacket;
import net.minecraft.network.packet.s2c.play.TeamS2CPacket;
import net.minecraft.server.PlayerManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerChunkManager;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.server.world.ThreadedAnvilChunkStorage;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.registry.Registry;
import net.minecraft.util.registry.RegistryKey;
import net.minecraft.world.GameMode;
import net.minecraft.world.World;
import net.minecraft.world.biome.source.BiomeAccess;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xyz.nucleoid.disguiselib.api.DisguiseUtils;
import xyz.nucleoid.disguiselib.api.EntityDisguise;
import xyz.nucleoid.disguiselib.impl.mixin.accessor.EntityTrackerEntryAccessor;
import xyz.nucleoid.disguiselib.impl.mixin.accessor.PlayerListS2CPacketAccessor;
import xyz.nucleoid.disguiselib.impl.mixin.accessor.ThreadedAnvilChunkStorageAccessor;

import java.util.*;
import java.util.stream.Collectors;

import static net.minecraft.entity.EntityType.PLAYER;
import static net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.ADD_PLAYER;
import static net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.REMOVE_PLAYER;
import static xyz.nucleoid.disguiselib.impl.DisguiseLib.DISGUISE_TEAM;
import static xyz.nucleoid.disguiselib.impl.mixin.accessor.PlayerEntityAccessor.getPLAYER_MODEL_PARTS;

@Mixin(Entity.class)
public abstract class EntityMixin_Disguise implements EntityDisguise, DisguiseUtils {

    @Unique
    private final Entity disguiselib$entity = (Entity) (Object) this;
    @Shadow
    public World world;
    @Shadow
    protected UUID uuid;
    @Unique
    private Entity disguiselib$disguiseEntity;
    @Unique
    private int disguiselib$ticks;
    @Unique
    private EntityType<?> disguiselib$disguiseType;
    @Unique
    private GameProfile disguiselib$profile;
    @Unique
    private boolean disguiselib$trueSight = false;

    @Shadow
    public abstract EntityType<?> getType();

    @Shadow
    public abstract float getHeadYaw();

    @Shadow
    public abstract Text getName();

    @Shadow
    public abstract DataTracker getDataTracker();

    @Shadow
    @Nullable
    public abstract Text getCustomName();

    @Shadow
    public abstract boolean isCustomNameVisible();

    @Shadow public abstract boolean isSprinting();

    @Shadow public abstract boolean isSneaking();

    @Shadow public abstract boolean isSwimming();

    @Shadow public abstract boolean isGlowing();

    @Shadow public abstract boolean isSilent();

    @Shadow public abstract EntityPose getPose();

    @Shadow private int id;

    @Shadow public abstract int getId();

    @Shadow public abstract boolean isOnFire();

    @Shadow public abstract Text getDisplayName();

    /**
     * Tells you the disguised status.
     *
     * @return true if entity is disguised, otherwise false.
     */
    @Override
    public boolean isDisguised() {
        return this.disguiselib$disguiseEntity != null;
    }

    /**
     * Sets entity's disguise from {@link EntityType}
     *
     * @param entityType the type to disguise this entity into
     */
    @Override
    public void disguiseAs(EntityType<?> entityType) {
        this.disguiselib$disguiseType = entityType;

        PlayerManager manager = this.world.getServer().getPlayerManager();

        if(this.disguiselib$disguiseEntity != null && this.disguiselib$disguiseEntity.getType() != entityType && this.disguiselib$entity instanceof ServerPlayerEntity) {
            this.disguiselib$hideSelfView();
        }

        if(entityType == PLAYER) {
            if(this.disguiselib$profile == null)
                this.setGameProfile(new GameProfile(this.uuid, this.getDisplayName().getString()));
            this.disguiselib$constructFakePlayer(this.disguiselib$profile);
        } else {
            // Why null check? Well, if entity was disguised via EntityDisguise#disguiseAs(Entity), this field is already set
            if(this.disguiselib$disguiseEntity == null || this.disguiselib$disguiseEntity.getType() != entityType)
                this.disguiselib$disguiseEntity = entityType.create(world);

            if(this.disguiselib$profile != null) {
                // Previous type was player, we have to send a player remove packet
                PlayerListS2CPacket listPacket = new PlayerListS2CPacket(REMOVE_PLAYER);
                PlayerListS2CPacketAccessor listPacketAccessor = (PlayerListS2CPacketAccessor) listPacket;
                listPacketAccessor.setEntries(Arrays.asList(new PlayerListS2CPacket.Entry(this.disguiselib$profile, 0, GameMode.SURVIVAL, Text.literal(this.disguiselib$profile.getName()), null)));
                manager.sendToAll(listPacket);
            }

            // We don't need gameprofile anymore
            this.disguiselib$profile = null;
        }


        // Fix some client predictions
        if(this.disguiselib$disguiseEntity instanceof MobEntity)
            ((MobEntity) this.disguiselib$disguiseEntity).setAiDisabled(true);

        RegistryKey<World> worldRegistryKey = this.world.getRegistryKey();

        // Minor datatracker thingies
        this.updateTrackedData();

        // Updating entity on the client
        manager.sendToDimension(new EntitiesDestroyS2CPacket(this.id), worldRegistryKey);
        manager.sendToDimension(new EntitySpawnS2CPacket(this.disguiselib$entity), worldRegistryKey); // will be replaced by network handler

        manager.sendToDimension(new EntityTrackerUpdateS2CPacket(this.id, this.getDataTracker(), true), worldRegistryKey);
        manager.sendToDimension(new EntityEquipmentUpdateS2CPacket(this.id, this.disguiselib$getEquipment()), worldRegistryKey); // Reload equipment
        manager.sendToDimension(new EntitySetHeadYawS2CPacket(this.disguiselib$entity, (byte) ((int) (this.getHeadYaw() * 256.0F / 360.0F))), worldRegistryKey); // Head correction
    }

    /**
     * Sets entity's disguise from {@link Entity}
     *
     * @param entity the entity to disguise into
     */
    @Override
    public void disguiseAs(Entity entity) {
        if(this.disguiselib$disguiseEntity != null && this.disguiselib$entity instanceof ServerPlayerEntity) {
            // Removing previous disguise if this is player
            // (we have it saved under a separate id)
            this.disguiselib$hideSelfView();
        }

        this.disguiselib$disguiseEntity = entity;
        if(entity instanceof PlayerEntity) {
            this.setGameProfile(((PlayerEntity) entity).getGameProfile());
        }
        this.disguiseAs(entity.getType());
    }

    /**
     * Clears the disguise - sets the {@link EntityMixin_Disguise#disguiselib$disguiseType} back to original.
     */
    @Override
    public void removeDisguise() {
        if(this.disguiselib$disguiseEntity != null && this.disguiselib$entity instanceof ServerPlayerEntity) {
            // Removing previous disguise if this is player
            // (we have it saved under a separate id)
            this.disguiselib$hideSelfView();
        }
        // Disguising entity as itself
        this.disguiselib$disguiseEntity = this.disguiselib$entity;
        this.disguiselib$disguiseType = this.getType();

        this.disguiseAs(this.getType());

        // Setting as not-disguised
        this.disguiselib$disguiseEntity = null;
    }

    /**
     * Gets the disguise entity type
     *
     * @return disguise entity type or real type if there's no disguise
     */
    @Override
    public EntityType<?> getDisguiseType() {
        return this.disguiselib$disguiseType;
    }

    /**
     * Gets the disguise entity.
     *
     * @return disguise entity or null if there's no disguise
     */
    @Nullable
    @Override
    public Entity getDisguiseEntity() {
        return this.disguiselib$disguiseEntity;
    }

    /**
     * Whether disguise type entity is an instance of {@link LivingEntity}.
     *
     * @return true if the disguise type is an instance of {@link LivingEntity}, otherwise false.
     */
    @Override
    public boolean disguiseAlive() {
        return this.disguiselib$disguiseEntity instanceof LivingEntity;
    }

    /**
     * Whether this entity can bypass the
     * "disguises" and see entities normally
     * Intended more for admins (to not get trolled themselves).
     *
     * @return if entity can be "fooled" by disguise
     */
    @Override
    public boolean hasTrueSight() {
        return this.disguiselib$trueSight;
    }

    /**
     * Toggles true sight - whether entity
     * can see disguises or not.
     * Intended more for admins (to not get trolled themselves).
     *
     * @param trueSight if entity should not see disguises
     */
    @Override
    public void setTrueSight(boolean trueSight) {
        this.disguiselib$trueSight = trueSight;
    }

    /**
     * Gets the {@link GameProfile} for disguised entity,
     * used when disguising as player.
     *
     * @return GameProfile of the entity.
     */
    @Override
    public @Nullable GameProfile getGameProfile() {
        return this.disguiselib$profile;
    }

    /**
     * Sets the GameProfile
     *
     * @param gameProfile a new profile for the entity.
     */
    @Override
    public void setGameProfile(@Nullable GameProfile gameProfile) {
        this.disguiselib$profile = gameProfile;
        if(gameProfile != null) {
            String name = gameProfile.getName();
            if(name.length() > 16) {
                // Minecraft kicks players on such profile name received
                name = name.substring(0, 16);
                PropertyMap properties = gameProfile.getProperties();
                this.disguiselib$profile = new GameProfile(gameProfile.getId(), name);
                Collection<Property> textures = properties.get("textures");
                if(!textures.isEmpty())
                    this.disguiselib$profile.getProperties().put("textures", textures.stream().findFirst().get());
            }
        }

        this.disguiselib$sendProfileUpdates();
    }

    /**
     * Hides player's self-disguise-entity
     */
    @Unique
    private void disguiselib$hideSelfView() {
        // Removing previous disguise if this is player
        // (we have it saved under a separate id)
        ServerPlayerEntity player = (ServerPlayerEntity) this.disguiselib$entity;
        player.networkHandler.sendPacket(new EntitiesDestroyS2CPacket(this.disguiselib$disguiseEntity.getId()));

        TeamS2CPacket removeTeamPacket = TeamS2CPacket.changePlayerTeam(DISGUISE_TEAM, player.getGameProfile().getName(), TeamS2CPacket.Operation.REMOVE);
        player.networkHandler.sendPacket(removeTeamPacket);
    }

    /**
     * Constructs fake player entity for use
     * when entities are disguised as players.
     *
     * @param profile gameprofile to use for new player.
     */
    @Unique
    private void disguiselib$constructFakePlayer(@NotNull GameProfile profile) {
        this.disguiselib$disguiseEntity = new ServerPlayerEntity(world.getServer(), (ServerWorld) world, profile, null);
        this.disguiselib$disguiseEntity.getDataTracker().set(getPLAYER_MODEL_PARTS(), (byte) 0x7f);
    }

    /**
     * Gets equipment as list of {@link Pair Pairs}.
     * Requires entity to be an instanceof {@link LivingEntity}.
     *
     * @return equipment list of pairs.
     */
    @Unique
    private List<Pair<EquipmentSlot, ItemStack>> disguiselib$getEquipment() {
        if(disguiselib$entity instanceof LivingEntity)
            return Arrays.stream(EquipmentSlot.values()).map(slot -> new Pair<>(slot, ((LivingEntity) disguiselib$entity).getEquippedStack(slot))).collect(Collectors.toList());
        return Collections.emptyList();
    }

    /**
     * Updates the entity's GameProfile for other clients
     */
    @Unique
    private void disguiselib$sendProfileUpdates() {
        PlayerListS2CPacket packet = new PlayerListS2CPacket(REMOVE_PLAYER);
        //noinspection ConstantConditions
        PlayerListS2CPacketAccessor accessor = (PlayerListS2CPacketAccessor) packet;
        accessor.setEntries(Arrays.asList(new PlayerListS2CPacket.Entry(this.disguiselib$profile, 0, GameMode.SURVIVAL, Text.literal(this.disguiselib$profile.getName()), null)));

        PlayerManager playerManager = this.world.getServer().getPlayerManager();
        playerManager.sendToAll(packet);
        accessor.setAction(ADD_PLAYER);
        playerManager.sendToAll(packet);

        ServerChunkManager manager = (ServerChunkManager) this.world.getChunkManager();
        ThreadedAnvilChunkStorage storage = manager.threadedAnvilChunkStorage;
        EntityTrackerEntryAccessor trackerEntry = ((ThreadedAnvilChunkStorageAccessor) storage).getEntityTrackers().get(this.getId());
        if(trackerEntry != null)
            trackerEntry.getListeners().forEach(tracking -> trackerEntry.getEntry().startTracking(tracking.getPlayer()));

        // Changing entity on client
        if(this.disguiselib$entity instanceof ServerPlayerEntity player) {
            ServerWorld targetWorld = player.getWorld();

            player.networkHandler.sendPacket(new PlayerRespawnS2CPacket(
                    targetWorld.getDimensionKey(),  // getDimension()
                    targetWorld.getRegistryKey(),
                    BiomeAccess.hashSeed(targetWorld.getSeed()),
                    player.interactionManager.getGameMode(),
                    player.interactionManager.getPreviousGameMode(),
                    targetWorld.isDebugWorld(),
                    targetWorld.isFlat(),
                    true,
                    Optional.empty()
            ));
            player.networkHandler.requestTeleport(player.getX(), player.getY(), player.getZ(), player.getYaw(), player.getPitch());

            player.server.getPlayerManager().sendCommandTree(player);

            player.networkHandler.sendPacket(new ExperienceBarUpdateS2CPacket(player.experienceProgress, player.totalExperience, player.experienceLevel));
            player.networkHandler.sendPacket(new HealthUpdateS2CPacket(player.getHealth(), player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel()));

            for (StatusEffectInstance statusEffect : player.getStatusEffects()) {
                player.networkHandler.sendPacket(new EntityStatusEffectS2CPacket(player.getId(), statusEffect));
            }

            player.sendAbilitiesUpdate();
            playerManager.sendWorldInfo(player, targetWorld);
            playerManager.sendPlayerStatus(player);
        }
    }

    /**
     * Updates custom name and its visibility.
     * Also sets no-gravity to true in order
     * to prevent the client from predicting
     * the entity position and velocity.
     */
    @Override
    public void updateTrackedData() {
        // Minor datatracker thingies
        this.disguiselib$disguiseEntity.setNoGravity(true);
        this.disguiselib$disguiseEntity.setCustomName(this.getCustomName());
        this.disguiselib$disguiseEntity.setCustomNameVisible(this.isCustomNameVisible());
        this.disguiselib$disguiseEntity.setSprinting(this.isSprinting());
        this.disguiselib$disguiseEntity.setSneaking(this.isSneaking());
        this.disguiselib$disguiseEntity.setSwimming(this.isSwimming());
        this.disguiselib$disguiseEntity.setGlowing(this.isGlowing());
        this.disguiselib$disguiseEntity.setOnFire(this.isOnFire());
        this.disguiselib$disguiseEntity.setSilent(this.isSilent());
        this.disguiselib$disguiseEntity.setPose(this.getPose());
    }

    /**
     * Sends additional move packets to the client if
     * entity is disguised.
     * Prevents client desync and fixes "blocky" movement.
     */
    @Inject(method = "tick()V", at = @At("TAIL"))
    private void postTick(CallbackInfo ci) {
        // Fixes #2, also makes non-living entities update their pos
        // more than once per second -> movement isn't as "blocky"
        if(this.isDisguised()) {
            if(this.world.getServer() != null && !(this.disguiselib$disguiseEntity instanceof LivingEntity) && !(this.disguiselib$entity instanceof PlayerEntity))
                this.world.getServer().getPlayerManager().sendToDimension(new EntityPositionS2CPacket(this.disguiselib$entity), this.world.getRegistryKey());
            else if(this.disguiselib$entity instanceof ServerPlayerEntity && ++this.disguiselib$ticks % 40 == 0 && this.disguiselib$disguiseEntity != null) {
                // "Disguised as" message
                MutableText msg = Text.literal("You are disguised as ")
                        .append(Text.translatable(this.disguiselib$disguiseEntity.getType().getTranslationKey()))
                        .formatted(Formatting.GREEN);

                ((ServerPlayerEntity) this.disguiselib$entity).sendMessage(msg, true);
                this.disguiselib$ticks = 0;
            }
        }

    }

    /**
     * If entity is disguised as player, we need to send a player
     * remove packet on death as well, otherwise tablist still contains
     * it.
     */
    @Inject(
            method = "discard()V",
            at = @At("TAIL")
    )
    private void onRemove(CallbackInfo ci) {
        if(this.isDisguised() && this.disguiselib$profile != null) {
            // If entity was killed, we should also send a remove player action packet
            PlayerListS2CPacket packet = new PlayerListS2CPacket(REMOVE_PLAYER);
            PlayerListS2CPacketAccessor listS2CPacketAccessor = (PlayerListS2CPacketAccessor) packet;

            GameProfile profile = new GameProfile(this.disguiselib$entity.getUuid(), this.getName().getString());
            // Arrays.asList is needed as we PlayerList needs mutable list
            listS2CPacketAccessor.setEntries(Arrays.asList(new PlayerListS2CPacket.Entry(profile, 0, GameMode.SURVIVAL, this.getName(), null)));

            PlayerManager manager = this.world.getServer().getPlayerManager();
            manager.sendToAll(packet);
        }
    }

    /**
     * Takes care of loading the fake entity data from tag.
     *
     * @param tag tag to load data from.
     */
    @Inject(
            method = "readNbt(Lnet/minecraft/nbt/NbtCompound;)V",
            at = @At("TAIL")
    )
    private void fromTag(NbtCompound tag, CallbackInfo ci) {
        NbtCompound disguiseTag = (NbtCompound) tag.get("DisguiseLib");

        if(disguiseTag != null) {
            Identifier disguiseTypeId = new Identifier(disguiseTag.getString("DisguiseType"));
            this.disguiselib$disguiseType = Registry.ENTITY_TYPE.get(disguiseTypeId);

            if(this.disguiselib$disguiseType == PLAYER) {
                this.setGameProfile(new GameProfile(this.uuid, this.getName().getString()));
                this.disguiselib$constructFakePlayer(this.disguiselib$profile);
            } else {
                NbtCompound disguiseEntityTag = disguiseTag.getCompound("DisguiseEntity");
                if(!disguiseEntityTag.isEmpty())
                    this.disguiselib$disguiseEntity = EntityType.loadEntityWithPassengers(disguiseEntityTag, this.world, (entityx) -> entityx);
            }
        }
    }

    /**
     * Takes care of saving the fake entity data to tag.
     *
     * @param tag tag to save data to.
     */
    @Inject(
            method = "writeNbt(Lnet/minecraft/nbt/NbtCompound;)Lnet/minecraft/nbt/NbtCompound;",
            at = @At("TAIL")
    )
    private void toTag(NbtCompound tag, CallbackInfoReturnable<NbtCompound> cir) {
        if(this.isDisguised()) {
            NbtCompound disguiseTag = new NbtCompound();

            disguiseTag.putString("DisguiseType", Registry.ENTITY_TYPE.getId(this.disguiselib$disguiseType).toString());

            if(this.disguiselib$disguiseEntity != null && !this.disguiselib$entity.equals(this.disguiselib$disguiseEntity)) {
                NbtCompound disguiseEntityTag = new NbtCompound();
                this.disguiselib$disguiseEntity.writeNbt(disguiseEntityTag);

                Identifier identifier = Registry.ENTITY_TYPE.getId(this.disguiselib$disguiseEntity.getType());
                disguiseEntityTag.putString("id", identifier.toString());

                disguiseTag.put("DisguiseEntity", disguiseEntityTag);
            }

            tag.put("DisguiseLib", disguiseTag);
        }
    }
}
