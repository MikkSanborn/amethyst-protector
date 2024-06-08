package com.example.mikksanborn.amethystprotector.client;

import java.io.File;
import java.io.IOException;
import java.util.*;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.block.Blocks;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.argument.BlockStateArgument;
import net.minecraft.command.argument.BlockStateArgumentType;
import net.minecraft.nbt.*;
import net.minecraft.registry.Registries;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;

import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;

import org.lwjgl.glfw.GLFW;

/**
 * The entire implementation of the AmethystProtector class
 *
 * @author Mikk Sanborn
 * @version ${version}
 */
public class AmethystProtectorClient implements ClientModInitializer {
    private static boolean isProtectionEnabled = true;
    private static KeyBinding protectionKey;
    private static final File configFile = new File(FabricLoader.getInstance().getConfigDir().toFile(), "protected_blocks.dat");
    private static Set<Block> protectedBlocks = new HashSet<>();

    /**
     * Run mod initializer
     */
    @Override
    public void onInitializeClient() {
        // Reload saved state, if it exists
        loadState();

        // Register toggle key keybinding
        protectionKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding("key.amethyst_helper.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_P,
                        "category.amethyst_protector.general")
            );

        // Register toggle keypress event
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (protectionKey.wasPressed()) {
                setIsProtectionEnabled(!getIsProtectionEnabled());

                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                if (p != null)
                    p.sendMessage(Text.literal("Amethyst Protection has been "
                                               + (isProtectionEnabled ? "enabled" : "disabled") + "!"), false);
            }
        });

        // Register cancel attack callback
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (isProtectionEnabled && protectedBlocks.contains(world.getBlockState(pos).getBlock())) {
                // Play a sound when the attack is cancelled
                ClientPlayerEntity p = MinecraftClient.getInstance().player;
                if (p != null) p.playSound(SoundEvents.BLOCK_ANVIL_BREAK, SoundCategory.PLAYERS, 1.0F, 1.0F);
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        // Register command
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(ClientCommandManager.literal("amethystprotector").executes(context -> { // /amethystprotector
                context.getSource().sendFeedback(Text.literal("""
                        /amethystprotector
                          - add <block> : add block to protection list
                          - remove <block> : remove block from protection list
                          - list : list protected blocks
                          - enable : enable block protection
                          - disable : disable block protection
                          - status : return current protection status
                          - reset : reset to default settings"""));

                return 0;
            }).then(ClientCommandManager.literal("list")
                    .executes(context -> { // /amethystprotector list
                List<String> protectedBlockNames = new ArrayList<>();
                protectedBlocks.forEach(block -> protectedBlockNames.add(block.getName().getString()));
                Collections.sort(protectedBlockNames);

                StringBuilder sb = new StringBuilder("Currently protected blocks:");
                protectedBlockNames.forEach(blockName -> sb.append("\n  ").append(blockName));
                if (protectedBlocks.isEmpty()) {
                    sb.append("\n  (None)");
                }
                context.getSource().sendFeedback(Text.literal(sb.toString()));
                return 1;
            })).then(ClientCommandManager.literal("reset")
                            .executes(context -> { // /amethystprotector reset
                setDefaults();
                context.getSource().sendFeedback(Text.literal("Reset to default settings!"));
                return 1;
            })).then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("block", BlockStateArgumentType.blockState(registryAccess))
                            .executes(context -> { // /amethystprotector add <block>
                Block b = context.getArgument("block", BlockStateArgument.class).getBlockState().getBlock();
                context.getSource().sendFeedback(Text.literal(
                        (addProtectedBlock(b) ? "Now protecting " : "Already protecting") + b.getName().getString()));
                return 1;
            }))).then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("block", BlockStateArgumentType.blockState(registryAccess))
                            .executes(context -> { // /amethystprotector remove <block>
                Block b = context.getArgument("block", BlockStateArgument.class).getBlockState().getBlock();
                context.getSource().sendFeedback(Text.literal(
                        (removeProtectedBlock(b) ? "No longer protecting " : "Not protecting ") + b.getName().getString()));
                return 1;
            }))).then(ClientCommandManager.literal("enable")
                    .executes(context -> { // /amethystprotector enable
                setIsProtectionEnabled(true);
                context.getSource().sendFeedback(Text.literal("Block protection enabled!"));
                return 1;
            })).then(ClientCommandManager.literal("disable")
                    .executes(context -> { // /amethystprotector disable
                setIsProtectionEnabled(false);
                context.getSource().sendFeedback(Text.literal("Block protection disabled!"));
                return 1;
            })).then(ClientCommandManager.literal("status")
                    .executes(context -> { // /amethystprotector status
                context.getSource().sendFeedback(Text.literal("Block protection is "
                                                              + (getIsProtectionEnabled() ? "enabled" : "disabled") + "!"));
                return getIsProtectionEnabled() ? 1 : 0;
            })));
        });
    }

    /**
     * Add a Block to protect
     *
     * @param block the block to be protected
     * @return if block was not present before adding (successfully added)
     */
    public static boolean addProtectedBlock(Block block) {
        if (block == null || block == Blocks.AIR) {
            return true;
        }
        boolean wasAdded = protectedBlocks.add(block);
        saveState();
        return wasAdded;
    }

    /**
     * Remove a Block to protect
     *
     * @param block the block to not protect
     * @return if block was present before removal (successfully removed)
     */
    public static boolean removeProtectedBlock(Block block) {
        if (block == null) {
            return false;
        }
        boolean wasRemoved = protectedBlocks.remove(block);
        saveState();
        return wasRemoved;
    }

    /**
     * Sets isProtectionEnabled
     *
     * @param toSet the value to set
     */
    private static void setIsProtectionEnabled(boolean toSet) {
        isProtectionEnabled = toSet;
        saveState();
    }

    /**
     * Checks if the specified Block is protected
     *
     * @param block the Block to check
     * @return block's protection status
     */
    public static boolean isProtectedBlock(Block block) {
        return protectedBlocks.contains(block);
    }

    /**
     * Checks if protection is enabled
     *
     * @return isProtectionEnabled
     */
    public static boolean getIsProtectionEnabled() {
        return isProtectionEnabled;
    }

    /**
     * Save the state of {@link AmethystProtectorClient} (protected blocks and isProtectionEnabled) to the disk
     */
    private static void saveState() {
        NbtCompound nbt = new NbtCompound();
        NbtList nbtProtectedBlocksList = new NbtList();

        protectedBlocks.forEach(b -> nbtProtectedBlocksList.add(NbtString.of(Registries.BLOCK.getId(b).toString())));

        nbt.put("protectedBlocks", nbtProtectedBlocksList);
        nbt.putBoolean("isProtectionEnabled", isProtectionEnabled);

        try {
            NbtIo.write(nbt, configFile);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Load the state of {@link AmethystProtectorClient} (protected blocks and isProtectionEnabled) from the disk
     */
    private static void loadState() {
        try {
            NbtCompound nbt = NbtIo.read(configFile);
            if (nbt == null) {
                setDefaults();
                return;
            }
            NbtList nbtProtectedBlocksList = nbt.getList("protectedBlocks", NbtElement.STRING_TYPE);

            protectedBlocks = new HashSet<>();
            nbtProtectedBlocksList.forEach(nbtElement -> {
                Block b = Registries.BLOCK.get(new Identifier(nbtElement.asString()));
                if (b != Blocks.AIR) {
                    protectedBlocks.add(b);
                }
            });

            isProtectionEnabled = nbt.getBoolean("isProtectionEnabled");
        } catch (IOException e) {
            setDefaults();
        }
    }

    /**
     * Reset to the default values
     */
    private static void setDefaults() {
        protectedBlocks = new HashSet<>();
        protectedBlocks.add(Blocks.BUDDING_AMETHYST);
        isProtectionEnabled = true;
        saveState();
    }

}
