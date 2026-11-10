package com.volyacraft.volyahud.client.gui;

import com.volyacraft.volyahud.client.VolyaHudClient;
import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.hud.HudStyle;
import com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache;
import com.volyacraft.volyahud.client.hud.minimap.MinimapHud;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Supplier;

/**
 * Меню налаштувань HUD у стилі Sodium: вкладки-категорії зверху, під ними
 * чіткий двоколонковий список опцій із підказками (наведи мишкою — побачиш
 * опис).
 *
 * <p><b>ГОЛОВНИЙ ФІКС ІНТЕРФЕЙСУ:</b> раніше список опцій просто малювався
 * вниз від верхнього краю екрана, і все, що не вміщувалося, опинялося
 * <i>за межами екрана</i> — при високому GUI Scale або на маленькому вікні
 * половина опцій ставала недоступною (кнопки не натискались, підказки не
 * показувались). Тепер список <b>прокручується</b>: колесо миші та кнопки
 * "вгору"/"вниз" біля вкладок. Рядки, яких не видно, навіть не створюються
 * — тож нічого не "з'їжджає" і нічого не перекривається.
 */
public class ConfigScreen extends Screen {

	private enum Tab {
		ARMOR("Броня"),
		PLAYERS("Гравці"),
		THROWS("Кидки"),
		BUILD("Будова"),
		MINIMAP("Мапа"),
		INFO("Інфо"),
		DAMAGE("Шкода"),
		MISC("Інше");

		final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	private static final int ROW_HEIGHT = 24;
	private static final int COL_WIDTH = 200;
	private static final int MIN_COL_WIDTH = 120;
	private static final int COL_GAP = 16;
	private static final int TAB_HEIGHT = 22;
	private static final int TAB_GAP = 2;
	/** Верх списку опцій — залежить від того, скільки рядів вкладок влізло. */
	private int contentTop = 54;
	/** Скільки рядків опцій одночасно показувати на екрані (рахується під висоту вікна). */
	private int visibleRows = 7;
	/** Ширина колонки й позиції, які підлаштовуються під ширину вікна. */
	private int colWidth = COL_WIDTH;
	private int col1X = 8;
	private int col2X = 224;
	/** Позиція/ширина вкладок і акцентної лінії під вибраною вкладкою. */
	private int tabWidth = 80;
	private int selectedTabX = 0;
	private int selectedTabUnderlineY = 48;

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;
	private Tab currentTab = Tab.ARMOR;

	/** Зсув прокрутки (в рядках). 0 = верх списку. */
	private int scroll = 0;
	/** Скільки всього рядків у поточній вкладці. */
	private int totalRows = 0;

	public ConfigScreen(Screen parent) {
		super(Component.literal("VolyaHUD " + VolyaHudClient.version() + " — налаштування"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// Screen#resize() calls init() without clearing the old widgets, while
		// our own tab/scroll handlers already clear explicitly. Clearing here
		// makes both paths safe: resizing the window no longer leaves a second
		// copy of every button/edit box stacked on top of the first one.
		this.clearWidgets();
		int tabCount = Tab.values().length;
		// На вузькому вікні 8 вкладок в один ряд стають по 20-30px і текст
		// налазить одна на одну. Тому при ширині < 620px розкладаємо їх у
		// два ряди по 4 — підписи лишаються читабельними, а не обрізаються.
		int perRow = this.width < 620 ? 4 : tabCount;
		int tabRows = (tabCount + perRow - 1) / perRow;
		int maxTabsWidth = Math.max(160, this.width - 52);
		this.tabWidth = Math.min(104, (maxTabsWidth - TAB_GAP * (perRow - 1)) / perRow);
		int rowTabsWidth = tabWidth * perRow + TAB_GAP * (perRow - 1);
		int rowStartX = this.width / 2 - rowTabsWidth / 2;
		this.contentTop = 26 + tabRows * TAB_HEIGHT + (tabRows - 1) * TAB_GAP + 6;

		// Висота підгоняється під вікно: на маленькому екрані 7 фіксованих
		// рядків налазили на кнопку "Готово" внизу.
		this.visibleRows = Mth.clamp((this.height - contentTop - 36) / ROW_HEIGHT, 1, 32);

		for (int index = 0; index < tabCount; index++) {
			Tab tab = Tab.values()[index];
			int row = index / perRow;
			int col = index % perRow;
			int tabX = rowStartX + col * (tabWidth + TAB_GAP);
			int tabY = 26 + row * (TAB_HEIGHT + TAB_GAP);
			boolean selected = tab == currentTab;
			Button tabButton = Button.builder(
					Component.literal((selected ? "▸ " : "") + tab.title),
					b -> switchTab(tab)
			).bounds(tabX, tabY, tabWidth, TAB_HEIGHT).build();
			tabButton.active = !selected;
			this.addRenderableWidget(tabButton);
			if (selected) {
				this.selectedTabX = tabX;
				this.selectedTabUnderlineY = tabY + TAB_HEIGHT;
			}
		}

		// Лічильники рядків для кожної колонки — щоб знати, скільки всього
		// рядків (для прокрутки) і які рядки зараз видимі.
		this.colWidth = Mth.clamp((this.width - 24 - COL_GAP) / 2, MIN_COL_WIDTH, COL_WIDTH);
		this.col1X = Math.max(8, this.width / 2 - (colWidth * 2 + COL_GAP) / 2);
		this.col2X = col1X + colWidth + COL_GAP;

		// Спершу рахуємо рядки "всуху" (без створення віджетів), потім
		// обмежуємо scroll і лише після цього будуємо видимі рядки. Раніше
		// clampScroll() викликався ПІСЛЯ buildTab(): після збільшення вікна
		// старий scroll міг перевищити новий максимум, віджди створювались
		// для неправильного діапазону й лишались на екрані зі старими Y.
		measureRows();
		clampScroll();

		RowBuilder c1 = new RowBuilder(col1X);
		RowBuilder c2 = new RowBuilder(col2X);
		buildTab(c1, c2);

		// Кнопки прокрутки — праворуч від вкладок.
		int scrollBtnX = this.width - 24;
		Button up = Button.builder(Component.literal("▲"), b -> scrollBy(-1))
				.bounds(scrollBtnX, 26, 16, 10)
				.tooltip(Tooltip.create(Component.literal("Прокрутити список опцій вгору")))
				.build();
		Button down = Button.builder(Component.literal("▼"), b -> scrollBy(1))
				.bounds(scrollBtnX, 38, 16, 10)
				.tooltip(Tooltip.create(Component.literal("Прокрутити список опцій вниз")))
				.build();
		int maxScroll = Math.max(0, totalRows - visibleRows);
		up.active = scroll > 0;
		down.active = scroll < maxScroll;
		this.addRenderableWidget(up);
		this.addRenderableWidget(down);

		// Кнопка "Готово" — завжди внизу екрана, незалежно від довжини списку.
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			cfg.save();
			Minecraft.getInstance().setScreen(parent);
		}).bounds(this.width / 2 - 75, this.height - 26, 150, 20).build());
	}

