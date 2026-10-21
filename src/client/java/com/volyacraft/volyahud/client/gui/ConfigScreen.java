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
		BUILD("Будівництво"),
		MINIMAP("Мінімапа"),
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
	private static final int COL_GAP = 16;
	private static final int TAB_HEIGHT = 22;
	private static final int CONTENT_TOP = 54;
	/** Скільки рядків опцій одночасно показувати на екрані. */
	private static final int VISIBLE_ROWS = 7;

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;
	private Tab currentTab = Tab.ARMOR;

	/** Зсув прокрутки (в рядках). 0 = верх списку. */
	private int scroll = 0;
	/** Скільки всього рядків у поточній вкладці. */
	private int totalRows = 0;

	public ConfigScreen(Screen parent) {
		super(Component.literal("VolyaHUD — налаштування"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int tabCount = Tab.values().length;
		int tabGap = 2;
		int maxTabsWidth = this.width - 16;
		int tabWidth = Mth.clamp((maxTabsWidth - tabGap * (tabCount - 1)) / tabCount, 44, 104);
		int totalTabsWidth = tabWidth * tabCount + tabGap * (tabCount - 1);
		int tabX = this.width / 2 - totalTabsWidth / 2;
		for (Tab tab : Tab.values()) {
			boolean selected = tab == currentTab;
			Button tabButton = Button.builder(
					Component.literal((selected ? "▸ " : "") + tab.title),
					b -> switchTab(tab)
			).bounds(tabX, 26, tabWidth, TAB_HEIGHT).build();
			tabButton.active = !selected;
			this.addRenderableWidget(tabButton);
			tabX += tabWidth + tabGap;
		}

		// Лічильники рядків для кожної колонки — щоб знати, скільки всього
		// рядків (для прокрутки) і які рядки зараз видимі.
		int col1X = Math.max(8, this.width / 2 - (COL_WIDTH * 2 + COL_GAP) / 2);
		int col2X = col1X + COL_WIDTH + COL_GAP;

		RowBuilder c1 = new RowBuilder(col1X);
		RowBuilder c2 = new RowBuilder(col2X);

		buildTab(c1, c2);

		totalRows = Math.max(c1.count, c2.count);
		clampScroll();

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
		this.addRenderableWidget(up);
		this.addRenderableWidget(down);

		// Кнопка "Готово" — завжди внизу екрана, незалежно від довжини списку.
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			cfg.save();
			Minecraft.getInstance().setScreen(parent);
		}).bounds(this.width / 2 - 75, this.height - 26, 150, 20).build());
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

				c2.toggle("Броня інших гравців", "Показувати іконки броні над головою гравців, на яких дивишся.",
						() -> cfg.otherPlayersArmorHud, v -> cfg.otherPlayersArmorHud = v);
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
				c1.toggle("Сітка чанка", "Тонкі лінії на кожній межі чанка (кожні 16 блоків) навколо тебе — зручно для рівної забудови. Клавіша: G.",
						() -> cfg.chunkGrid, v -> cfg.chunkGrid = v);
				c1.toggle("Підпис \"16 x 16\"", "Показує підпис на стінах чанка, у якому ти зараз стоїш.",
						() -> cfg.chunkGridLabels, v -> cfg.chunkGridLabels = v);
				c1.button("Скинути налаштування", "Повертає ВСІ опції HUD до стандартних. Мітки та позначені чанки НЕ змінюються. Це не скасувати.",
						this::resetOptions);

				c2.toggle("Кордон території", "Підсвічує жовтим межу твоїх позначених чанків (\"свого міста\"). Клавіша: H.",
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

				c2.button("Відкрити повну карту", "Показує всі досліджені чанки цього світу й дозволяє ставити мітки. Клавіша: M.",
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
				c1.cycle("Кут екрана", "Де саме малювати панель FPS/координат.",
						() -> cfg.infoPanelCorner.name(), this::cycleAnyCorner, this::cornerLabel);
				c1.stepper("Зсув по X", "Ручне зміщення інфо-панелі вбік.",
						() -> cfg.infoPanelOffsetX, v -> cfg.infoPanelOffsetX = v, 4, -300, 300);
				c1.stepper("Зсув по Y", "Ручне зміщення інфо-панелі вгору/вниз.",
						() -> cfg.infoPanelOffsetY, v -> cfg.infoPanelOffsetY = v, 4, -300, 300);
				c1.toggle("Компас", "Показувати напрям погляду (сторону світу і градуси) в інфо-панелі.",
						() -> cfg.showCompass, v -> cfg.showCompass = v);

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
				c1.toggle("Шкода в тултипі зброї", "При наведенні на меч в інвентарі дописує розраховану реальну шкоду: базовий урон + усі зачарування, що впливають на шкоду, окремо критичний удар.",
						() -> cfg.weaponDamageTooltip, v -> cfg.weaponDamageTooltip = v);
				c1.toggle("Світло від предмета в руці", "Факел/ліхтар/лампа/багаття в руці освітлюють територію навколо тебе. Тотем світить як факел. Важливо: світло НЕ створює жодного блоку у світі — нічого не заважає будувати.",
						() -> cfg.heldItemDynamicLight, v -> cfg.heldItemDynamicLight = v);
				c1.stepper("Яскравість світла", "Наскільки сильно світить предмет у руці: 15 — як справжній факел, менше — світло м'якше й не \"пробиває\" так далеко крізь стіни.",
						() -> cfg.heldItemLightLevel, v -> cfg.heldItemLightLevel = v, 1, 1, 15);

				// Ширина чату — реальна ванільна опція гри: даємо швидкий доступ
				// до повзунка, не роблячи окремий кастомний рендер чату.
				Minecraft mc = Minecraft.getInstance();
				c2.raw(mc.options.chatWidth().createButton(mc.options, c2.x, CONTENT_TOP, COL_WIDTH));
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
			return index >= scroll && index < scroll + VISIBLE_ROWS;
		}

		/** Y-координата для номера рядка (з урахуванням прокрутки). */
		private int yFor(int index) {
			return CONTENT_TOP + (index - scroll) * ROW_HEIGHT;
		}

		private int currentY() {
			return CONTENT_TOP + (count - 1 - scroll) * ROW_HEIGHT;
		}

		void toggle(String label, String tooltip, BooleanSupplier getter, Consumer<Boolean> setter) {
			if (!visible()) return;
			Button b = Button.builder(
					toggleText(label, getter.getAsBoolean()),
					btn -> {
						boolean newValue = !getter.getAsBoolean();
						setter.accept(newValue);
						btn.setMessage(toggleText(label, newValue));
					}
			).bounds(x, currentY(), COL_WIDTH, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
			addRenderableWidget(b);
		}

		void cycle(String label, String tooltip, Supplier<String> getter, Runnable advance, java.util.function.Function<String, String> valueLabel) {
			if (!visible()) return;
			Button b = Button.builder(
					Component.literal(label + ": " + valueLabel.apply(getter.get())),
					btn -> {
						advance.run();
						btn.setMessage(Component.literal(label + ": " + valueLabel.apply(getter.get())));
					}
			).bounds(x, currentY(), COL_WIDTH, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();
			addRenderableWidget(b);
		}

		void stepper(String label, String tooltip, java.util.function.IntSupplier getter, java.util.function.IntConsumer setter, int step, int min, int max) {
			if (!visible()) return;
			int y = currentY();
			int btnW = 26;
			int labelW = COL_WIDTH - btnW * 2 - 4;

			Button valueDisplay = Button.builder(Component.literal(label + ": " + getter.getAsInt()), b -> {})
					.bounds(x + btnW + 2, y, labelW, 20).build();
			valueDisplay.active = false;

			Button minus = Button.builder(Component.literal("-" + step), b -> {
				setter.accept(clamp(getter.getAsInt() - step, min, max));
				valueDisplay.setMessage(Component.literal(label + ": " + getter.getAsInt()));
			}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			Button plus = Button.builder(Component.literal("+" + step), b -> {
				setter.accept(clamp(getter.getAsInt() + step, min, max));
				valueDisplay.setMessage(Component.literal(label + ": " + getter.getAsInt()));
			}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			addRenderableWidget(minus);
			addRenderableWidget(valueDisplay);
			addRenderableWidget(plus);
		}

		void slider(String label, String tooltip, DoubleSupplier getter, DoubleConsumer setter, double step, double min, double max) {
			if (!visible()) return;
			int y = currentY();
			int btnW = 26;
			int labelW = COL_WIDTH - btnW * 2 - 4;

			Button valueDisplay = Button.builder(Component.literal(label + ": " + formatNum(getter.getAsDouble())), b -> {})
					.bounds(x + btnW + 2, y, labelW, 20).build();
			valueDisplay.active = false;

			Button minus = Button.builder(Component.literal("-"), b -> {
				setter.accept(clampD(getter.getAsDouble() - step, min, max));
				valueDisplay.setMessage(Component.literal(label + ": " + formatNum(getter.getAsDouble())));
			}).bounds(x, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			Button plus = Button.builder(Component.literal("+"), b -> {
				setter.accept(clampD(getter.getAsDouble() + step, min, max));
				valueDisplay.setMessage(Component.literal(label + ": " + formatNum(getter.getAsDouble())));
			}).bounds(x + btnW + labelW + 4, y, btnW, 20).tooltip(Tooltip.create(Component.literal(tooltip))).build();

			addRenderableWidget(minus);
			addRenderableWidget(valueDisplay);
			addRenderableWidget(plus);
		}

		void button(String label, String tooltip, Runnable action) {
			if (!visible()) return;
			Button b = Button.builder(Component.literal(label), btn -> action.run())
					.bounds(x, currentY(), COL_WIDTH, 20)
					.tooltip(Tooltip.create(Component.literal(tooltip)))
					.build();
			addRenderableWidget(b);
		}

		void claimButton() {
			if (!visible()) return;
			Button b = Button.builder(Component.literal(claimLabel()), btn -> {
				toggleClaimCurrentChunk();
				btn.setMessage(Component.literal(claimLabel()));
			}).bounds(x, currentY(), COL_WIDTH, 20)
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
		int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
		int clamped = Mth.clamp(newScroll, 0, maxScroll);
		if (clamped == scroll) return;

		scroll = clamped;
		// Перебудовуємо віджди — так рядки, які стали видимими/невидимими,
		// створюються або зникають правильно.
		this.clearWidgets();
		this.init();
	}

	private void clampScroll() {
		int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
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
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, 0xFFFFFF);

		// Індикатор прокрутки — тонка смужка праворуч, щоб було видно, що
		// список довший за екран і є куди гортати.
		if (totalRows > VISIBLE_ROWS) {
			int trackH = VISIBLE_ROWS * ROW_HEIGHT;
			int thumbH = Math.max(20, trackH * VISIBLE_ROWS / totalRows);
			int maxScroll = totalRows - VISIBLE_ROWS;
			int thumbY = CONTENT_TOP + (maxScroll == 0 ? 0 : (trackH - thumbH) * scroll / maxScroll);
			int x = this.width - 6;
			graphics.fill(x, CONTENT_TOP, x + 3, CONTENT_TOP + trackH, 0x40FFFFFF);
			graphics.fill(x, thumbY, x + 3, thumbY + thumbH, 0xB0FFFFFF);
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

	private void clearMap() {
		ChunkColorCache.clearCurrentWorld();
		cfg.waypoints.clear();
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
		int cx = Mth.floor(mc.player.getX()) >> 4;
		int cz = Mth.floor(mc.player.getZ()) >> 4;
		boolean claimed = TerritoryManager.isClaimed(dim, cx, cz);
		return claimed ? "✗ Прибрати поточний чанк" : "✓ Додати поточний чанк";
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
		return Component.literal(label + ": " + (value ? "§aУВІМК" : "§cвимк"));
	}
}
