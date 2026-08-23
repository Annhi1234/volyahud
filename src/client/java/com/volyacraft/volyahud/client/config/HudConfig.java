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
	/**
	 * НОВА ФУНКЦІЯ: динамічне світло від предмета в руці. Якщо тримаєш
	 * факел/ліхтар/лампу (будь-що, що як блок світиться) в основній АБО
	 * в другорядній руці — навколо тебе стає видно так само, ніби ти цей
	 * предмет поставив на землю. Тотем безсмертя поставити не можна, тому
	 * для нього зроблено виняток: він світить як звичайний факел.
	 */
	public boolean heldItemDynamicLight = true;
	/** Показувати відстань (у блоках) поруч з HP-міткою над іншими гравцями. */
	public boolean healthTagDistance = true;
	/** Дописувати в тултип зброї (наведення в інвентарі) розраховану реальну шкоду по HP: база + усі чари, що впливають на шкоду (Sharpness/Smite/BoA, а також кастомні чари модів на кшталт ExcellentEnchants) + окремо крит. */
	public boolean weaponDamageTooltip = true;

	// --- інфо-панель (fps/координати) ---
	public boolean showFps = false;
	public boolean showCoordX = false;
	public boolean showCoordY = false;
	public boolean showCoordZ = false;
	public boolean showChunkCoords = false;
	/** Показувати напрям погляду (N/S/E/W + градуси) в інфо-панелі. */
	public boolean showCompass = false;
	/** Де саме показувати панель FPS/координат. */
	public ScreenCorner infoPanelCorner = ScreenCorner.TOP_RIGHT;
	/** Ручне зміщення інфо-панелі по X/Y (px), як і в панелі броні/мінімапи. */
	public int infoPanelOffsetX = 0;
	public int infoPanelOffsetY = 0;

	// --- РЕАЛЬНА шкода при критичних ударах ---
	/**
	 * Показувати окрему компактну панель із ФАКТИЧНО втраченими HP (а не
	 * "сирим" уроном предмета з таблиць — тобто вже після врахування броні,
	 * зачарувань, абсорбції, опору і т.д.): скільки ми реально зняли цілі
	 * критичним ударом, і скільки нам реально зняв критом противник.
	 */
	public boolean damageMeter = true;
	/** true = панель малюється по центру екрана над прицілом; false = у куті (кут/зсув нижче). */
	public boolean damageMeterAboveCrosshair = false;
	/** У якому куті малювати панель шкоди, коли вона НЕ над прицілом. За замовчуванням той самий кут, що й інфо-панель (FPS/координати), щоб панель шкоди стояла одразу під нею. */
	public ScreenCorner damageMeterCorner = ScreenCorner.TOP_RIGHT;
	/** Ручне зміщення панелі шкоди по X (px). */
	public int damageMeterOffsetX = 0;
	/** Ручне зміщення панелі шкоди по Y (px). За замовчуванням трохи опущена вниз, щоб за замовчуванням стояти під панеллю FPS/координат, а не поверх неї. */
	public int damageMeterOffsetY = 54;
	/** Масштаб тексту панелі шкоди: 1.0 = звичайний розмір шрифту, менше — дрібніший і компактніший текст. */
	public float damageMeterScale = 0.7f;

	// --- HP і екіпіровка інших гравців (мітка над головою) ---
	/** Додаткове вертикальне зміщення рядка HP над головою гравця (px). Від'ємне — вище (далі від ніка), додатне — нижче (ближче до ніка). Зсуває разом із собою і рядки екіпіровки/зброї над ним, тому вони завжди лишаються стосом без зазорів, що "з'їжджають". */
	public int otherHpOffsetY = 0;
	/** Додаткове вертикальне зміщення рядка екіпіровки (і, відповідно, рядка зброї в руках над ним) відносно HP. */
	public int otherEquipmentOffsetY = 0;
	/** Бажаний проміжок (px) між нижнім краєм рядка HP і верхнім краєм рядка екіпіровки (і так само між екіпіровкою й рядком зброї в руках). Фактичний зазор ніколи не опускається нижче 1px навіть при екстремальних offsetY — рендер сам підстраховується від накладання. */
	public int otherHpEquipmentGap = 3;

	// --- бафи цілі (гравець, на якого дивишся) ---
	/** У якому куті малювати панель бафів цілі. */
	public ScreenCorner targetBuffsCorner = ScreenCorner.TOP_LEFT;
	/** Ручне зміщення панелі бафів по X/Y (px). */
	public int targetBuffsOffsetX = 0;
	public int targetBuffsOffsetY = 0;

	// --- сповіщення про тотем ---
	/** Ручне зміщення банера сповіщень по X/Y (px). За замовчуванням — вгорі по центру. */
	public int totemAlertOffsetX = 0;
	public int totemAlertOffsetY = 0;

	// --- індикатор гравців (PvP-радар) ---
	/** Ручне зміщення горизонтальної шкали PvP-радара по X/Y (px). */
	public int pvpRadarOffsetX = 0;
	public int pvpRadarOffsetY = 0;

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
	/** Показувати стрілки-індикатори по краю мінімапи для міток, які зараз не влазять у видиму область. */
	public boolean minimapWaypointArrows = true;

	/** Позначки (мітки) на карті, окремо для кожного виміру — ключ так само, як у territoryChunks. */
	public Map<String, List<Waypoint>> waypoints = new HashMap<>();

	// --- таймер бою ---
	/** Показувати в інфо-панелі, скільки секунд лишилось "бойового тегу" після останнього удару. */
	public boolean combatTimer = true;
	/** Тривалість (сек) бойового тегу після останнього отриманого удару або нашої атаки по гравцю. */
	public int combatTimerSeconds = 15;

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
					loaded.sanitize();
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

	/**
	 * БАГ-ФІКС (важливий, впливає на КОЖНЕ оновлення мода): Gson створює
	 * об'єкт через Unsafe і НЕ викликає ані конструктор, ані ініціалізатори
	 * полів — тому будь-яке поле, якого не було в старому volyahud.json
	 * (наприклад, якщо файл збережений попередньою версією мода, ще до появи
	 * нового поля), після завантаження стає null, а НЕ тим значенням за
	 * замовчуванням, що вказане вище (= ScreenCorner.TOP_RIGHT, = new HashMap<>()
	 * і т.д.). Для enum-полів (armorCorner, minimapCorner, ...) це означало
	 * NPE одразу при першому натисканні кнопки перемикання кута в меню
	 * (cycleXCorner() викликає .ordinal() на null); для territoryChunks/
	 * waypoints — NPE при першому ж рендері кордону території чи мінімапи.
	 * Тут просто "залатуємо" null на дефолт одразу після завантаження.
	 */
	private void sanitize() {
		if (infoPanelCorner == null) infoPanelCorner = ScreenCorner.TOP_RIGHT;
		if (targetBuffsCorner == null) targetBuffsCorner = ScreenCorner.TOP_LEFT;
		if (armorLayout == null) armorLayout = ArmorLayout.GRID;
		if (armorCorner == null) armorCorner = ScreenCorner.BOTTOM_LEFT;
		if (minimapCorner == null) minimapCorner = ScreenCorner.TOP_RIGHT;
		if (damageMeterCorner == null) damageMeterCorner = ScreenCorner.TOP_RIGHT;
		// damageMeterScale — float-поле: у старих конфігах, збережених до появи
		// цієї фічі, Gson підставить "сирий" дефолт JVM (0.0f), а не 0.7f з
		// ініціалізатора поля вище (та сама причина, що описана в коментарі
		// над цим методом) — при масштабі 0 текст був би просто невидимий.
		if (damageMeterScale <= 0f) damageMeterScale = 0.7f;
		// Той самий клас бага для нового поля gap: у старому конфігу, збереженому
		// до появи цієї опції, Gson підставить "сирий" 0 замість дефолтних 3px.
		// 0 сам по собі ще не призвів би до накладання (рендер підстраховується
		// мінімумом 1px нижче), але це зламало б задуманий вигляд за замовчуванням.
		if (otherHpEquipmentGap <= 0) otherHpEquipmentGap = 3;
		// Той самий клас бага для нового поля: у старому конфігу, збереженому
		// до появи таймера бою, Gson підставить "сирий" 0 замість дефолтних 15с.
		if (combatTimerSeconds <= 0) combatTimerSeconds = 15;
		if (territoryChunks == null) territoryChunks = new HashMap<>();
		if (waypoints == null) waypoints = new HashMap<>();
		// На випадок пошкодженого/частково записаного файлу — прибираємо
		// будь-які null-записи всередині самих мап, щоб не впасти пізніше
		// на territoryChunks.get(dim).contains(...) чи waypointsFor(dim).
		territoryChunks.values().removeIf(java.util.Objects::isNull);
		waypoints.values().removeIf(java.util.Objects::isNull);
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
