package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

/**
 * НОВА ФУНКЦІЯ: "бойовий тег" — скільки ще секунд триває стан "щойно був у
 * бою". Корисно на серверах, де вихід із гри під час бою карається (типова
 * PvP-механіка) — щоб було видно на власні очі, а не гадати чи вже "чисто".
 *
 * Це евристика, як і DamageMeter/TotemAlert: офіційного пакета "ти в бою"
 * ванільний протокол не має, тому бій визначається клієнтом самостійно за
 * двома подіями:
 *  1) власне здоров'я щойно зменшилось (нас ударили/потрапили) — ловиться
 *     порівнянням здоров'я гравця між тіками;
 *  2) ми самі щойно атакували іншого ГРАВЦЯ (не моба) — той самий клік
 *     атаки, що вже перехоплює MultiPlayerGameModeMixin для DamageMeter.
 * Кожна з цих подій продовжує таймер на combatTimerSeconds наперед.
 */
public final class CombatTimer {

	private static float prevHealth = -1f;
	private static long combatEndsAtMs = 0L;

	private CombatTimer() {
	}

	public static void tick(Minecraft mc) {
		Player player = mc.player;
		if (player == null) {
			prevHealth = -1f;
			return;
		}
		float hp = player.getHealth();
		// prevHealth < 0 лише одразу після заходу в гру/респауну — тоді ще
		// нема з чим порівнювати, і порівнювати не варто (інакше респаун із
		// повним HP після смерті міг би хибно зарахуватись як "втрата HP").
		if (prevHealth >= 0f && hp < prevHealth - 0.01f) {
			enterCombat();
		}
		prevHealth = hp;
	}

	/** Викликається з MultiPlayerGameModeMixin, коли ми атакуємо саме іншого гравця (не моба). */
	public static void onAttackPlayer() {
		enterCombat();
	}

	private static void enterCombat() {
		int seconds = HudConfig.INSTANCE.combatTimerSeconds;
		combatEndsAtMs = System.currentTimeMillis() + seconds * 1000L;
	}

	public static boolean isActive() {
		return System.currentTimeMillis() < combatEndsAtMs;
	}

	/** Округлення вгору: щоб таймер показував "1с" аж до самого моменту завершення, а не "0с" завчасно. */
	public static int secondsLeft() {
		long left = combatEndsAtMs - System.currentTimeMillis();
		return (int) Math.max(0, (left + 999) / 1000);
	}
}