	/** Рахує кількість рядків поточної вкладки, не створюючи віджетів. */
	private void measureRows() {
		int savedVisibleRows = visibleRows;
		visibleRows = 0;
		try {
			RowBuilder c1 = new RowBuilder(col1X);
			RowBuilder c2 = new RowBuilder(col2X);
			buildTab(c1, c2);
			totalRows = Math.max(c1.count, c2.count);
		} finally {
			visibleRows = savedVisibleRows;
		}
	}

	/**
	 * Будує вміст вкладки. Кожна колонка — окремий {@link RowBuilder}, який
	 * сам вирішує, чи створювати віджди для цього рядка (тобто чи він
	 * потрапляє у видиму частину списку).
	 */
	private void buildTab(RowBuilder c1, RowBuilder c2) {
		switch (currentTab) {
			case ARMOR -> {
				c1.toggle("Своя броня", "Показувати панель зі своєю бронею.",
						() -> cfg.ownArmorHud, v -> cfg.ownArmorHud = v);
				c1.cycle("Розкладка", "Сітка 2x2 або один рядок з 4 слотів.",
						() -> cfg.armorLayout.name(), () -> {
							cfg.armorLayout = cfg.armorLayout == HudConfig.ArmorLayout.GRID
									? HudConfig.ArmorLayout.LINE : HudConfig.ArmorLayout.GRID;
						}, this::armorLayoutLabel);
				c1.cycle("Кут екрана", "У якому куті малювати свою броню.",
						() -> cfg.armorCorner.name(), this::cycleArmorCorner, this::cornerLabel);
				c1.toggle("Впритул до низу", "Притиснути панель до самого краю екрана (може лягати на хотбар по краях).",
						() -> cfg.armorFlushBottom, v -> cfg.armorFlushBottom = v);
				c1.stepper("Зсув по X", "Ручне зміщення панелі вбік — щоб розминутись із чатом чи іншим HUD.",
						() -> cfg.armorOffsetX, v -> cfg.armorOffsetX = v, 4, -200, 200);
				c1.stepper("Зсув по Y", "Ручне зміщення панелі вгору/вниз. Мінус — вище, плюс — нижче.",
						() -> cfg.armorOffsetY, v -> cfg.armorOffsetY = v, 4, -200, 200);

				c2.toggle("Броня інших гравців", "Показувати іконки броні над головою гравців, на яких дивишся. Ближче — більше, далі — компактніше.",
						() -> cfg.otherPlayersArmorHud, v -> cfg.otherPlayersArmorHud = v);
				c2.slider("Дистанція броні", "Максимальна відстань (у блоках), на якій показується броня інших гравців. 10–15 — робочий PvP-радіус.",
						() -> cfg.otherArmorRenderDistance, v -> cfg.otherArmorRenderDistance = v, 1, 5, 40);
				c2.slider("Масштаб блоку", "Базовий розмір блоку HP/броні/зброї. 0.85 — компактно, 1.0 — як стандартні слоти.",
						() -> (double) cfg.otherArmorScale, v -> cfg.otherArmorScale = (float) v, 0.05, 0.5, 1.2);
				c2.toggle("Зменшувати з відстанню", "Плавно зменшувати блок, коли гравець далі. Близько — базовий розмір, на 15 блоках — приблизно на третину менше.",
						() -> cfg.otherArmorDistanceScale, v -> cfg.otherArmorDistanceScale = v);
				c2.cycle("Позиція біля гравця", "Малювати блок зверху, зліва або справа від голови гравця.",
						() -> cfg.otherOverlayAnchor.name(), this::cycleOtherOverlayAnchor, this::overlayAnchorLabel);
				c2.stepper("Зсув блоку по X", "Ручне зміщення всього блоку HP/броні/зброї вбік.",
						() -> cfg.otherOverlayOffsetX, v -> cfg.otherOverlayOffsetX = v, 4, -200, 200);
				c2.stepper("Зсув блоку по Y", "Ручне зміщення всього блоку вгору/вниз.",
						() -> cfg.otherOverlayOffsetY, v -> cfg.otherOverlayOffsetY = v, 4, -200, 200);
				c2.toggle("Показ екіпіровки цілі", "Показувати, що тримає в руках гравець, на якого дивишся.",
						() -> cfg.inventoryPeek, v -> cfg.inventoryPeek = v);
				c2.stepper("Зсув екіпіровки по Y", "Зміщення рядка екіпіровки (і зброї) над головою противника відносно HP. Мінус — далі від HP, плюс — ближче. Не може накластися на HP: зазор завжди підстраховує мінімум 1px.",
						() -> cfg.otherEquipmentOffsetY, v -> cfg.otherEquipmentOffsetY = v, 2, -60, 60);
				c2.stepper("Зазор HP ↔ екіпіровка", "Проміжок (px) між рядком HP і рядком екіпіровки (і так само між екіпіровкою й зброєю).",
						() -> cfg.otherHpEquipmentGap, v -> cfg.otherHpEquipmentGap = v, 1, 1, 20);
			}
			case PLAYERS -> {
				c1.toggle("HP-мітки над гравцями", "Показувати здоров'я над головою інших гравців.",
						() -> cfg.healthTags, v -> cfg.healthTags = v);
				c1.slider("Дистанція показу", "Максимальна відстань (у блоках), на якій показуються мітки гравців.",
						() -> cfg.otherPlayersRenderDistance, v -> cfg.otherPlayersRenderDistance = v, 8, 4, 128);
				c1.toggle("Дистанція в HP-мітці", "Показувати відстань у блоках поруч з HP гравця.",
						() -> cfg.healthTagDistance, v -> cfg.healthTagDistance = v);
				c1.stepper("Зсув HP по Y", "Зміщення рядка HP над головою противника відносно ніка. Рухає разом із собою й екіпіровку/зброю над ним, тому стос не розсипається.",
						() -> cfg.otherHpOffsetY, v -> cfg.otherHpOffsetY = v, 2, -60, 60);

				c2.toggle("Бафи цілі", "Показувати активні ефекти гравця, на якого дивишся.",
						() -> cfg.targetBuffsHud, v -> cfg.targetBuffsHud = v);
				c2.cycle("Кут екрана", "У якому куті малювати панель бафів цілі.",
						() -> cfg.targetBuffsCorner.name(), this::cycleTargetBuffsCorner, this::cornerLabel);
				c2.stepper("Зсув по X", "Ручне зміщення панелі бафів вбік.",
						() -> cfg.targetBuffsOffsetX, v -> cfg.targetBuffsOffsetX = v, 4, -300, 300);
				c2.stepper("Зсув по Y", "Ручне зміщення панелі бафів вгору/вниз.",
						() -> cfg.targetBuffsOffsetY, v -> cfg.targetBuffsOffsetY = v, 4, -300, 300);
				c2.toggle("Сповіщення про тотем", "Текстове сповіщення, коли поруч хтось використав тотем безсмертя.",
						() -> cfg.totemAlert, v -> cfg.totemAlert = v);
				c2.stepper("Зсув сповіщень по X", "Зміщення банера сповіщень про тотем вбік від центру.",
						() -> cfg.totemAlertOffsetX, v -> cfg.totemAlertOffsetX = v, 4, -300, 300);
				c2.stepper("Зсув сповіщень по Y", "Зміщення банера сповіщень про тотем вгору/вниз.",
						() -> cfg.totemAlertOffsetY, v -> cfg.totemAlertOffsetY = v, 4, -300, 300);
			}
			case THROWS -> {
				c1.toggle("Прев'ю траєкторії", "Лінія напрямку, поки тримаєш перлу/зілля/пляшку досвіду в руці.",
						() -> cfg.pearlTrajectory, v -> cfg.pearlTrajectory = v);
				c1.toggle("Слід кинутого предмета", "Лінія за вже кинутою перлою/зіллям/пляшкою в польоті.",
						() -> cfg.pearlTrail, v -> cfg.pearlTrail = v);

				c2.toggle("Індикатор гравців", "Горизонтальна шкала, що показує, з якого боку поруч знаходяться гравці.",
						() -> cfg.pvpRadar, v -> cfg.pvpRadar = v);
				c2.slider("Радіус радара", "Відстань (у блоках), на якій гравці з'являються на індикаторі.",
						() -> cfg.pvpRadarActivationDistance, v -> cfg.pvpRadarActivationDistance = v, 4, 4, 64);
				c2.stepper("Зсув по X", "Зміщення шкали радара вбік від центру.",
						() -> cfg.pvpRadarOffsetX, v -> cfg.pvpRadarOffsetX = v, 4, -300, 300);
				c2.stepper("Зсув по Y", "Зміщення шкали радара вгору/вниз.",
						() -> cfg.pvpRadarOffsetY, v -> cfg.pvpRadarOffsetY = v, 4, -300, 300);
			}
			case BUILD -> {
				c1.toggle("Сітка чанка", "Тонкі лінії на кожній межі чанка (кожні 16 блоків) навколо тебе — зручно для рівної забудови. Клавіша: [.",
						() -> cfg.chunkGrid, v -> cfg.chunkGrid = v);
				c1.toggle("Підпис \"16 x 16\"", "Показує підпис на стінах чанка, у якому ти зараз стоїш.",
						() -> cfg.chunkGridLabels, v -> cfg.chunkGridLabels = v);
				c1.button("Скинути налаштування", "Повертає ВСІ опції HUD до стандартних. Мітки та позначені чанки НЕ змінюються. Це не скасувати.",
						this::resetOptions);

				c2.toggle("Кордон території", "Підсвічує жовтим межу твоїх позначених чанків (\"свого міста\"). Клавіша: ].",
						() -> cfg.territoryBorder, v -> cfg.territoryBorder = v);
				c2.claimButton();
				c2.button("Очистити територію", "Прибирає ВСІ позначені чанки в поточному вимірі. Це не скасувати.",
						this::clearTerritory);
			}
			case MINIMAP -> {
				c1.toggle("Мінімапа", "Невеличка мапа рельєфу в кутку екрана. Ніяких мобів чи гравців вона не показує — лише блоки.",
						() -> cfg.minimap, v -> cfg.minimap = v);
				c1.cycle("Кут екрана", "У якому куті малювати мінімапу.",
						() -> cfg.minimapCorner.name(), this::cycleMinimapCorner, this::cornerLabel);
				c1.stepper("Розмір (px)", "Розмір панелі мінімапи в пікселях.",
						() -> cfg.minimapSize, v -> cfg.minimapSize = v, 16, 64, 320);
				c1.stepper("Наближення", "Скільки пікселів мінімапи припадає на 1 блок світу. Більше — детальніше, але охоплює менше території.",
						() -> cfg.minimapZoom, v -> cfg.minimapZoom = v, 1, 1, 6);
				c1.stepper("Зсув по X", "Ручне зміщення мінімапи вбік.",
						() -> cfg.minimapOffsetX, v -> cfg.minimapOffsetX = v, 4, -400, 400);
				c1.stepper("Зсув по Y", "Ручне зміщення мінімапи вгору/вниз.",
						() -> cfg.minimapOffsetY, v -> cfg.minimapOffsetY = v, 4, -400, 400);
				c1.toggle("Стрілки до міток", "Коли мітка не влазить у видиму область мінімапи, показує трикутник на краю панелі в її напрямку.",
						() -> cfg.minimapWaypointArrows, v -> cfg.minimapWaypointArrows = v);
				c1.toggle("Мітка смерті", "Автоматично ставити мітку в місці смерті. Наступна смерть у тому самому світі замінює попередню мітку.",
						() -> cfg.deathWaypoint, v -> cfg.deathWaypoint = v);
				c1.toggle("Сітка чанків на карті", "Малювати тонкі лінії меж чанків (кожні 16 блоків) на повноекранній карті.",
						() -> cfg.mapChunkGrid, v -> cfg.mapChunkGrid = v);
				c1.toggle("Територія на карті", "Показувати позначені чанки твоєї території жовтим overlay на повноекранній карті.",
						() -> cfg.mapTerritoryOverlay, v -> cfg.mapTerritoryOverlay = v);

				c2.button("Відкрити повну карту", "Показує всі досліджені чанки цього світу й дозволяє ставити мітки. Клавіша: O.",
						() -> {
							if (Minecraft.getInstance().level != null) {
								Minecraft.getInstance().setScreen(new MapScreen(this));
							}
						});
				c2.button("Очистити карту", "Прибирає ВСІ збережені чанки й мітки для поточного світу/сервера. Це не скасувати.",
						this::clearMap);
			}
			case INFO -> {
				c1.toggle("Лічильник FPS", "Показувати поточну кількість кадрів за секунду.",
						() -> cfg.showFps, v -> cfg.showFps = v);
				c1.toggle("Фон інфо-панелі", "Малювати темний фон і рамку під FPS/координатами. Вимкни, щоб залишити тільки цифри й літери без плашки.",
						() -> cfg.infoPanelBackground, v -> cfg.infoPanelBackground = v);
				c1.cycle("Кут екрана", "Де саме малювати панель FPS/координат.",
						() -> cfg.infoPanelCorner.name(), this::cycleAnyCorner, this::cornerLabel);
				c1.stepper("Зсув по X", "Ручне зміщення інфо-панелі вбік.",
						() -> cfg.infoPanelOffsetX, v -> cfg.infoPanelOffsetX = v, 4, -300, 300);
				c1.stepper("Зсув по Y", "Ручне зміщення інфо-панелі вгору/вниз.",
						() -> cfg.infoPanelOffsetY, v -> cfg.infoPanelOffsetY = v, 4, -300, 300);
				c1.toggle("Компас", "Показувати напрям погляду (сторону світу і градуси) в інфо-панелі.",
						() -> cfg.showCompass, v -> cfg.showCompass = v);
				c1.toggle("Клавіші WASD + CPS", "Показувати компактну панель натиснутих клавіш руху, стрибка й кнопок миші, а також CPS.",
						() -> cfg.keystrokesHud, v -> cfg.keystrokesHud = v);
				c1.cycle("Кут панелі клавіш", "У якому куті малювати панель натиснутих клавіш.",
						() -> cfg.keystrokesCorner.name(), this::cycleKeystrokesCorner, this::cornerLabel);
				c1.stepper("Зсув клавіш по X", "Ручне зміщення панелі клавіш вбік.",
						() -> cfg.keystrokesOffsetX, v -> cfg.keystrokesOffsetX = v, 4, -300, 300);
				c1.stepper("Зсув клавіш по Y", "Ручне зміщення панелі клавіш вгору/вниз.",
						() -> cfg.keystrokesOffsetY, v -> cfg.keystrokesOffsetY = v, 4, -300, 300);
				c1.toggle("CPS у панелі клавіш", "Показувати кількість кліків лівою/правою кнопкою миші за останню секунду.",
						() -> cfg.keystrokesShowCps, v -> cfg.keystrokesShowCps = v);

				c2.toggle("Координата X", "Показати вісь X у панелі координат.",
						() -> cfg.showCoordX, v -> cfg.showCoordX = v);
				c2.toggle("Координата Y (висота)", "Показати лише висоту, не вмикаючи X і Z.",
						() -> cfg.showCoordY, v -> cfg.showCoordY = v);
				c2.toggle("Координата Z", "Показати вісь Z у панелі координат.",
						() -> cfg.showCoordZ, v -> cfg.showCoordZ = v);
				c2.toggle("Координати чанка", "Показати номер чанка та позицію в ньому.",
						() -> cfg.showChunkCoords, v -> cfg.showChunkCoords = v);
				c2.toggle("Таймер бою", "Скільки секунд лишилось \"бойового тегу\" після удару чи атаки. Корисно, якщо на сервері карається вихід із гри під час бою.",
						() -> cfg.combatTimer, v -> cfg.combatTimer = v);
				c2.stepper("Тривалість бою (с)", "Скільки секунд триває позначка бою.",
						() -> cfg.combatTimerSeconds, v -> cfg.combatTimerSeconds = v, 5, 5, 60);
				c2.toggle("Ping", "Показувати затримку з'єднання з сервером у мілісекундах.",
						() -> cfg.showPing, v -> cfg.showPing = v);
			}
			case DAMAGE -> {
				c1.toggle("Реальна шкода при криті", "Скільки HP ми ФАКТИЧНО зняли цілі своїм критичним ударом, і скільки нам ФАКТИЧНО зняв критом противник.",
						() -> cfg.damageMeter, v -> cfg.damageMeter = v);
				c1.toggle("Над прицілом", "Показувати панель по центру екрана, трохи вище прицілу, замість кута екрана.",
						() -> cfg.damageMeterAboveCrosshair, v -> cfg.damageMeterAboveCrosshair = v);
				c1.slider("Розмір тексту", "Масштаб шрифту панелі: 1.0 — звичайний розмір, менше — дрібніший.",
						() -> (double) cfg.damageMeterScale, v -> cfg.damageMeterScale = (float) v, 0.1, 0.4, 1.5);

				c2.cycle("Кут екрана", "У якому куті малювати панель шкоди, коли вона НЕ над прицілом.",
						() -> cfg.damageMeterCorner.name(), this::cycleDamageMeterCorner, this::cornerLabel);
				c2.stepper("Зсув по X", "Ручне зміщення панелі шкоди вбік.",
						() -> cfg.damageMeterOffsetX, v -> cfg.damageMeterOffsetX = v, 4, -300, 300);
				c2.stepper("Зсув по Y", "Зміщення панелі шкоди вгору/вниз. За замовчуванням трохи опущена, щоб стояти під панеллю FPS/координат.",
						() -> cfg.damageMeterOffsetY, v -> cfg.damageMeterOffsetY = v, 4, -300, 300);
			}
			case MISC -> {
				c1.toggle("Закид у скриню/бочку", "Shift+ЛКМ або Shift+ПКМ по предмету у СВОЄМУ інвентарі переносить у відкриту скриню, подвійну скриню або бочку всі однакові предмети (включно з хотбаром). Швидко вмикається/вимикається комбінацією Alt+F6.",
						() -> cfg.bulkChestTransfer, v -> cfg.bulkChestTransfer = v);
				c1.toggle("Забирання зі скрині", "Shift+ЛКМ або Shift+ПКМ по предмету в скрині/бочці забирає в інвентар усі такі самі предмети з цього контейнера. Швидко вмикається/вимикається комбінацією Alt+F7.",
						() -> cfg.bulkChestWithdraw, v -> cfg.bulkChestWithdraw = v);
				c1.toggle("Шкода в тултипі зброї", "При наведенні на меч в інвентарі дописує розраховану реальну шкоду: базовий урон + усі зачарування, що впливають на шкоду, окремо критичний удар.",
						() -> cfg.weaponDamageTooltip, v -> cfg.weaponDamageTooltip = v);
				c1.toggle("Світло від предмета в руці", "Факел/ліхтар/лампа/багаття в руці освітлюють територію навколо тебе. Тотем світить як факел. Важливо: світло НЕ створює жодного блоку у світі — нічого не заважає будувати.",
						() -> cfg.heldItemDynamicLight, v -> cfg.heldItemDynamicLight = v);
				c1.stepper("Яскравість світла", "Наскільки сильно світить предмет у руці: 15 — як справжній факел, менше — світло м'якше й не \"пробиває\" так далеко крізь стіни.",
						() -> cfg.heldItemLightLevel, v -> cfg.heldItemLightLevel = v, 1, 1, 15);

				// Ширина чату — реальна ванільна опція гри: даємо швидкий доступ
				// до повзунка, не роблячи окремий кастомний рендер чату.
				Minecraft mc = Minecraft.getInstance();
				c2.raw(mc.options.chatWidth().createButton(mc.options, c2.x, contentTop, colWidth));
			}
		}
	}

