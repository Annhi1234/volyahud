package com.volyacraft.volyahud.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.gui.ConfigScreen;
import com.volyacraft.volyahud.client.gui.MapScreen;
import com.volyacraft.volyahud.client.hud.ArmorHud;
import com.volyacraft.volyahud.client.hud.BuffHud;
import com.volyacraft.volyahud.client.hud.ChunkGridOverlay;
import com.volyacraft.volyahud.client.hud.FpsHud;
import com.volyacraft.volyahud.client.hud.OverlayQueue;
import com.volyacraft.volyahud.client.hud.PearlTrajectory;
import com.volyacraft.volyahud.client.hud.PvpRadar;
import com.volyacraft.volyahud.client.hud.TerritoryBorder;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache;
import com.volyacraft.volyahud.client.hud.minimap.MinimapHud;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
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

	/**
	 * БАГ-ФІКС (важливий): раніше всі HUD-елементи малювались одним суцільним
	 * блоком коду без жодного захисту від помилок. Якщо ХОЧА Б ОДИН елемент
	 * кидав виняток (наприклад через дрібну відмінність рендер-API між
	 * патчами 1.21.x — це саме той ризик, про який попереджає README), ця
	 * помилка вилітала з усього HudRenderCallback/WorldRenderEvents одразу —
	 * і всі елементи ПІСЛЯ зламаного в тому ж списку просто переставали
	 * малюватись на кожному кадрі, хоча в налаштуваннях лишались "увімкнені".
	 * Саме так і виглядає "частина функцій не працює" ззовні.
	 *
	 * Тепер кожен елемент малюється ізольовано: помилка в одному НЕ впливає
	 * на інші, у лог пишеться один раз (а не 20+ разів на секунду), і в грі
	 * можна побачити конкретне повідомлення, яке саме гілку зламано.
	 */
	private static final java.util.Set<String> FAILED = java.util.concurrent.ConcurrentHashMap.newKeySet();

	private static void safe(String name, Runnable action) {
		if (FAILED.contains(name)) return; // вже зламався цього разу — не пробуємо на кожному кадрі знову
		try {
			action.run();
		} catch (Throwable t) {
			FAILED.add(name);
			LOGGER.error("VolyaHUD: елемент '{}' зламався і вимкнений до перезаходу в гру. " +
					"Це схоже на розбіжність рендер-API між патчами 1.21.x (див. README) — " +
					"скинь цей стек-трейс розробнику.", name, t);
			Minecraft mc = Minecraft.getInstance();
			if (mc.player != null) {
				mc.player.displayClientMessage(Component.literal(
						"§c[VolyaHUD] '" + name + "' зламався і вимкнений цю сесію (див. лог, F3+D)."), false);
			}
		}
	}

	private static KeyMapping openConfigKey;
	private static KeyMapping toggleChunkGridKey;
	private static KeyMapping toggleTerritoryKey;
	private static KeyMapping claimChunkKey;
	private static KeyMapping openMapKey;

	@Override
	public void onInitializeClient() {
		LOGGER.info("VolyaHUD ініціалізовано");

		// БАГ-ФІКС (коментар): тут раніше було написано "Ctrl+K", хоча насправді
		// біндиться сама клавіша K без модифікатора — виправлено лише опис,
		// логіка й так була правильною.
		// K за замовчуванням відкриває меню налаштувань HUD.
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

		// M — розгорнути повну карту (мінімапа й так завжди на екрані, якщо увімкнена).
		openMapKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.open_map",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_M,
				"category.volyahud.general"
		));

		// Головний малюнок 2D HUD (броня, PvP-радар, бафи цілі).
		HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.options.hideGui) return;
			HudConfig cfg = HudConfig.INSTANCE;

			if (cfg.ownArmorHud) {
				safe("Своя броня", () -> ArmorHud.renderOwn(graphics, mc));
			}
			// БАГ-ФІКС: раніше цю панель малювали, лише якщо otherPlayersArmorHud
			// АБО healthTags були увімкнені — через це "Показ екіпіровки цілі"
			// (inventoryPeek) не малювався, навіть коли був увімкнений сам по собі.
			if (cfg.otherPlayersArmorHud || cfg.healthTags || cfg.inventoryPeek) {
				safe("Броня/HP інших гравців", () -> ArmorHud.drawQueuedOverlays(graphics, mc));
			} else {
				OverlayQueue.drain(); // не залишаємо старі записи в черзі, якщо фіча вимкнена
			}
			if (cfg.pvpRadar) {
				safe("PvP-радар", () -> PvpRadar.render(graphics, mc));
			}
			if (cfg.targetBuffsHud) {
				safe("Бафи цілі", () -> BuffHud.render(graphics, mc));
			}
			if (cfg.totemAlert) {
				safe("Сповіщення про тотем", () -> TotemAlert.render(graphics, mc));
			}
			if (cfg.showFps || cfg.showCoordX || cfg.showCoordY || cfg.showCoordZ || cfg.showChunkCoords || cfg.showCompass) {
				safe("Інфо-панель", () -> FpsHud.render(graphics, mc));
			}
			// Підписи "16 x 16" на стінах поточного чанка (самі лінії — у 3D, нижче).
			safe("Сітка чанка (підписи)", () -> ChunkGridOverlay.renderHud(graphics, mc));

			safe("Мінімапа", () -> MinimapHud.render(graphics, mc));
		});

		// 3D-рендер у світі: броня/HP над гравцями, траєкторія перли, слід перли,
		// сітка меж чанка та кордон позначеної території.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null) return;
			HudConfig cfg = HudConfig.INSTANCE;

			if (cfg.otherPlayersArmorHud || cfg.healthTags || cfg.inventoryPeek) {
				safe("Проєкція броні/HP гравців", () -> ArmorHud.renderOthers(context, mc));
			}
			if (cfg.pearlTrajectory || cfg.pearlTrail) {
				safe("Траєкторія кидка", () -> PearlTrajectory.render(context, mc));
			}
			safe("Сітка чанка (лінії)", () -> ChunkGridOverlay.renderWorld(context, mc));
			safe("Кордон території", () -> TerritoryBorder.render(context, mc));
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
			while (openMapKey.consumeClick()) {
				if (mc.level != null && mc.screen == null) {
					mc.setScreen(new MapScreen(null));
				}
			}
			safe("Тік траєкторії кидка", () -> PearlTrajectory.tick(mc));
			if (HudConfig.INSTANCE.totemAlert) {
				safe("Тік сповіщення про тотем", () -> TotemAlert.tick(mc));
			}
			safe("Тік мінімапи", () -> MinimapHud.tick(mc));

			mapAutosaveTimer++;
			if (mapAutosaveTimer >= MAP_AUTOSAVE_INTERVAL_TICKS) {
				mapAutosaveTimer = 0;
				safe("Автозбереження карти", ChunkColorCache::saveWorld);
			}
		});

		// Знімаємо колір рельєфу одразу, щойно чанк доходить до клієнта — так мапа
		// поповнюється швидше, ніж чекати на періодичний опитувальний тік мінімапи.
		ClientChunkEvents.CHUNK_LOAD.register((level, chunk) -> ChunkColorCache.captureChunk(level, chunk));

		// Прив'язуємо збережену карту до конкретного світу (одиночна гра чи сервер),
		// щоб мапа з одного сервера не змішувалась із мапою іншого.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			ChunkColorCache.loadWorld(currentWorldKey(client));
			FAILED.clear(); // даємо елементам, які раніше зламались, новий шанс на новому заході в гру
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> ChunkColorCache.saveWorld());
	}

	private static int mapAutosaveTimer = 0;
	private static final int MAP_AUTOSAVE_INTERVAL_TICKS = 20 * 30; // раз на ~30 секунд

	/** Ідентифікатор поточного "світу" для збереження карти: адреса сервера або назва одиночної гри. */
	private static String currentWorldKey(Minecraft mc) {
		if (mc.getCurrentServer() != null) {
			return "server_" + mc.getCurrentServer().ip;
		}
		if (mc.getSingleplayerServer() != null) {
			return "singleplayer_" + mc.getSingleplayerServer().getWorldData().getLevelName();
		}
		return "unknown_world";
	}
}
