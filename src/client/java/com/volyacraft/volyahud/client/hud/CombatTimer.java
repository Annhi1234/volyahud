package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
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
	private static DamageSource lastDamageSource;
	private static int lastHurtTime;

	private CombatTimer() {
	}

	/** Скидає стан при зміні світу/сервера, щоб старий таймер не "переїхав" у нову гру. */
	public static void reset() {
		prevHealth = -1f;
		combatEndsAtMs = 0L;
		nowMs = 0L;
		lastDamageSource = null;
		lastHurtTime = 0;
	}

	public static void tick(Minecraft mc) {
		// Один знімок часу на тік — щоб не читати годинник у кожному з
		// викликів isActive()/secondsLeft(), які йдуть щокадру.
		nowMs = System.currentTimeMillis();

		Player player = mc.player;
		if (player == null) {
			prevHealth = -1f;
			lastDamageSource = null;
			lastHurtTime = 0;
			return;
		}

		// Раніше КОЖНА втрата HP (падіння, вогонь, отрута, голод) вмикала
		// "бойовий тег". Тепер дивимось на джерело шкоди: таймер продовжує
		// лише атака іншого гравця (удар, стріла, зілля тощо).
		//
		// hurtTime збільшується на початку нової шкоди й спадає щотік; це
		// ловить і повністю заблоковані/поглинуті удари, коли health не
		// змінюється. lastDamageSource додатково ловить випадки, коли
		// hurtTime вже встиг спасти до нашого тіку.
		DamageSource source = player.getLastDamageSource();
		boolean newHurt = player.hurtTime > lastHurtTime;
		boolean sourceChanged = source != null && source != lastDamageSource;
		if ((newHurt || sourceChanged) && isPlayerDamage(source, player)) {
			enterCombat();
		}
		lastHurtTime = player.hurtTime;
		lastDamageSource = source;
		prevHealth = player.getHealth();
	}

	private static boolean isPlayerDamage(DamageSource source, Player self) {
		if (source == null) return false;
		Entity attacker = source.getEntity();
		if (attacker == null) attacker = source.getDirectEntity();
		return attacker instanceof Player other && other != self;
	}

	/** Викликається з MultiPlayerGameModeMixin, коли ми атакуємо саме іншого гравця (не моба). */
	public static void onAttackPlayer() {
		enterCombat();
	}

	private static void enterCombat() {
		int seconds = HudConfig.INSTANCE.combatTimerSeconds;
		nowMs = System.currentTimeMillis();
		combatEndsAtMs = nowMs + seconds * 1000L;
	}

	/**
	 * ОПТИМІЗАЦІЯ: обидва методи нижче викликаються з FpsHud ЩОКАДРУ, і
	 * кожен читав системний годинник. Тепер час рахується один раз на тік
	 * ({@link #tick}), а зовні ми просто порівнюємо зкешоване значення.
	 */
	private static long nowMs = 0L;

	public static boolean isActive() {
		return nowMs < combatEndsAtMs;
	}

	/** Округлення вгору: щоб таймер показував "1с" аж до самого моменту завершення, а не "0с" завчасно. */
	public static int secondsLeft() {
		long left = combatEndsAtMs - nowMs;
		return (int) Math.max(0, (left + 999) / 1000);
	}
}