	/**
	 * Будує одну колонку рядків опцій. Рядки, що не потрапляють у видиму
	 * частину (з урахуванням прокрутки), просто НЕ створюються — це і є
	 * головний фікс: раніше вони створювалися й опинялися за межами екрана.
	 */
	private final class RowBuilder {
		private final int x;
		private int count = 0;

		RowBuilder(int x) {
			this.x = x;
		}

		/** Чи цей рядок зараз видимий? */
		private boolean visible() {
			int index = count;
			count++;
			return index >= scroll && index < scroll + visibleRows;
		}

		/** Y-координата для номера рядка (з урахуванням прокрутки). */
		private int yFor(int index) {
			return contentTop + (index - scroll) * ROW_HEIGHT;
		}

		private int currentY() {
			return contentTop + (count - 1 - scroll) * ROW_HEIGHT;
		}

		/** Обрізає підпис із трикрапкою, щоб він не вилазив на сусідні кнопки. */
		private String fit(String text, int width) {
			int usable = Math.max(8, width - 6);
			if (font.width(text) <= usable) return text;
			String dots = "…";
			int dotsW = font.width(dots);
			return font.plainSubstrByWidth(text, Math.max(0, usable - dotsW)) + dots;
		}

		/**
		 * Підпис степпера/слайдера: спершу лишаємо місце під ": значення",
		 * а вже потім (за потреби) скорочуємо саму назву опції. Так на
		 * вузькому екрані не зникає поточне значення — воно важливіше.
		 */
		private String fitValue(String label, String value, int width) {
			int usable = Math.max(8, width - 6);
			String suffix = ": " + value;
			int suffixW = font.width(suffix);
			if (suffixW >= usable) return font.plainSubstrByWidth(value, usable);
			int labelW = usable - suffixW;
			if (font.width(label) <= labelW) return label + suffix;
			String dots = "…";
			int dotsW = font.width(dots);
			if (labelW < dotsW + 6) return font.plainSubstrByWidth(suffix, usable);
			return font.plainSubstrByWidth(label, labelW - dotsW) + dots + suffix;
		}

