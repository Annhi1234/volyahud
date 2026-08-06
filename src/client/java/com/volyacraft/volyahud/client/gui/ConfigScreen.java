package com.volyacraft.volyahud.client.gui;

import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.config.TerritoryManager;
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
		INFO("Інфо-панель"),
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
		int totalTabsWidth = Tab.values().length * 108;
		int tabX = this.width / 2 - totalTabsWidth / 2;
		for (Tab tab : Tab.values()) {
			boolean selected = tab == currentTab;
			Button tabButton = Button.builder(
					Component.literal((selected ? "\u25B8 " : "") + tab.title),
					b -> switchTab(tab)
			).bounds(tabX, 26, 104, TAB_HEIGHT).build();
			tabButton.active = !selected;
			this.addRenderableWidget(tabButton);
			tabX += 108;
		}

		int col1X = this.width / 2 - (COL_WIDTH * 2 + COL_GAP) / 2;
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
						"У якому нижньому куті малювати свою броню.",
						() -> cfg.armorCorner.name(), () -> cycleBottomCorner(), this::cornerLabel);
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
			}
			case PLAYERS -> {
				y1 = addToggle(col1X, y1, "HP-мітки над гравцями",
						"Показувати здоров'я над головою інших гравців.",
						() -> cfg.healthTags, v -> cfg.healthTags = v);
				y1 = addSlider(col1X, y1, "Дистанція показу",
						"Максимальна відстань (у блоках), на якій показуються мітки гравців.",
						() -> cfg.otherPlayersRenderDistance, v -> cfg.otherPlayersRenderDistance = v, 8, 4, 128);

				y2 = addToggle(col2X, y2, "Бафи цілі",
						"Показувати активні ефекти гравця, на якого дивишся, у лівому верхньому куті.",
						() -> cfg.targetBuffsHud, v -> cfg.targetBuffsHud = v);
				y2 = addToggle(col2X, y2, "Сповіщення про тотем",
						"Текстове сповіщення, коли поруч хтось використав тотем безсмертя.",
						() -> cfg.totemAlert, v -> cfg.totemAlert = v);
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
			case INFO -> {
				y1 = addToggle(col1X, y1, "Лічильник FPS",
						"Показувати поточну кількість кадрів за секунду.",
						() -> cfg.showFps, v -> cfg.showFps = v);
				y1 = addCycle(col1X, y1, "Кут екрана", "Де саме малювати панель FPS/координат.",
						() -> cfg.infoPanelCorner.name(), this::cycleAnyCorner, this::cornerLabel);

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
			case MISC -> {
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

	private void cycleBottomCorner() {
		cfg.armorCorner = cfg.armorCorner == HudConfig.ScreenCorner.BOTTOM_LEFT
				? HudConfig.ScreenCorner.BOTTOM_RIGHT : HudConfig.ScreenCorner.BOTTOM_LEFT;
	}

	private void cycleAnyCorner() {
		HudConfig.ScreenCorner[] all = HudConfig.ScreenCorner.values();
		int next = (cfg.infoPanelCorner.ordinal() + 1) % all.length;
		cfg.infoPanelCorner = all[next];
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
