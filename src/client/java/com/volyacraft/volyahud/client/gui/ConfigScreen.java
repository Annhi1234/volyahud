package com.volyacraft.volyahud.client.gui;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

public class ConfigScreen extends Screen {

	private static final int ROW_HEIGHT = 22;
	private static final int COL_WIDTH = 180;
	private static final int COL_GAP = 10;

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;

	public ConfigScreen(Screen parent) {
		super(Component.literal("VolyaHUD — налаштування"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Minecraft mc = Minecraft.getInstance();

		int totalWidth = COL_WIDTH * 3 + COL_GAP * 2;
		int col1X = this.width / 2 - totalWidth / 2;
		int col2X = col1X + COL_WIDTH + COL_GAP;
		int col3X = col2X + COL_WIDTH + COL_GAP;
		int startY = this.height / 2 - 100;
		int bottomMost = startY;

		// --- колонка 1: броня ---
		int y = startY;
		addToggle(col1X, y, "Своя броня знизу", () -> cfg.ownArmorHud, v -> cfg.ownArmorHud = v);
		y += ROW_HEIGHT;
		addToggle(col1X, y, "Броня справа (замість зліва)", () -> cfg.armorOnRight, v -> cfg.armorOnRight = v);
		y += ROW_HEIGHT;
		addToggle(col1X, y, "Броня інших гравців", () -> cfg.otherPlayersArmorHud, v -> cfg.otherPlayersArmorHud = v);
		y += ROW_HEIGHT;
		addToggle(col1X, y, "HP-мітки над гравцями", () -> cfg.healthTags, v -> cfg.healthTags = v);
		y += ROW_HEIGHT;
		addToggle(col1X, y, "Показ екіпіровки цілі", () -> cfg.inventoryPeek, v -> cfg.inventoryPeek = v);
		y += ROW_HEIGHT;
		bottomMost = Math.max(bottomMost, y);

		// --- колонка 2: кидки / радар / бафи ---
		y = startY;
		addToggle(col2X, y, "Прев'ю траєкторії кидка", () -> cfg.pearlTrajectory, v -> cfg.pearlTrajectory = v);
		y += ROW_HEIGHT;
		addToggle(col2X, y, "Слід перли/зілля/досвіду", () -> cfg.pearlTrail, v -> cfg.pearlTrail = v);
		y += ROW_HEIGHT;
		addToggle(col2X, y, "Індикатор гравців над хотбаром", () -> cfg.pvpRadar, v -> cfg.pvpRadar = v);
		y += ROW_HEIGHT;
		addToggle(col2X, y, "Бафи цілі (протилежний бік)", () -> cfg.targetBuffsHud, v -> cfg.targetBuffsHud = v);
		y += ROW_HEIGHT;
		addToggle(col2X, y, "Сповіщення про тотем", () -> cfg.totemAlert, v -> cfg.totemAlert = v);
		y += ROW_HEIGHT;
		bottomMost = Math.max(bottomMost, y);

		// --- колонка 3: інфо-панель та чат ---
		y = startY;
		addToggle(col3X, y, "Лічильник FPS", () -> cfg.showFps, v -> cfg.showFps = v);
		y += ROW_HEIGHT;
		addToggle(col3X, y, "Показ координат (XYZ)", () -> cfg.showCoords, v -> cfg.showCoords = v);
		y += ROW_HEIGHT;
		addToggle(col3X, y, "Показ координат чанка", () -> cfg.showChunkCoords, v -> cfg.showChunkCoords = v);
		y += ROW_HEIGHT + 6;
		// Ширина чату — реальна ванільна опція гри, щоб не робити окремий
		// кастомний рендер чату (і не конфліктувати з ним): просто даємо
		// швидкий доступ до повзунка ширини прямо з нашого меню.
		this.addRenderableWidget(mc.options.chatWidth().createButton(mc.options, col3X, y, COL_WIDTH));
		y += ROW_HEIGHT;
		bottomMost = Math.max(bottomMost, y);

		int doneY = bottomMost + 14;
		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			cfg.save();
			Minecraft.getInstance().setScreen(parent);
		}).bounds(this.width / 2 - 75, doneY, 150, 20).build());
	}

	private void addToggle(int x, int y, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		this.addRenderableWidget(Button.builder(
				toggleText(label, getter.getAsBoolean()),
				b -> {
					boolean newValue = !getter.getAsBoolean();
					setter.accept(newValue);
					b.setMessage(toggleText(label, newValue));
				}
		).bounds(x, y, COL_WIDTH, 20).build());
	}

	private Component toggleText(String label, boolean value) {
		return Component.literal(label + ": " + (value ? "УВІМК" : "вимк"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 122, 0xFFFFFF);
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