		private String toggleValue(boolean value) {
			return value ? "§aУВІМК" : "§cвимк";
		}

		void toggle(String label, String tooltip, BooleanSupplier getter, Consumer<Boolean> setter) {
			if (!visible()) return;
			Button b = Button.builder(
					Component.literal(fitValue(label, toggleValue(getter.getAsBoolean()), colWidth)),
					btn -> {
						boolean newValue = !getter.getAsBoolean();
						setter.accept(newValue);
						btn.setMessage(Component.literal(fitValue(label, toggleValue(newValue), colWidth)));
					}
			).bounds(x, currentY(), colWidth, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
			addRenderableWidget(b);
		}

		void cycle(String label, String tooltip, Supplier<String> getter, Runnable advance, java.util.function.Function<String, String> valueLabel) {
			if (!visible()) return;
			Button b = Button.builder(
					Component.literal(fitValue(label, valueLabel.apply(getter.get()), colWidth)),
					btn -> {
						advance.run();
						btn.setMessage(Component.literal(
								fitValue(label, valueLabel.apply(getter.get()), colWidth)));
					}
			).bounds(x, currentY(), colWidth, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
			addRenderableWidget(b);
		}

		void stepper(String label, String tooltip, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter, int step, int min, int max) {
			if (!visible()) return;
			int y = currentY();
			int btnW = 26;
			int labelW = colWidth - btnW * 2 - 4;

			Button valueDisplay = Button.builder(
					Component.literal(fitValue(label, String.valueOf(getter.getAsInt()), labelW)), b -> {})
					.bounds(x + btnW + 2, y, labelW, 20).build();
			valueDisplay.active = false;

			Button minus = Button.builder(Component.literal("-" + step), b -> {
				setter.accept(clamp(getter.getAsInt() - step, min, max));
				valueDisplay.setMessage(Component.literal(
						fitValue(label, String.valueOf(getter.getAsInt()), labelW)));
			}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			Button plus = Button.builder(Component.literal("+" + step), b -> {
				setter.accept(clamp(getter.getAsInt() + step, min, max));
				valueDisplay.setMessage(Component.literal(
						fitValue(label, String.valueOf(getter.getAsInt()), labelW)));
			}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			addRenderableWidget(minus);
			addRenderableWidget(valueDisplay);
			addRenderableWidget(plus);
		}

		void slider(String label, String tooltip, DoubleSupplier getter, DoubleConsumer setter, double step, double min, double max) {
			if (!visible()) return;
			int y = currentY();
			int btnW = 26;
			int labelW = colWidth - btnW * 2 - 4;

			Button valueDisplay = Button.builder(
					Component.literal(fitValue(label, formatNum(getter.getAsDouble()), labelW)), b -> {})
					.bounds(x + btnW + 2, y, labelW, 20).build();
			valueDisplay.active = false;

			Button minus = Button.builder(Component.literal("-"), b -> {
				setter.accept(clampD(getter.getAsDouble() - step, min, max));
				valueDisplay.setMessage(Component.literal(
						fitValue(label, formatNum(getter.getAsDouble()), labelW)));
			}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			Button plus = Button.builder(Component.literal("+"), b -> {
				setter.accept(clampD(getter.getAsDouble() + step, min, max));
				valueDisplay.setMessage(Component.literal(
						fitValue(label, formatNum(getter.getAsDouble()), labelW)));
			}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			addRenderableWidget(minus);
			addRenderableWidget(valueDisplay);
			addRenderableWidget(plus);
		}

		void button(String label, String tooltip, Runnable action) {
			if (!visible()) return;
			Button b = Button.builder(Component.literal(fit(label, colWidth)), btn -> action.run())
					.bounds(x, currentY(), colWidth, 20)
					.tooltip(Tooltip.create(Component.literal(tooltip)))
					.build();
			addRenderableWidget(b);
		}

		void claimButton() {
			if (!visible()) return;
			Button b = Button.builder(Component.literal(fit(claimLabel(), colWidth)), btn -> {
				toggleClaimCurrentChunk();
				btn.setMessage(Component.literal(fit(claimLabel(), colWidth)));
			}).bounds(x, currentY(), colWidth, 20)
					.tooltip(Tooltip.create(Component.literal("Додає або прибирає той чанк, у якому ти зараз стоїш, з твоєї території. Клавіша: J.")))
					.build();
			b.active = Minecraft.getInstance().level != null && Minecraft.getInstance().player != null;
			addRenderableWidget(b);
		}

		/** Готовий віджди (наприклад, ванільний повзунок ширини чату). */
		void raw(net.minecraft.client.gui.components.AbstractWidget widget) {
			if (!visible()) return;
			widget.setY(currentY());
			addRenderableWidget(widget);
		}
	}

	private void switchTab(Tab tab) {
		this.currentTab = tab;
		this.scroll = 0;
		this.clearWidgets();
		this.init();
	}

	private void scrollBy(int rows) {
		int newScroll = scroll + rows;
		int maxScroll = Math.max(0, totalRows - visibleRows);
		int clamped = Mth.clamp(newScroll, 0, maxScroll);
		if (clamped == scroll) return;

		scroll = clamped;
		// Перебудовуємо віджди — так рядки, які стали видимими/невидимими,
		// створюються або зникають правильно.
		this.clearWidgets();
		this.init();
	}

	private void clampScroll() {
		int maxScroll = Math.max(0, totalRows - visibleRows);
		scroll = Mth.clamp(scroll, 0, maxScroll);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY > 0) {
			scrollBy(-1);
			return true;
		} else if (scrollY < 0) {
			scrollBy(1);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFF4F6F8);
		HudStyle.divider(graphics, this.width / 2 - 90, 20, this.width / 2 + 90);

		// Індикатор прокрутки — тонка смужка праворуч, щоб було видно, що
		// список довший за екран і є куди гортати.
		if (totalRows > visibleRows) {
			int trackH = visibleRows * ROW_HEIGHT;
			int thumbH = Math.max(20, trackH * visibleRows / totalRows);
			int maxScroll = totalRows - visibleRows;
			int thumbY = contentTop + (maxScroll == 0 ? 0 : (trackH - thumbH) * scroll / maxScroll);
			int x = this.width - 6;
			graphics.fill(x, contentTop, x + 3, contentTop + trackH, 0x40FFFFFF);
			graphics.fill(x, thumbY, x + 3, thumbY + thumbH, 0xC0FFFFFF);
		}
	}

	/**
	 * Фон меню та підкладки колонок. Малюється ДО віджетів (їх рендерить
	 * {@link #render}), тому кнопки завжди залишаються зверху й читаються
	 * однаково добре на будь-якому фоні.
	 */
	@Override
	public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.renderBackground(graphics, mouseX, mouseY, partialTick);
		int panelTop = contentTop - 4;
		int shownRows = Math.max(1, Math.min(visibleRows, totalRows));
		int panelBottom = Math.min(this.height - 30, contentTop + shownRows * ROW_HEIGHT - 2);
		if (panelBottom > panelTop) {
			HudStyle.panel(graphics, col1X - 4, panelTop, col1X + colWidth + 4, panelBottom);
			HudStyle.panel(graphics, col2X - 4, panelTop, col2X + colWidth + 4, panelBottom);
		}
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void onClose() {
		cfg.save();
		Minecraft.getInstance().setScreen(parent);
	}

	// --- перемикання кутів/розкладок ---

	private void cycleArmorCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.armorCorner.ordinal() + 1) % all.length;
		cfg.armorCorner = all[next];
	}

	private void cycleAnyCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.infoPanelCorner.ordinal() + 1) % all.length;
		cfg.infoPanelCorner = all[next];
	}

