package com.volyacraft.volyahud.client.gui;

import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
import com.volyacraft.volyahud.client.hud.minimap.ChunkColorCache;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleConsumer;
import java.util.function.Supplier;

/**
 * Меню налаштувань HUD у стилі Sodium: вкладки-категорії зверху, під ними
 * чіткий двоколонковий список опцій із підказками (наведи мишкою — побачиш
 * опис), і окрема вкладка не захаращує екран усіма ~20 опціями одразу.
 */
public class ConfigScreen extends Screen {

	private enum Tab {
		ARMOR("Броня"),
		PLAYERS("Гравці"),
		THROWS("Кидки і радар"),
		BUILD("Будівництво"),
		MINIMAP("Мінімапа"),
		INFO("Інфо-панель"),
		DAMAGE("Шкода"),
		MISC("Чат і інше");

		final String title;

		Tab(String title) {
			this.title = title;
		}
	}

	private static final int ROW_HEIGHT = 24;
	private static final int COL_WIDTH = 200;
	private static final int COL_GAP = 16;
	private static final int TAB_HEIGHT = 22;
	private static final int CONTENT_TOP = 54;

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;
	private Tab currentTab = Tab.ARMOR;

	public ConfigScreen(Screen parent) {
		super(Component.literal("VolyaHUD — налаштування"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		// БАГ-ФІКС: раніше ширина вкладок рахувалась як фіксовані 108px * 7
		// вкладок (= 756px) незалежно від реального розміру вікна. При вищому
		// GUI Scale (або вузькому вікні) guiScaledWidth легко буває меншим за
		// 756 — вкладки просто вилазили за межі екрана й ставали недоступні
		// для кліку/виглядали "криво збоку". Тепер ширина кожної вкладки
		// підлаштовується під реальну ширину екрана.
		int tabCount = Tab.values().length;
		int tabGap = 2;
		int maxTabsWidth = this.width - 16;
		int tabWidth = Mth.clamp((maxTabsWidth - tabGap * (tabCount - 1)) / tabCount, 46, 106);
		int totalTabsWidth = tabWidth * tabCount + tabGap * (tabCount - 1);
		int tabX = this.width / 2 - totalTabsWidth / 2;
		for (Tab tab : Tab.values()) {
			boolean selected = tab == currentTab;
			Button tabButton = Button.builder(
					Component.literal((selected ? "\u25B8 " : "") + tab.title),
					b -> switchTab(tab)
			).bounds(tabX, 26, tabWidth, TAB_HEIGHT).build();
			tabButton.active = !selected;
			this.addRenderableWidget(tabButton);
			tabX += tabWidth + tabGap;
		}

		// Захист від того самого класу бага й тут: не даємо колонкам піти
		// за межі екрана на дуже вузьких вікнах/високому GUI Scale.
		int col1X = Math.max(8, this.width / 2 - (COL_WIDTH * 2 + COL_GAP) / 2);
		int col2X = col1X + COL_WIDTH + COL_GAP;
		int y1 = CONTENT_TOP;
		int y2 = CONTENT_TOP;

		switch (currentTab) {
			case ARMOR -> {
				y1 = addToggle(col1X, y1, "Своя броня знизу",
						"Показувати панель зі своєю бронею над хотбаром.",
						() -> cfg.ownArmorHud, v -> cfg.ownArmorHud = v);
				y1 = addCycle(col1X, y1, "Розкладка", "Сітка 2x2 або один рядок з 4 слотів.",
						() -> cfg.armorLayout.name(), () -> {
							cfg.armorLayout = cfg.armorLayout == HudConfig.ArmorLayout.GRID
									? HudConfig.ArmorLayout.LINE : HudConfig.ArmorLayout.GRID;
						}, this::armorLayoutLabel);
				y1 = addCycle(col1X, y1, "Кут екрана",
						"У якому куті малювати свою броню.",
						() -> cfg.armorCorner.name(), this::cycleArmorCorner, this::cornerLabel);
				y1 = addToggle(col1X, y1, "Впритул до низу",
						"Притиснути панель до самого краю екрана (може лягати на хотбар по краях), замість того щоб стояти над ним.",
						() -> cfg.armorFlushBottom, v -> cfg.armorFlushBottom = v);
				y1 = addStepper(col1X, y1, "Зсув по X",
						"Ручне зміщення панелі вбік — щоб розминутись із чатом чи іншим HUD.",
						() -> cfg.armorOffsetX, v -> cfg.armorOffsetX = v, 4, -200, 200);
				y1 = addStepper(col1X, y1, "Зсув по Y",
						"Ручне зміщення панелі вгору/вниз. Мінус — вище, плюс — нижче.",
						() -> cfg.armorOffsetY, v -> cfg.armorOffsetY = v, 4, -200, 200);

				y2 = addToggle(col2X, y2, "Броня інших гравців",
						"Показувати іконки броні над головою гравців, на яких дивишся.",
						() -> cfg.otherPlayersArmorHud, v -> cfg.otherPlayersArmorHud = v);
				y2 = addToggle(col2X, y2, "Показ екіпіровки цілі",
						"Показувати, що тримає в руках гравець, на якого дивишся.",
						() -> cfg.inventoryPeek, v -> cfg.inventoryPeek = v);
				y2 = addStepper(col2X, y2, "Зсув екіпіровки по Y",
						"Ручне зміщення рядка екіпіровки (і зброї в руках) над головою противника відносно HP. Мінус — далі від HP, плюс — ближче. Не може накластися на HP: зазор нижче завжди підстраховує мінімум 1px.",
						() -> cfg.otherEquipmentOffsetY, v -> cfg.otherEquipmentOffsetY = v, 2, -60, 60);
				y2 = addStepper(col2X, y2, "Зазор HP \u2194 екіпіровка",
						"Бажаний проміжок (px) між рядком HP і рядком екіпіровки над головою противника (і так само між екіпіровкою й зброєю в руках).",
						() -> cfg.otherHpEquipmentGap, v -> cfg.otherHpEquipmentGap = v, 1, 1, 20);
			}
			case PLAYERS -> {
				y1 = addToggle(col1X, y1, "HP-мітки над гравцями",
						"Показувати здоров'я над головою інших гравців.",
						() -> cfg.healthTags, v -> cfg.healthTags = v);
				y1 = addSlider(col1X, y1, "Дистанція показу",
						"Максимальна відстань (у блоках), на якій показуються мітки гравців.",
						() -> cfg.otherPlayersRenderDistance, v -> cfg.otherPlayersRenderDistance = v, 8, 4, 128);
				y1 = addToggle(col1X, y1, "Дистанція в HP-мітці",
						"Показувати відстань у блоках поруч з HP гравця (наприклад \"18/20 \u2764 12m\").",
						() -> cfg.healthTagDistance, v -> cfg.healthTagDistance = v);
				y1 = addStepper(col1X, y1, "Зсув HP по Y",
						"Ручне зміщення рядка HP над головою противника відносно ніка. Мінус — далі від ніка (вище), плюс — ближче. Рухає разом із собою й екіпіровку/зброю над ним, тому стос не розсипається.",
						() -> cfg.otherHpOffsetY, v -> cfg.otherHpOffsetY = v, 2, -60, 60);

				y2 = addToggle(col2X, y2, "Бафи цілі",
						"Показувати активні ефекти гравця, на якого дивишся.",
						() -> cfg.targetBuffsHud, v -> cfg.targetBuffsHud = v);
				y2 = addCycle(col2X, y2, "Кут екрана",
						"У якому куті малювати панель бафів цілі.",
						() -> cfg.targetBuffsCorner.name(), this::cycleTargetBuffsCorner, this::cornerLabel);
				y2 = addStepper(col2X, y2, "Зсув по X",
						"Ручне зміщення панелі бафів вбік.",
						() -> cfg.targetBuffsOffsetX, v -> cfg.targetBuffsOffsetX = v, 4, -300, 300);
				y2 = addStepper(col2X, y2, "Зсув по Y",
						"Ручне зміщення панелі бафів вгору/вниз.",
						() -> cfg.targetBuffsOffsetY, v -> cfg.targetBuffsOffsetY = v, 4, -300, 300);
				y2 = addToggle(col2X, y2, "Сповіщення про тотем",
						"Текстове сповіщення, коли поруч хтось використав тотем безсмертя.",
						() -> cfg.totemAlert, v -> cfg.totemAlert = v);
				y2 = addStepper(col2X, y2, "Зсув сповіщень по X",
						"Ручне зміщення банера сповіщень про тотем вбік від центру.",
						() -> cfg.totemAlertOffsetX, v -> cfg.totemAlertOffsetX = v, 4, -300, 300);
				y2 = addStepper(col2X, y2, "Зсув сповіщень по Y",
						"Ручне зміщення банера сповіщень про тотем вгору/вниз.",
						() -> cfg.totemAlertOffsetY, v -> cfg.totemAlertOffsetY = v, 4, -300, 300);
			}
			case THROWS -> {
				y1 = addToggle(col1X, y1, "Прев'ю траєкторії кидка",
						"Лінія напрямку, поки тримаєш перлу/зілля/пляшку досвіду в руці.",
						() -> cfg.pearlTrajectory, v -> cfg.pearlTrajectory = v);
				y1 = addToggle(col1X, y1, "Слід кинутого предмета",
						"Лінія за вже кинутою перлою/зіллям/пляшкою досвіду в польоті.",
						() -> cfg.pearlTrail, v -> cfg.pearlTrail = v);

				y2 = addToggle(col2X, y2, "Індикатор гравців над хотбаром",
						"Горизонтальна шкала, що показує, з якого боку поруч знаходяться гравці.",
						() -> cfg.pvpRadar, v -> cfg.pvpRadar = v);
				y2 = addSlider(col2X, y2, "Радіус радара",
						"Відстань (у блоках), на якій гравці з'являються на індикаторі.",
						() -> cfg.pvpRadarActivationDistance, v -> cfg.pvpRadarActivationDistance = v, 4, 4, 64);
				y2 = addStepper(col2X, y2, "Зсув по X",
						"Ручне зміщення шкали радара вбік від центру.",
						() -> cfg.pvpRadarOffsetX, v -> cfg.pvpRadarOffsetX = v, 4, -300, 300);
				y2 = addStepper(col2X, y2, "Зсув по Y",
						"Ручне зміщення шкали радара вгору/вниз.",
						() -> cfg.pvpRadarOffsetY, v -> cfg.pvpRadarOffsetY = v, 4, -300, 300);
			}
			case BUILD -> {
				y1 = addToggle(col1X, y1, "Сітка чанка",
						"Малює тонкі лінії на кожній межі чанка (кожні 16 блоків) навколо тебе — зручно для рівної забудови. Клавіша за замовчуванням: G.",
						() -> cfg.chunkGrid, v -> cfg.chunkGrid = v);
				y1 = addToggle(col1X, y1, "Підпис \"16 x 16\"",
						"Показує підпис на стінах чанка, у якому ти зараз стоїш.",
						() -> cfg.chunkGridLabels, v -> cfg.chunkGridLabels = v);

				y2 = addToggle(col2X, y2, "Кордон території",
						"Підсвічує жовтим межу твоїх позначених чанків (\"свого міста\"). Клавіша за замовчуванням: H.",
						() -> cfg.territoryBorder, v -> cfg.territoryBorder = v);
				y2 = addClaimButton(col2X, y2);
				y2 = addButton(col2X, y2, "Очистити територію (цей світ)",
						"Прибирає ВСІ позначені чанки в поточному вимірі. Це не скасувати.",
						this::clearTerritory);
			}
			case MINIMAP -> {
				y1 = addToggle(col1X, y1, "Мінімапа",
						"Показувати невеличку мапу рельєфу в кутку екрана. Ніяких мобів чи гравців вона не показує — лише блоки.",
						() -> cfg.minimap, v -> cfg.minimap = v);
				y1 = addCycle(col1X, y1, "Кут екрана",
						"У якому куті малювати мінімапу.",
						() -> cfg.minimapCorner.name(), this::cycleMinimapCorner, this::cornerLabel);
				y1 = addStepper(col1X, y1, "Розмір (px)",
						"Розмір панелі мінімапи в пікселях.",
						() -> cfg.minimapSize, v -> cfg.minimapSize = v, 16, 64, 320);
				y1 = addStepper(col1X, y1, "Наближення",
						"Скільки пікселів мінімапи припадає на 1 блок світу. Більше — детальніше, але охоплює менше території.",
						() -> cfg.minimapZoom, v -> cfg.minimapZoom = v, 1, 1, 6);
				y1 = addStepper(col1X, y1, "Зсув по X",
						"Ручне зміщення мінімапи вбік.",
						() -> cfg.minimapOffsetX, v -> cfg.minimapOffsetX = v, 4, -400, 400);
				y1 = addStepper(col1X, y1, "Зсув по Y",
						"Ручне зміщення мінімапи вгору/вниз.",
						() -> cfg.minimapOffsetY, v -> cfg.minimapOffsetY = v, 4, -400, 400);

				y2 = addButton(col2X, y2, "Відкрити повну карту",
						"Показує всі досліджені чанки цього світу й дозволяє ставити мітки. Клавіша за замовчуванням: M.",
						() -> {
							if (Minecraft.getInstance().level != null) {
								Minecraft.getInstance().setScreen(new MapScreen(this));
							}
						});
				y2 = addButton(col2X, y2, "Очистити карту (цей світ)",
						"Прибирає ВСІ збережені чанки й мітки для поточного світу/сервера. Це не скасувати.",
						this::clearMap);
			}
			case INFO -> {
				y1 = addToggle(col1X, y1, "Лічильник FPS",
						"Показувати поточну кількість кадрів за секунду.",
						() -> cfg.showFps, v -> cfg.showFps = v);
				y1 = addCycle(col1X, y1, "Кут екрана", "Де саме малювати панель FPS/координат.",
						() -> cfg.infoPanelCorner.name(), this::cycleAnyCorner, this::cornerLabel);
				y1 = addStepper(col1X, y1, "Зсув по X",
						"Ручне зміщення інфо-панелі вбік.",
						() -> cfg.infoPanelOffsetX, v -> cfg.infoPanelOffsetX = v, 4, -300, 300);
				y1 = addStepper(col1X, y1, "Зсув по Y",
						"Ручне зміщення інфо-панелі вгору/вниз.",
						() -> cfg.infoPanelOffsetY, v -> cfg.infoPanelOffsetY = v, 4, -300, 300);
				y1 = addToggle(col1X, y1, "Компас",
						"Показувати напрям погляду (сторону світу і градуси) в інфо-панелі.",
						() -> cfg.showCompass, v -> cfg.showCompass = v);

				y2 = addToggle(col2X, y2, "Координата X",
						"Показати вісь X у панелі координат.",
						() -> cfg.showCoordX, v -> cfg.showCoordX = v);
				y2 = addToggle(col2X, y2, "Координата Y (висота)",
						"Показати лише висоту, не вмикаючи X і Z.",
						() -> cfg.showCoordY, v -> cfg.showCoordY = v);
				y2 = addToggle(col2X, y2, "Координата Z",
						"Показати вісь Z у панелі координат.",
						() -> cfg.showCoordZ, v -> cfg.showCoordZ = v);
				y2 = addToggle(col2X, y2, "Координати чанка",
						"Показати номер чанка та позицію в ньому.",
						() -> cfg.showChunkCoords, v -> cfg.showChunkCoords = v);
			}
			case DAMAGE -> {
				y1 = addToggle(col1X, y1, "Реальна шкода при криті",
						"Окрема панель: скільки HP ми ФАКТИЧНО зняли цілі своїм критичним ударом, і скільки нам ФАКТИЧНО зняв критом противник (не \"сирий\" урон предмета, а вже з урахуванням броні/зачарувань/абсорбції).",
						() -> cfg.damageMeter, v -> cfg.damageMeter = v);
				y1 = addToggle(col1X, y1, "Над прицілом",
						"Показувати панель по центру екрана, трохи вище прицілу, замість кута екрана.",
						() -> cfg.damageMeterAboveCrosshair, v -> cfg.damageMeterAboveCrosshair = v);
				y1 = addSlider(col1X, y1, "Розмір тексту",
						"Масштаб шрифту панелі: 1.0 — звичайний розмір, менше — дрібніший і компактніший текст.",
						() -> (double) cfg.damageMeterScale, v -> cfg.damageMeterScale = (float) v, 0.1, 0.4, 1.5);

				y2 = addCycle(col2X, y2, "Кут екрана",
						"У якому куті малювати панель шкоди, коли вона НЕ над прицілом.",
						() -> cfg.damageMeterCorner.name(), this::cycleDamageMeterCorner, this::cornerLabel);
				y2 = addStepper(col2X, y2, "Зсув по X",
						"Ручне зміщення панелі шкоди вбік.",
						() -> cfg.damageMeterOffsetX, v -> cfg.damageMeterOffsetX = v, 4, -300, 300);
				y2 = addStepper(col2X, y2, "Зсув по Y",
						"Ручне зміщення панелі шкоди вгору/вниз. За замовчуванням трохи опущена, щоб стояти під панеллю FPS/координат.",
						() -> cfg.damageMeterOffsetY, v -> cfg.damageMeterOffsetY = v, 4, -300, 300);
			}
			case MISC -> {
				y1 = addToggle(col1X, y1, "Шкода в тултипі зброї",
						"При наведенні на меч/сокиру в інвентарі дописує розраховану реальну шкоду по HP: базовий урон + усі зачарування, що впливають на шкоду (Sharpness/Smite/Bane of Arthropods, а також кастомні чари з модів на кшталт ExcellentEnchants) з урахуванням рівня кожного, окремо критичний удар.",
						() -> cfg.weaponDamageTooltip, v -> cfg.weaponDamageTooltip = v);

				// Ширина чату — реальна ванільна опція гри, щоб не робити окремий
				// кастомний рендер чату (і не конфліктувати з ним): просто даємо
				// швидкий доступ до повзунка ширини прямо з нашого меню.
				Minecraft mc = Minecraft.getInstance();
				this.addRenderableWidget(mc.options.chatWidth().createButton(mc.options, col1X, y1, COL_WIDTH));
				y1 += ROW_HEIGHT;
			}
		}

		int bottomMost = Math.max(y1, y2) + 10;
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			cfg.save();
			Minecraft.getInstance().setScreen(parent);
		}).bounds(this.width / 2 - 75, Math.min(bottomMost, this.height - 28), 150, 20).build());
	}

	private void switchTab(Tab tab) {
		this.currentTab = tab;
		this.clearWidgets();
		this.init();
	}

	// БАГ-ФІКС/ПОКРАЩЕННЯ: раніше кут своєї броні можна було перемикати лише
	// між двома нижніми кутами, хоча сам ArmorHud.renderOwn вже давно вміє
	// малювати панель і зверху (onTop) — тепер кут броні цикається так само,
	// як і всі інші панелі (мінімапа, інфо-панель, бафи), і всі 4 кути реально доступні.
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

	private void clearMap() {
		ChunkColorCache.clearCurrentWorld();
		cfg.waypoints.clear();
		cfg.save();
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

	// ---- рядки опцій ----

	private int addToggle(int x, int y, String label, String tooltip, BooleanSupplier getter, Consumer<Boolean> setter) {
		Button b = Button.builder(
				toggleText(label, getter.getAsBoolean()),
				btn -> {
					boolean newValue = !getter.getAsBoolean();
					setter.accept(newValue);
					btn.setMessage(toggleText(label, newValue));
				}
		).bounds(x, y, COL_WIDTH, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
		this.addRenderableWidget(b);
		return y + ROW_HEIGHT;
	}

	private int addCycle(int x, int y, String label, String tooltip, Supplier<String> getter, Runnable advance, java.util.function.Function<String, String> valueLabel) {
		Button b = Button.builder(
				Component.literal(label + ": " + valueLabel.apply(getter.get())),
				btn -> {
					advance.run();
					btn.setMessage(Component.literal(label + ": " + valueLabel.apply(getter.get())));
				}
		).bounds(x, y, COL_WIDTH, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
		this.addRenderableWidget(b);
		return y + ROW_HEIGHT;
	}

	private int addStepper(int x, int y, String label, String tooltip, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter, int step, int min, int max) {
		int btnW = 26;
		int labelW = COL_WIDTH - btnW * 2 - 4;

		Button valueDisplay = Button.builder(Component.literal(label + ": " + getter.getAsInt()), b -> {})
				.bounds(x + btnW + 2, y, labelW, 20).build();
		valueDisplay.active = false;

		Button minus = Button.builder(Component.literal("-" + step), b -> {
			int v = clamp(getter.getAsInt() - step, min, max);
			setter.accept(v);
			valueDisplay.setMessage(Component.literal(label + ": " + getter.getAsInt()));
		}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

		Button plus = Button.builder(Component.literal("+" + step), b -> {
			int v = clamp(getter.getAsInt() + step, min, max);
			setter.accept(v);
			valueDisplay.setMessage(Component.literal(label + ": " + getter.getAsInt()));
		}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

		this.addRenderableWidget(minus);
		this.addRenderableWidget(valueDisplay);
		this.addRenderableWidget(plus);
		return y + ROW_HEIGHT;
	}

	private int addSlider(int x, int y, String label, String tooltip, DoubleSupplier getter, DoubleConsumer setter, double step, double min, double max) {
		int btnW = 26;
		int labelW = COL_WIDTH - btnW * 2 - 4;
		Button valueDisplay = Button.builder(Component.literal(label + ": " + formatNum(getter.getAsDouble())), b -> {})
				.bounds(x + btnW + 2, y, labelW, 20).build();
		valueDisplay.active = false;

		Button minus = Button.builder(Component.literal("-"), b -> {
			double v = clampD(getter.getAsDouble() - step, min, max);
			setter.accept(v);
			valueDisplay.setMessage(Component.literal(label + ": " + formatNum(getter.getAsDouble())));
		}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

		Button plus = Button.builder(Component.literal("+"), b -> {
			double v = clampD(getter.getAsDouble() + step, min, max);
			setter.accept(v);
			valueDisplay.setMessage(Component.literal(label + ": " + formatNum(getter.getAsDouble())));
		}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

		this.addRenderableWidget(minus);
		this.addRenderableWidget(valueDisplay);
		this.addRenderableWidget(plus);
		return y + ROW_HEIGHT;
	}

	private int addButton(int x, int y, String label, String tooltip, Runnable action) {
		Button b = Button.builder(Component.literal(label), btn -> action.run())
				.bounds(x, y, COL_WIDTH, 20)
				.tooltip(Tooltip.create(Component.literal(tooltip)))
				.build();
		this.addRenderableWidget(b);
		return y + ROW_HEIGHT;
	}

	/** Кнопка "додати/прибрати поточний чанк" — текст і стан оновлюються прямо при кліку. */
	private int addClaimButton(int x, int y) {
		Button b = Button.builder(Component.literal(claimLabel()), btn -> {
			toggleClaimCurrentChunk();
			btn.setMessage(Component.literal(claimLabel()));
		}).bounds(x, y, COL_WIDTH, 20)
				.tooltip(Tooltip.create(Component.literal("Додає або прибирає той чанк, у якому ти зараз стоїш, з твоєї території. Клавіша за замовчуванням: J.")))
				.build();
		b.active = Minecraft.getInstance().level != null && Minecraft.getInstance().player != null;
		this.addRenderableWidget(b);
		return y + ROW_HEIGHT;
	}

	private String claimLabel() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return "Додати поточний чанк";
		String dim = mc.level.dimension().location().toString();
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		boolean claimed = TerritoryManager.isClaimed(dim, cx, cz);
		return claimed ? "\u2717 Прибрати поточний чанк" : "\u2713 Додати поточний чанк";
	}

	private void toggleClaimCurrentChunk() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) return;
		String dim = mc.level.dimension().location().toString();
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		TerritoryManager.toggle(dim, cx, cz);
	}

	private void clearTerritory() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) return;
		TerritoryManager.clearDimension(mc.level.dimension().location().toString());
	}

	private static String formatNum(double v) {
		return v == Math.floor(v) ? String.valueOf((long) v) : String.format("%.1f", v);
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static double clampD(double v, double min, double max) {
		return Math.max(min, Math.min(max, v));
	}

	private Component toggleText(String label, boolean value) {
		return Component.literal(label + ": " + (value ? "\u00A7aУВІМК" : "\u00A7cвимк"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);
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
}
