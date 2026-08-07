package com.volyacraft.volyahud.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Прості налаштування HUD. Кожне поле — окремий тумблер/параметр, який можна
 * змінити з меню (ConfigScreen) або залишити як є в json.
 *
 * ПРИМІТКА про сумісність зі старими конфігами: якщо в файлі volyahud.json
 * лишились старі поля (наприклад "showCoords" чи "armorOnRight" з попередніх
 * версій), Gson просто ігнорує невідомі поля і підставляє значення за
 * замовчуванням для нових — оновлення конфігу відбувається "прозоро",
 * без помилок і без ручного видалення файлу.
 */
public class HudConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("volyahud.json");

	public static HudConfig INSTANCE = load();

	public enum ArmorLayout { GRID, LINE }

	public enum ScreenCorner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

	// --- тумблери фіч ---
	public boolean ownArmorHud = true;
	public boolean otherPlayersArmorHud = true;
	public boolean healthTags = true;
	public boolean pearlTrajectory = true;
	public boolean pearlTrail = true;
	public boolean pvpRadar = true;
	public boolean targetBuffsHud = true;
	public boolean inventoryPeek = true;
	public boolean totemAlert = true;

	// --- інфо-панель (fps/координати) ---
	public boolean showFps = false;
	public boolean showCoordX = false;
	public boolean showCoordY = false;
	public boolean showCoordZ = false;
	public boolean showChunkCoords = false;
	/** Де саме показувати панель FPS/координат. */
	public ScreenCorner infoPanelCorner = ScreenCorner.TOP_RIGHT;

	// --- налаштовувані параметри ---
	public double otherPlayersRenderDistance = 40.0;
	public double pvpRadarActivationDistance = 24.0;

	// --- своя броня ---
	/** GRID = сітка 2x2 (за замовчуванням), LINE = один рядок з 4 слотів. */
	public ArmorLayout armorLayout = ArmorLayout.GRID;
	/** У якому нижньому куті малювати панель своєї броні. */
	public ScreenCorner armorCorner = ScreenCorner.BOTTOM_LEFT;
	/** Додаткове ручне зміщення панелі броні по X (px), щоб розминутись з чатом і т.п. */
	public int armorOffsetX = 0;
	/** Додаткове ручне зміщення панелі броні по Y (px). Від'ємне — вище, додатне — нижче. */
	public int armorOffsetY = 0;
	/** true = панель притиснута до самого низу екрана (може перекривати хотбар по краях), false = стоїть над хотбаром. */
	public boolean armorFlushBottom = false;

	// --- сітка меж чанка (допомога при будівництві) ---
	/** Малює тонкі лінії на кожній межі чанка (кожні 16 блоків) навколо гравця. */
	public boolean chunkGrid = false;
	/** Показує підпис "16 x 16" на 4 стінах чанка, у якому зараз стоїть гравець. */
	public boolean chunkGridLabels = true;

	// --- кордон "своєї території"/міста ---
	/** Підсвічує жовтим межу чанків, позначених гравцем як "своя територія". */
	public boolean territoryBorder = false;
	/**
	 * Позначені чанки, окремо для кожного виміру (ключ — id виміру, напр.
	 * "minecraft:overworld"), значення — набір координат чанків, закодованих
	 * через ChunkPos.asLong(x, z). Заповнюється клавішею J або з меню.
	 */
	public Map<String, Set<Long>> territoryChunks = new HashMap<>();

	// --- мінімапа ---
	/** Малювати невеличку мінімапу в кутку екрана. Клавіша розгортання повної карти за замовчуванням: M. */
	public boolean minimap = true;
	/** Розмір панелі мінімапи в пікселях (квадрат size x size). */
	public int minimapSize = 128;
	/** У якому куті екрана малювати мінімапу. */
	public ScreenCorner minimapCorner = ScreenCorner.TOP_RIGHT;
	/** Ручне зміщення мінімапи по X/Y (px), як і для панелі броні. */
	public int minimapOffsetX = 0;
	public int minimapOffsetY = 0;
	/** Скільки пікселів мінімапи припадає на 1 блок світу (масштаб). 1 = дуже віддалено, 4-6 = близько. */
	public int minimapZoom = 2;

	/** Позначки (мітки) на карті, окремо для кожного виміру — ключ так само, як у territoryChunks. */
	public Map<String, List<Waypoint>> waypoints = new HashMap<>();

	public static class Waypoint {
		public String name;
		public int x;
		public int y;
		public int z;
		/** Колір мітки у форматі 0xRRGGBB (без альфи — вона завжди повністю непрозора). */
		public int color = 0xFFFFFF;

		public Waypoint() {
		}

		public Waypoint(String name, int x, int y, int z, int color) {
			this.name = name;
			this.x = x;
			this.y = y;
			this.z = z;
			this.color = color;
		}
	}

	public List<Waypoint> waypointsFor(String dimensionKey) {
		return waypoints.computeIfAbsent(dimensionKey, k -> new ArrayList<>());
	}

	public static HudConfig load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				HudConfig loaded = GSON.fromJson(reader, HudConfig.class);
				if (loaded != null) {
					return loaded;
				}
			} catch (IOException | com.google.gson.JsonSyntaxException e) {
				e.printStackTrace();
			}
		}
		HudConfig fresh = new HudConfig();
		fresh.save();
		return fresh;
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
}