	private void cycleTargetBuffsCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.targetBuffsCorner.ordinal() + 1) % all.length;
		cfg.targetBuffsCorner = all[next];
	}

	private void cycleDamageMeterCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.damageMeterCorner.ordinal() + 1) % all.length;
		cfg.damageMeterCorner = all[next];
	}

	private void cycleMinimapCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.minimapCorner.ordinal() + 1) % all.length;
		cfg.minimapCorner = all[next];
	}

	private void cycleKeystrokesCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.keystrokesCorner.ordinal() + 1) % all.length;
		cfg.keystrokesCorner = all[next];
	}

	private void cycleOtherOverlayAnchor() {
		HudConfig.OverlayAnchor[] all = HudConfig.OverlayAnchor.values();
		int next = (cfg.otherOverlayAnchor.ordinal() + 1) % all.length;
		cfg.otherOverlayAnchor = all[next];
	}

	private String overlayAnchorLabel(String value) {
		return switch (HudConfig.OverlayAnchor.valueOf(value)) {
			case ABOVE -> "Зверху";
			case LEFT -> "Зліва";
			case RIGHT -> "Справа";
		};
	}

	private void clearMap() {
		ChunkColorCache.clearCurrentWorld();
		MinimapHud.invalidateMapTexture();
		// Кнопка обіцяє очистити карту ЛИШЕ поточного світу/сервера.
		// Раніше тут видалялися мітки всіх світів одразу.
		String worldKey = VolyaHudClient.currentWorldKey(Minecraft.getInstance());
		cfg.waypoints.values().forEach(list ->
				list.removeIf(wp -> cfg.isWaypointVisible(wp, worldKey)));
		cfg.save();
	}

	private void resetOptions() {
		cfg.resetToDefaults();
		this.clearWidgets();
		this.init();
	}

	private String armorLayoutLabel(String v) {
		return cfg.armorLayout == HudConfig.ArmorLayout.GRID ? "Сітка 2x2" : "Один рядок";
	}

	private String cornerLabel(String v) {
		return switch (HudConfig.ScreenCorner.valueOf(v)) {
			case BOTTOM_LEFT -> "Знизу-зліва";
			case BOTTOM_RIGHT -> "Знизу-справа";
			case TOP_LEFT -> "Зверху-зліва";
			case TOP_RIGHT -> "Зверху-справа";
		};
	}

	private String claimLabel() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "Додати поточний чанк";
		String dim = mc.level.dimension().location().toString();
		String world = VolyaHudClient.currentWorldKey(mc);
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		boolean claimed = TerritoryManager.isClaimed(world, dim, cx, cz);
		return claimed ? "✗ Прибрати поточний чанк" : "✓ Додати поточний чанк";
	}

	private void toggleClaimCurrentChunk() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		String dim = mc.level.dimension().location().toString();
		String world = VolyaHudClient.currentWorldKey(mc);
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		TerritoryManager.toggle(world, dim, cx, cz);
	}

	private void clearTerritory() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		TerritoryManager.clearWorldDimension(
				VolyaHudClient.currentWorldKey(mc),
				mc.level.dimension().location().toString());
	}

	private static String formatNum(double v) {
		if (v == Math.floor(v)) return String.valueOf((long) v);
		// Два знаки після коми потрібні для кроку 0.05 (наприклад, 0.85),
		// інакше 0.85 округлялось до 0.9 і налаштування виглядало неточним.
		String s = String.format("%.2f", v);
		if (s.endsWith("0")) s = s.substring(0, s.length() - 1);
		return s;
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static double clampD(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

}
