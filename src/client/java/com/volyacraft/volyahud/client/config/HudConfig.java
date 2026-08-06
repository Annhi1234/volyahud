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

/**
 * Прості налаштування HUD. Кожне поле — окремий тумблер, який можна
 * увімкнути/вимкнути з меню (ConfigScreen) або залишити як є в json.
 */
public class HudConfig {

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("volyahud.json");

	public static HudConfig INSTANCE = load();

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

	// --- налаштовувані параметри ---
	public double otherPlayersRenderDistance = 40.0;
	public double pvpRadarActivationDistance = 24.0;

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
