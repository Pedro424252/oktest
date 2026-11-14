package com.example.lootguard;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.block.Blocks;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Yakinda bir oyuncu olunce LOCK_MS kadar sure:
 *  - ekranda "X oldu" yazar ve eline kilic alir
 *  - kristal / glowstone / anchor kullanimini ve kristale vurmayi engeller
 */
public class LootGuard implements ClientModInitializer {
    public static volatile boolean enabled = true;
    public static volatile int lockSeconds = 5;  // kilit suresi (saniye), GUI'den ayarlanir
    private static KeyBinding openKey;
    private static final double RANGE = 30.0;   // kac blok yakindaki oluler algilanir

    private static volatile long lockUntil = 0;
    private static final Set<UUID> handled = new HashSet<>();
    private static String lastName = "";

    public static boolean locked() {
        return enabled && System.currentTimeMillis() < lockUntil;
    }

    private static boolean bad(ItemStack s) {
        return s.isOf(Items.END_CRYSTAL) || s.isOf(Items.GLOWSTONE) || s.isOf(Items.RESPAWN_ANCHOR);
    }

    private static void switchToSword(MinecraftClient client) {
        var inv = client.player.getInventory();
        for (int i = 0; i < 9; i++) {
            if (inv.getStack(i).isIn(ItemTags.SWORDS)) {
                inv.setSelectedSlot(i);
                return;
            }
        }
    }

    @Override
    public void onInitializeClient() {
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of("lootguard", "main"));
        openKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.lootguard.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, category));

        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (screen instanceof GameMenuScreen) {
                Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Loot Guard"),
                        b -> MinecraftClient.getInstance().setScreen(new LootGuardScreen()))
                        .dimensions(5, 5, 80, 20).build());
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (openKey.wasPressed()) {
                client.setScreen(new LootGuardScreen());
            }
            if (client.player == null || client.world == null) {
                handled.clear();
                return;
            }
            if (!enabled) {
                handled.clear();
                return;
            }
            for (AbstractClientPlayerEntity p : client.world.getPlayers()) {
                if (p == client.player) continue;
                if (!p.isDead()) {
                    handled.remove(p.getUuid());
                    continue;
                }
                if (client.player.squaredDistanceTo(p) > RANGE * RANGE) continue;
                if (handled.add(p.getUuid())) {
                    lockUntil = System.currentTimeMillis() + lockSeconds * 1000L;
                    lastName = p.getName().getString();
                    switchToSword(client);
                }
            }
            if (locked() && client.player.age % 5 == 0) {
                long left = Math.max(0, lockUntil - System.currentTimeMillis());
                client.inGameHud.setOverlayMessage(
                        Text.literal(lastName + " OLDU - loot korunuyor (" + (left / 1000 + 1) + " sn)"), false);
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!world.isClient() || !locked()) return ActionResult.PASS;
            if (bad(player.getStackInHand(hand))
                    || world.getBlockState(hit.getBlockPos()).isOf(Blocks.RESPAWN_ANCHOR)) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });

        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (world.isClient() && locked() && entity instanceof EndCrystalEntity) {
                return ActionResult.FAIL;
            }
            return ActionResult.PASS;
        });
    }
}
