package com.volyacraft.volyahud.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
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

	/**
	 * Версія схеми конфігу. Потрібна, щоб відрізнити "поле відсутнє в старому
	 * файлі" від "гравець явно вимкнув нову функцію": Gson для відсутнього
	 * boolean підставляє false, тому без маркера нові фічі з дефолтом true
	 * виглядали б вимкненими після оновлення.
	 */
	public int configVersion = 4;

	public enum ArmorLayout { GRID, LINE }

	public enum ScreenCorner { BOTTOM_LEFT, BOTTOM_RIGHT, TOP_LEFT, TOP_RIGHT }

	/** Де малювати блок HP/броні/зброї іншого гравця відносно його голови. */
	public enum OverlayAnchor { ABOVE, LEFT, RIGHT }

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
	/**
	 * Максимальний рівень світла (0-15), який дає предмет у руці. 0 = вимкнено
	 * фічу повністю, 15 = максимум (як справжній факел). Менші значення
	 * роблять світло "м'якшим" — воно світить ближче й не "пробиває" так далеко
	 * крізь стіни, як повноцінний факел.
	 */
	public int heldItemLightLevel = 15;
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
	/** Показувати затримку з'єднання (ping) в інфо-панелі. */
	public boolean showPing = false;
	/** Малювати темний фон/рамку під інфо-панеллю. Вимкнено — лише текст. */
	public boolean infoPanelBackground = true;
	/** Де саме показувати панель FPS/координат. */
	public ScreenCorner infoPanelCorner = ScreenCorner.TOP_RIGHT;
	/** Ручне зміщення інфо-панелі по X/Y (px), як і в панелі броні/мінімапи. */
	public int infoPanelOffsetX = 0;
	public int infoPanelOffsetY = 0;

	// --- панель натиснутих клавіш / CPS ---
	/** Показувати компактну панель WASD + ЛКМ/ПКМ + пробіл. */
	public boolean keystrokesHud = false;
	/** Кут екрана для панелі клавіш. */
	public ScreenCorner keystrokesCorner = ScreenCorner.BOTTOM_LEFT;
	/** Ручне зміщення панелі клавіш по X/Y (px). */
	public int keystrokesOffsetX = 0;
	public int keystrokesOffsetY = 0;
	/** Показувати кількість кліків за секунду (CPS) під клавішами. */
	public boolean keystrokesShowCps = true;

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
	/**
	 * Максимальна дистанція (у блоках), на якій показувати саме броню інших
	 * гравців. HP-мітка може мати більший радіус (otherPlayersRenderDistance),
	 * а броня — окремий, компактніший ліміт. 15 блоків — робочий PvP-радіус.
	 */
	public double otherArmorRenderDistance = 15.0;
	/** Базовий масштаб блоку HP/броні/зброї інших гравців. 0.85 = компактніше, ніж стандартні слоти 20x20. */
	public float otherArmorScale = 0.85f;
	/** true = блок плавно зменшується з відстанню; false = завжди базовий масштаб. */
	public boolean otherArmorDistanceScale = true;
	/** Позиція блоку відносно голови гравця: зверху, зліва або справа. */
	public OverlayAnchor otherOverlayAnchor = OverlayAnchor.ABOVE;
	/** Ручне зміщення всього блоку HP/броні/зброї по X (px). */
	public int otherOverlayOffsetX = 0;
	/** Ручне зміщення всього блоку HP/броні/зброї по Y (px). */
	public int otherOverlayOffsetY = 0;

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
	 * Позначені чанки, окремо для кожного світу/сервера й виміру (ключ —
	 * worldKey + "|" + id виміру, напр. "singleplayer_Світ|minecraft:overworld"),
	 * значення — набір координат чанків, закодованих через ChunkPos.asLong(x, z).
	 * Заповнюється клавішею J або з меню.
	 */
	public Map<String, Set<Long>> territoryChunks = new HashMap<>();

	// --- скриня / бочка ---
	/**
	 * Швидкий закид усіх однотипних предметів у відкриту скриню, подвійну
	 * скриню або бочку: Shift+клік по предмету у своєму інвентарі переносить
	 * не лише його, а й усі такі самі предмети з інвентаря гравця (разом із
	 * хотбаром).
	 */
	public boolean bulkChestTransfer = true;
	/**
	 * Швидке забирання зі скрині/бочки: Shift+ПКМ (або Shift+ЛКМ) по предмету
	 * в контейнері переносить у свій інвентар усі такі самі предмети з цього
	 * контейнера. Вмикається/вимикається комбінацією Alt+F7.
	 */
	public boolean bulkChestWithdraw = true;

	// --- мінімапа ---
	/** Малювати невеличку мінімапу в кутку екрана. Клавіша розгортання повної карти за замовчуванням: O. */
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
	/** Автоматично ставити мітку в місці смерті (стара мітка смерті в цьому світі замінюється). */
	public boolean deathWaypoint = true;
	/** Показувати сітку чанків (кожні 16 блоків) на повноекранній карті. */
	public boolean mapChunkGrid = false;
	/** Показувати позначені чанки території жовтим overlay на повноекранній карті. */
	public boolean mapTerritoryOverlay = true;

	/** Позначки (мітки) на карті, окремо для кожного світу/сервера й виміру — ключ так само, як у territoryChunks. */
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
		/**
		 * Ключ світу/сервера, у якому створено мітку. null = стара мітка до
		 * появи прив'язки до світу; такі мітки видимі всюди для сумісності.
		 */
		public String world = null;
		/** true = автоматична мітка смерті (її замінює наступна смерть у цьому світі). */
		public boolean deathMarker = false;

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
		List<Waypoint> list = waypoints.get(dimensionKey);
		if (list == null) {
			list = new ArrayList<>();
			waypoints.put(dimensionKey, list);
		}
		return list;
	}

	/**
	 * Чи показувати мітку в поточному світі. Старі мітки без поля world
	 * (створені до версії 1.3.0) видимі в усіх світах, щоб оновлення не
	 * "загубило" вже поставлені позначки.
	 */
	public boolean isWaypointVisible(Waypoint wp, String worldKey) {
		return wp != null && (wp.world == null || wp.world.isEmpty() || wp.world.equals(worldKey));
	}

	/**
	 * Кількість міток у поточному світі/вимірі. Використовується для
	 * нумерації та кольору нових міток, щоб мітки з інших серверів не
	 * впливали на вигляд поточної карти.
	 */
	public int countVisibleWaypoints(String dimensionKey, String worldKey) {
		List<Waypoint> list = waypoints.get(dimensionKey);
		if (list == null) return 0;
		int count = 0;
		for (Waypoint wp : list) {
			if (isWaypointVisible(wp, worldKey)) count++;
		}
		return count;
	}

	/**
	 * Наступна вільна назва для нової мітки в поточному світі. Простий
	 * {@code size + 1} давав дублікати після видалення середньої мітки:
	 * «Мітка 1», «Мітка 3» → нова знову «Мітка 3». Тут шукаємо найменший
	 * номер, якого ще немає серед видимих міток.
	 */
	public String nextWaypointName(String dimensionKey, String worldKey) {
		Set<String> used = new HashSet<>();
		List<Waypoint> list = waypoints.get(dimensionKey);
		if (list != null) {
			for (Waypoint wp : list) {
				if (isWaypointVisible(wp, worldKey) && wp.name != null) used.add(wp.name);
			}
		}
		int n = 1;
		while (used.contains("Мітка " + n)) n++;
		return "Мітка " + n;
	}

	/** Видаляє мітку з повного списку виміру (а не з тимчасової відфільтрованої копії). */
	public void removeWaypoint(String dimensionKey, Waypoint wp) {
		List<Waypoint> list = waypoints.get(dimensionKey);
		if (list != null) list.remove(wp);
	}

	public static HudConfig load() {
		if (Files.exists(FILE)) {
			try (Reader reader = Files.newBufferedReader(FILE, StandardCharsets.UTF_8)) {
				HudConfig loaded = GSON.fromJson(reader, HudConfig.class);
				if (loaded != null) {
					loaded.sanitize();
					return loaded;
				}
				// Порожній/неповний json (наприклад, обірваний запис) — не
				// затираємо його мовчки: лишаємо копію для ручного відновлення.
				backupBrokenConfig();
			} catch (IOException | com.google.gson.JsonSyntaxException e) {
				System.err.println("VolyaHUD: cannot read volyahud.json, using defaults: " + e);
				backupBrokenConfig();
			}
		}
		HudConfig fresh = new HudConfig();
		fresh.save();
		return fresh;
	}

	/** Зберігає пошкоджений конфіг як volyahud.json.bak, перш ніж записати дефолти. */
	private static void backupBrokenConfig() {
		try {
			Files.copy(FILE, FILE.resolveSibling(FILE.getFileName() + ".bak"),
					StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			System.err.println("VolyaHUD: cannot back up broken config: " + e);
		}
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
		// Міграція старих конфігів: нові фічі з дефолтом true інакше
		// отримали б від Gson "сирий" false і виглядали б вимкненими.
		if (configVersion < 2) {
			deathWaypoint = true;
			mapTerritoryOverlay = true;
			keystrokesShowCps = true;
			// Нові поля з дефолтом true, яких не було в старому JSON.
			// Без цього Gson залишив би їх false і фіча виглядала б вимкненою.
			minimapWaypointArrows = true;
			configVersion = 2;
		}
		if (configVersion < 3) {
			// Нові поля 1.4.0: у старих конфігах Gson лишив би 0/null.
			// Ставимо акуратні дефолти, не чіпаючи вже налаштовані старі опції.
			otherArmorRenderDistance = 15.0;
			otherArmorScale = 0.85f;
			otherArmorDistanceScale = true;
			otherOverlayAnchor = OverlayAnchor.ABOVE;
			bulkChestWithdraw = true;
			configVersion = 3;
		}
		if (configVersion < 4) {
			// Нове поле 1.5.0: фон інфо-панелі. У старих конфігах Gson
			// залишив би false, хоча історично панель завжди мала фон.
			infoPanelBackground = true;
			configVersion = 4;
		}
		if (infoPanelCorner == null) infoPanelCorner = ScreenCorner.TOP_RIGHT;
		if (targetBuffsCorner == null) targetBuffsCorner = ScreenCorner.TOP_LEFT;
		if (armorLayout == null) armorLayout = ArmorLayout.GRID;
		if (armorCorner == null) armorCorner = ScreenCorner.BOTTOM_LEFT;
		if (minimapCorner == null) minimapCorner = ScreenCorner.TOP_RIGHT;
		if (damageMeterCorner == null) damageMeterCorner = ScreenCorner.TOP_RIGHT;
		if (keystrokesCorner == null) keystrokesCorner = ScreenCorner.BOTTOM_LEFT;
		if (otherOverlayAnchor == null) otherOverlayAnchor = OverlayAnchor.ABOVE;
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
		// Той самий клас бага для нового поля рівня світла: у старому
		// конфігу, збереженому до появи цієї опції, Gson підставить "сирий" 0
		// замість дефолтних 15. 0 означав би "максимально тьмяне світло",
		// хоча користувач ніколи таке не вибирав — повертаємо дефолт.
		if (heldItemLightLevel <= 0) heldItemLightLevel = 15;
		if (!Float.isFinite(damageMeterScale)) damageMeterScale = 0.7f;
		// Захист від пошкодженого/старого JSON: Gson міг залишити 0 або
		// безглуздо велике число. Це не дає панелям вийти за межі екрана
		// або зникнути зовсім.
		damageMeterScale = Math.max(0.4f, Math.min(1.5f, damageMeterScale));
		minimapSize = Math.max(64, Math.min(320, minimapSize));
		minimapZoom = Math.max(1, Math.min(6, minimapZoom));
		heldItemLightLevel = Math.max(1, Math.min(15, heldItemLightLevel));
		if (!Double.isFinite(otherPlayersRenderDistance) || otherPlayersRenderDistance <= 0) {
			otherPlayersRenderDistance = 40.0;
		}
		if (!Double.isFinite(otherArmorRenderDistance) || otherArmorRenderDistance <= 0) {
			otherArmorRenderDistance = 15.0;
		}
		otherArmorRenderDistance = Math.max(5.0, Math.min(40.0, otherArmorRenderDistance));
		if (!Float.isFinite(otherArmorScale) || otherArmorScale <= 0f) {
			otherArmorScale = 0.85f;
		}
		otherArmorScale = Math.max(0.5f, Math.min(1.2f, otherArmorScale));
		if (!Double.isFinite(pvpRadarActivationDistance) || pvpRadarActivationDistance <= 0) {
			pvpRadarActivationDistance = 24.0;
		}

		// На випадок пошкодженого/частково записаного файлу — прибираємо
		// будь-які null-записи всередині самих мап (і null-елементи списків),
		// щоб не впасти пізніше на contains(...) чи при рендері мітки.
		territoryChunks.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		territoryChunks.values().forEach(set -> set.removeIf(java.util.Objects::isNull));
		waypoints.entrySet().removeIf(e -> e.getKey() == null || e.getValue() == null);
		waypoints.values().forEach(list -> list.removeIf(w -> w == null || w.name == null));
	}

	public void save() {
		Path tmp = FILE.resolveSibling(FILE.getFileName() + ".tmp");
		try {
			Files.createDirectories(FILE.getParent());
			// Атомарний запис: обрив живлення/краш посеред save() не повинен
			// лишити порожній або обрізаний volyahud.json.
			try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			try {
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING,
						StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING);
			}
		} catch (IOException e) {
			System.err.println("VolyaHUD: cannot save config: " + e);
		} finally {
			try {
				Files.deleteIfExists(tmp);
			} catch (IOException ignored) {
				// temp file is harmless
			}
		}
	}

	/**
	 * Повертає всі налаштування до стандартних (кнопка "Скинути" в меню).
	 *
	 * <p>Робимо це НЕ заміною об'єкта, а копіюванням полів зі свіжого
	 * екземпляра: {@link #INSTANCE} — статичне поле, на яке посилаються всі
	 * HUD-класи, тому замінювати сам об'єкт не можна (усі посилання
	 * "залишилися б" б на старому). Копіюємо поле за полем — так свіжий
	 * дефолт зберігається в одному місці (початкові значення полів) і
	 * скидання завжди залишається синхронним із ними.
	 */
	public void resetToDefaults() {
		HudConfig d = new HudConfig();

		configVersion = d.configVersion;
		ownArmorHud = d.ownArmorHud;
		otherPlayersArmorHud = d.otherPlayersArmorHud;
		healthTags = d.healthTags;
		pearlTrajectory = d.pearlTrajectory;
		pearlTrail = d.pearlTrail;
		pvpRadar = d.pvpRadar;
		targetBuffsHud = d.targetBuffsHud;
		inventoryPeek = d.inventoryPeek;
		totemAlert = d.totemAlert;
		heldItemDynamicLight = d.heldItemDynamicLight;
		heldItemLightLevel = d.heldItemLightLevel;
		healthTagDistance = d.healthTagDistance;
		weaponDamageTooltip = d.weaponDamageTooltip;

		showFps = d.showFps;
		showCoordX = d.showCoordX;
		showCoordY = d.showCoordY;
		showCoordZ = d.showCoordZ;
		showChunkCoords = d.showChunkCoords;
		showCompass = d.showCompass;
		showPing = d.showPing;
		infoPanelBackground = d.infoPanelBackground;
		infoPanelCorner = d.infoPanelCorner;
		infoPanelOffsetX = d.infoPanelOffsetX;
		infoPanelOffsetY = d.infoPanelOffsetY;

		keystrokesHud = d.keystrokesHud;
		keystrokesCorner = d.keystrokesCorner;
		keystrokesOffsetX = d.keystrokesOffsetX;
		keystrokesOffsetY = d.keystrokesOffsetY;
		keystrokesShowCps = d.keystrokesShowCps;

		damageMeter = d.damageMeter;
		damageMeterAboveCrosshair = d.damageMeterAboveCrosshair;
		damageMeterCorner = d.damageMeterCorner;
		damageMeterOffsetX = d.damageMeterOffsetX;
		damageMeterOffsetY = d.damageMeterOffsetY;
		damageMeterScale = d.damageMeterScale;

		otherHpOffsetY = d.otherHpOffsetY;
		otherEquipmentOffsetY = d.otherEquipmentOffsetY;
		otherHpEquipmentGap = d.otherHpEquipmentGap;
		otherArmorRenderDistance = d.otherArmorRenderDistance;
		otherArmorScale = d.otherArmorScale;
		otherArmorDistanceScale = d.otherArmorDistanceScale;
		otherOverlayAnchor = d.otherOverlayAnchor;
		otherOverlayOffsetX = d.otherOverlayOffsetX;
		otherOverlayOffsetY = d.otherOverlayOffsetY;

		targetBuffsCorner = d.targetBuffsCorner;
		targetBuffsOffsetX = d.targetBuffsOffsetX;
		targetBuffsOffsetY = d.targetBuffsOffsetY;

		totemAlertOffsetX = d.totemAlertOffsetX;
		totemAlertOffsetY = d.totemAlertOffsetY;

		pvpRadarOffsetX = d.pvpRadarOffsetX;
		pvpRadarOffsetY = d.pvpRadarOffsetY;

		otherPlayersRenderDistance = d.otherPlayersRenderDistance;
		pvpRadarActivationDistance = d.pvpRadarActivationDistance;

		armorLayout = d.armorLayout;
		armorCorner = d.armorCorner;
		armorOffsetX = d.armorOffsetX;
		armorOffsetY = d.armorOffsetY;
		armorFlushBottom = d.armorFlushBottom;

		chunkGrid = d.chunkGrid;
		chunkGridLabels = d.chunkGridLabels;

		territoryBorder = d.territoryBorder;

		bulkChestTransfer = d.bulkChestTransfer;
		bulkChestWithdraw = d.bulkChestWithdraw;

		minimap = d.minimap;
		minimapSize = d.minimapSize;
		minimapCorner = d.minimapCorner;
		minimapOffsetX = d.minimapOffsetX;
		minimapOffsetY = d.minimapOffsetY;
		minimapZoom = d.minimapZoom;
		minimapWaypointArrows = d.minimapWaypointArrows;
		deathWaypoint = d.deathWaypoint;
		mapChunkGrid = d.mapChunkGrid;
		mapTerritoryOverlay = d.mapTerritoryOverlay;

		combatTimer = d.combatTimer;
		combatTimerSeconds = d.combatTimerSeconds;

		// Територію та мітки НЕ скидаємо — це дані гри, а не налаштування
		// (їх можна скинути окремою кнопкою у вкладці "Будівництво"/"Мінімапа").
		save();
	}
}
