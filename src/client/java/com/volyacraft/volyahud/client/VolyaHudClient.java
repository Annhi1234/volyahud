package com.volyacraft.volyahud.client;

import com.mojang.blaze3d.platform.InputConstants;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.gui.ConfigScreen;
import com.volyacraft.volyahud.client.gui.MapScreen;
import com.volyacraft.volyahud.client.hud.ArmorHud;
import com.volyacraft.volyahud.client.hud.BuffHud;
import com.volyacraft.volyahud.client.hud.ChunkGridOverlay;
import com.volyacraft.volyahud.client.hud.CombatTimer;
import com.volyacraft.volyahud.client.hud.DamageMeter;
import com.volyacraft.volyahud.client.hud.DynamicLight;
import com.volyacraft.volyahud.client.hud.FpsHud;
import com.volyacraft.volyahud.client.hud.OverlayQueue;
import com.volyacraft.volyahud.client.hud.PlayerFilter;
import com.volyacraft.volyahud.client.hud.PearlTrajectory;
import com.volyacraft.volyahud.client.hud.PvpRadar;
import com.volyacraft.volyahud.client.hud.TerritoryBorder;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache;
import com.volyacraft.volyahud.client.hud.minimap.MinimapHud;
import com.volyacraft.volyahud.client.tooltip.WeaponDamageTooltip;
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
	private static KeyMapping toggleHudKey;
	private static KeyMapping quickWaypointKey;

	/** Та сама палітра кольорів міток, що й у MapScreen — щоб швидкі мітки з клавіші виглядали так само, як поставлені вручну на карті. */
	private static final int[] WAYPOINT_COLORS = {
			0xFFFFFF, 0xFF5555, 0x55FF55, 0x5599FF, 0xFFD700, 0xFF55FF, 0x55FFFF
	};

	/**
	 * НОВА ФУНКЦІЯ: швидке приховування ВСЬОГО кастомного HUD одразу (для
	 * чистих скріншотів/відео), не чіпаючи ванільний F1 і не вимикаючи
	 * кожен тумблер окремо в меню. За замовчуванням клавіша "." (крапка).
	 */
	private static boolean hudHidden = false;

	@Override
	public void onInitializeClient() {
		LOGGER.info("VolyaHUD ініціалізовано");

		// Тултип зброї: розрахунок реальної шкоди по HP (база + чари + крит).
		WeaponDamageTooltip.register();

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

		// НОВА ФУНКЦІЯ: "." — миттєво приховати/показати ВЕСЬ кастомний HUD
		// одразу (для чистих скріншотів/відео), не чіпаючи налаштування
		// (усі тумблери лишаються такими ж, як були — це лише тимчасова
		// візуальна пауза до повторного натискання чи виходу з гри).
		toggleHudKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.toggle_hud",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_PERIOD,
				"category.volyahud.general"
		));

		// НОВА ФУНКЦІЯ: N — миттєво поставити мітку на карту прямо там, де
		// зараз стоїш, без відкриття повної карти. Назву можна змінити пізніше
		// в меню карти (M) — тут головне швидкість, щоб не втрачати позицію,
		// поки біжиш кудись у бою чи досліджуєш територію.
		quickWaypointKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
				"key.volyahud.quick_waypoint",
				InputConstants.Type.KEYSYM,
				GLFW.GLFW_KEY_N,
				"category.volyahud.general"
		));

		// Головний малюнок 2D HUD (броня, PvP-радар, бафи цілі).
		HudRenderCallback.EVENT.register((graphics, tickDelta) -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || mc.options.hideGui || hudHidden) return;
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
			if (cfg.showFps || cfg.showCoordX || cfg.showCoordY || cfg.showCoordZ || cfg.showChunkCoords || cfg.showCompass || cfg.combatTimer) {
				safe("Інфо-панель", () -> FpsHud.render(graphics, mc));
			}
			if (cfg.damageMeter) {
				safe("Реальна шкода (крит)", () -> DamageMeter.render(graphics, mc));
			}
			// Підписи "16 x 16" на стінах поточного чанка (самі лінії — у 3D, нижче).
			safe("Сітка чанка (підписи)", () -> ChunkGridOverlay.renderHud(graphics, mc));

			safe("Мінімапа", () -> MinimapHud.render(graphics, mc));
		});

		// 3D-рендер у світі: броня/HP над гравцями, траєкторія перли, слід перли,
		// сітка меж чанка та кордон позначеної території.
		WorldRenderEvents.AFTER_ENTITIES.register(context -> {
			Minecraft mc = Minecraft.getInstance();
			if (mc.player == null || hudHidden) return;
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
			// БАГ-ФІКС: KeyMapping у ваніли реагує на "сирий" GLFW-івент клавіші
			// незалежно від того, чи відкритий екран чату/інвентаря — тобто
			// набираючи в чаті повідомлення з літерами g/h/j/k/m чи крапкою,
			// гравець мимоволі смикав ці ж самі гарячі клавіші VolyaHUD.
			// Тепер усі перемикачі спрацьовують лише тоді, коли жоден екран
			// не відкритий (те саме правило, що вже й так діяло для openMapKey).
			boolean noScreen = mc.screen == null;

			while (openConfigKey.consumeClick()) {
				if (noScreen) mc.setScreen(new ConfigScreen(mc.screen));
			}
			while (toggleChunkGridKey.consumeClick()) {
				if (!noScreen) continue;
				HudConfig cfg = HudConfig.INSTANCE;
				cfg.chunkGrid = !cfg.chunkGrid;
				cfg.save();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal(
							cfg.chunkGrid ? "§bСітка чанків: §aувімк" : "§bСітка чанків: §cвимк"), true);
				}
			}
			while (toggleTerritoryKey.consumeClick()) {
				if (!noScreen) continue;
				HudConfig cfg = HudConfig.INSTANCE;
				cfg.territoryBorder = !cfg.territoryBorder;
				cfg.save();
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal(
							cfg.territoryBorder ? "§eКордон території: §aувімк" : "§eКордон території: §cвимк"), true);
				}
			}
			while (claimChunkKey.consumeClick()) {
				if (!noScreen) continue;
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
				if (noScreen && mc.level != null) {
					mc.setScreen(new MapScreen(null));
				}
			}
			while (toggleHudKey.consumeClick()) {
				if (!noScreen) continue;
				hudHidden = !hudHidden;
				if (mc.player != null) {
					mc.player.displayClientMessage(Component.literal(
							hudHidden ? "§7VolyaHUD: §cприховано" : "§7VolyaHUD: §aпоказано"), true);
				}
			}
			while (quickWaypointKey.consumeClick()) {
				if (!noScreen) continue;
				if (mc.player != null && mc.level != null) {
					HudConfig cfg = HudConfig.INSTANCE;
					String dim = mc.level.dimension().location().toString();
					java.util.List<HudConfig.Waypoint> list = cfg.waypointsFor(dim);
					int color = WAYPOINT_COLORS[list.size() % WAYPOINT_COLORS.length];
					String name = "Мітка " + (list.size() + 1);
					list.add(new HudConfig.Waypoint(name,
							Mth.floor(mc.player.getX()), Mth.floor(mc.player.getY()), Mth.floor(mc.player.getZ()),
							color));
					cfg.save();
					mc.player.displayClientMessage(Component.literal(
							"§a\u2691 Мітку \"" + name + "\" додано на карту. Перейменувати можна в меню карти (M)."), true);
				}
			}
			safe("Тік траєкторії кидка", () -> PearlTrajectory.tick(mc));
			if (HudConfig.INSTANCE.combatTimer) {
				safe("Тік таймера бою", () -> CombatTimer.tick(mc));
			}
			if (HudConfig.INSTANCE.totemAlert) {
				safe("Тік сповіщення про тотем", () -> TotemAlert.tick(mc));
			}
			safe("Тік мінімапи", () -> MinimapHud.tick(mc));
			// НОВА ФУНКЦІЯ: динамічне світло від факела/ліхтаря/тотема в руці.
			// Тік виконується завжди (а не лише коли тумблер увімкнений), бо
			// саме тут же й ховається логіка "прибрати фейковий блок назад",
			// якщо тумблер вимкнули просто посеред гри.
			safe("Динамічне світло від предмета в руці", () -> DynamicLight.tick(mc));
			if (HudConfig.INSTANCE.damageMeter) {
				safe("Тік реальної шкоди", () -> DamageMeter.tick(mc));
			}

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
			// Старий ClientLevel більше не існує — скидаємо стан динамічного
			// світла без спроби повернути блок у ньому (і так уже нема куди).
			DynamicLight.reset();
			// Кеш таб-листа (для відсіювання серверних НПС) скидаємо —
			// новий сервер має інший склад гравців.
			PlayerFilter.reset();
			// Приховування HUD — тимчасове, на одну сесію: після перезаходу в
			// гру показуємо його знову, щоб гравець не лишився з порожнім
			// екраном і не зрозумів, що мод зламався.
			hudHidden = false;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			// Тут (на відміну від періодичного автозбереження на тіку) навмисно
			// СИНХРОННА версія: гравець уже виходить зі світу, тож коротка пауза
			// непомітна, а нам критично важливо, щоб файл дописався ДО того, як
			// зникне рівень/сесія (фоновий потік міг би просто не встигнути).
			ChunkColorCache.saveWorldBlocking();
			DynamicLight.reset();
		});
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
