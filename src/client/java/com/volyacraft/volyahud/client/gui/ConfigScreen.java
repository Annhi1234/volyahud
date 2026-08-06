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

	private final Screen parent;
	private final HudConfig cfg = HudConfig.INSTANCE;

	public ConfigScreen(Screen parent) {
		super(Component.literal("VolyaHUD — налаштування"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int y = this.height / 2 - 110;
		int x = this.width / 2 - 100;

		addToggle(x, y, "Своя броня знизу", () -> cfg.ownArmorHud, v -> cfg.ownArmorHud = v);
		y += 22;
		addToggle(x, y, "Броня інших гравців", () -> cfg.otherPlayersArmorHud, v -> cfg.otherPlayersArmorHud = v);
		y += 22;
		addToggle(x, y, "HP-мітки над гравцями", () -> cfg.healthTags, v -> cfg.healthTags = v);
		y += 22;
		addToggle(x, y, "Прев'ю траєкторії перли", () -> cfg.pearlTrajectory, v -> cfg.pearlTrajectory = v);
		y += 22;
		addToggle(x, y, "Слід за кинутою перлою", () -> cfg.pearlTrail, v -> cfg.pearlTrail = v);
		y += 22;
		addToggle(x, y, "PvP-радар у хотбарі", () -> cfg.pvpRadar, v -> cfg.pvpRadar = v);
		y += 22;
		addToggle(x, y, "Бафи цілі (протилежний бік)", () -> cfg.targetBuffsHud, v -> cfg.targetBuffsHud = v);
		y += 22;
		addToggle(x, y, "Показ екіпіровки цілі", () -> cfg.inventoryPeek, v -> cfg.inventoryPeek = v);
		y += 22;
		addToggle(x, y, "Сповіщення про тотем", () -> cfg.totemAlert, v -> cfg.totemAlert = v);
		y += 30;

		this.addRenderableWidget(Button.builder(Component.literal("Готово"), b -> {
			cfg.save();
			Minecraft.getInstance().setScreen(parent);
		}).bounds(this.width / 2 - 75, y, 150, 20).build());
	}

	private void addToggle(int x, int y, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
		this.addRenderableWidget(Button.builder(
				toggleText(label, getter.getAsBoolean()),
				b -> {
					boolean newValue = !getter.getAsBoolean();
					setter.accept(newValue);
					b.setMessage(toggleText(label, newValue));
				}
		).bounds(x, y, 200, 20).build());
	}

	private Component toggleText(String label, boolean value) {
		return Component.literal(label + ": " + (value ? "УВІМК" : "вимк"));
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 130, 0xFFFFFF);
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
