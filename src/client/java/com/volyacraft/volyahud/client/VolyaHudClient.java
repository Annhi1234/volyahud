package com.volyacraft.volyahud.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.gui.ConfigScreen;
import com.volyacraft.volyahud.client.hud.ArmorHud;
import com.volyacraft.volyahud.client.hud.BuffHud;
import com.volyacraft.volyahud.client.hud.OverlayQueue;
import com.volyacraft.volyahud.client.hud.PearlTrajectory;
import com.volyacraft.volyahud.client.hud.PvpRadar;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VolyaHudClient implements ClientModInitializer {

	public static final String MOD_ID = "volyahud";
	public static final Logger LOGGER = LoggerFactory.getLogger("VolyaHUD");

	private static KeyMapping openConfigKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("VolyaHUD ініціалізовано");

		// Ctrl+K за замовчуванням відкриває меню налаштувань HUD.
		openConfigKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.open_config",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_K,
				"category.volyahud.general"
		));

		// Головний малюнок 2D HUD (броня, PvP-радар, бафи цілі).
		HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.options.hideGui) return;
			HudConfig cfg = HudConfig.INSTANCE;

			if (cfg.ownArmorHud) {
				ArmorHud.renderOwn(graphics, mc);
			}
			if (cfg.otherPlayersArmorHud || cfg.healthTags) {
				ArmorHud.drawQueuedOverlays(graphics, mc);
			} else {
				OverlayQueue.drain(); // не залишаємо старі записи в черзі, якщо фіча вимкнена
			}
			if (cfg.pvpRadar) {
				PvpRadar.render(graphics, mc);
			}
			if (cfg.targetBuffsHud) {
				BuffHud.render(graphics, mc);
			}
			if (cfg.totemAlert) {
				TotemAlert.render(graphics, mc);
			}
		});

		// 3D-рендер у світі: броня/HP над гравцями, траєкторія перли, слід перли.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) return;
			HudConfig cfg = HudConfig.INSTANCE;

			if (cfg.otherPlayersArmorHud || cfg.healthTags || cfg.inventoryPeek) {
				ArmorHud.renderOthers(context, mc);
			}
			if (cfg.pearlTrajectory || cfg.pearlTrail) {
				PearlTrajectory.render(context, mc);
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openConfigKey.consumeClick()) {
				mc.setScreen(new ConfigScreen(mc.screen));
			}
			PearlTrajectory.tick(mc);
			if (HudConfig.INSTANCE.totemAlert) {
				TotemAlert.tick(mc);
			}
		});
	}
}
