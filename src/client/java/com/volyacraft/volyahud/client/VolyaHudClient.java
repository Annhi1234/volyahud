package com.volyacraft.volyahud.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.gui.ConfigScreen;
import com.volyacraft.volyahud.client.hud.ArmorHud;
import com.volyacraft.volyahud.client.hud.BuffHud;
import com.volyacraft.volyahud.client.hud.ChunkGridOverlay;
import com.volyacraft.volyahud.client.hud.FpsHud;
import com.volyacraft.volyahud.client.hud.OverlayQueue;
import com.volyacraft.volyahud.client.hud.PearlTrajectory;
import com.volyacraft.volyahud.client.hud.PvpRadar;
import com.volyacraft.volyahud.client.hud.TerritoryBorder;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VolyaHudClient implements ClientModInitializer {

	public static final String MOD_ID = "volyahud";
	public static final Logger LOGGER = LoggerFactory.getLogger("VolyaHUD");

	private static KeyMapping openConfigKey;
	private static KeyMapping toggleChunkGridKey;
	private static KeyMapping toggleTerritoryKey;
	private static KeyMapping claimChunkKey;

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

		// G — швидко увімкнути/вимкнути сітку меж чанка (для будівництва).
		toggleChunkGridKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.toggle_chunk_grid",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_G,
				"category.volyahud.general"
		));

		// H — швидко увімкнути/вимкнути показ кордону території ("міста").
		toggleTerritoryKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.toggle_territory",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_H,
				"category.volyahud.general"
		));

		// J — позначити/зняти позначку з чанка, у якому зараз стоїш, як "своя територія".
		claimChunkKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.claim_chunk",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_J,
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
			// БАГ-ФІКС: раніше цю панель малювали, лише якщо otherPlayersArmorHud
			// АБО healthTags були увімкнені — через це "Показ екіпіровки цілі"
			// (inventoryPeek) не малювався, навіть коли був увімкнений сам по собі.
			if (cfg.otherPlayersArmorHud || cfg.healthTags || cfg.inventoryPeek) {
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
			if (cfg.showFps || cfg.showCoordX || cfg.showCoordY || cfg.showCoordZ || cfg.showChunkCoords) {
				FpsHud.render(graphics, mc);
			}
			// Підписи "16 x 16" на стінах поточного чанка (самі лінії — у 3D, нижче).
			ChunkGridOverlay.renderHud(graphics, mc);
		});

		// 3D-рендер у світі: броня/HP над гравцями, траєкторія перли, слід перли,
		// сітка меж чанка та кордон позначеної території.
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
			ChunkGridOverlay.renderWorld(context, mc);
			TerritoryBorder.render(context, mc);
		});

		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			while (openConfigKey.consumeClick()) {
				mc.setScreen(new ConfigScreen(mc.screen));
			}
			while (toggleChunkGridKey.consumeClick()) {
				HudConfig cfg = HudConfig.INSTANCE;
				cfg.chunkGrid = !cfg.chunkGrid;
				cfg.save();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal(
							cfg.chunkGrid ? "§bСітка чанків: §aувімк" : "§bСітка чанків: §cвимк"), true);
				}
			}
			while (toggleTerritoryKey.consumeClick()) {
				HudConfig cfg = HudConfig.INSTANCE;
				cfg.territoryBorder = !cfg.territoryBorder;
				cfg.save();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal(
							cfg.territoryBorder ? "§eКордон території: §aувімк" : "§eКордон території: §cвимк"), true);
				}
			}
			while (claimChunkKey.consumeClick()) {
				if (mc.player != null && mc.level != null) {
					String dim = mc.level.dimension().location().toString();
					int cx = Mth.floor(mc.player.getX()) >> 4;
					int cz = Mth.floor(mc.player.getZ()) >> 4;
					boolean added = TerritoryManager.toggle(dim, cx, cz);
					mc.player.displayClientMessage(Component.literal(
							added ? "§aЧанк додано до твоєї території" : "§cЧанк прибрано з території"), true);
				}
			}
			PearlTrajectory.tick(mc);
			if (HudConfig.INSTANCE.totemAlert) {
				TotemAlert.tick(mc);
			}
		});
	}
}
