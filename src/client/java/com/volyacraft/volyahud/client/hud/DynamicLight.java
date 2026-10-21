package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.util.Mth;

/**
 * СВІТЛО ВІД ПРЕДМЕТА В РУЦІ — без жодного "невидимого факела" у світі.
 *
 * <p><b>ГОЛОВНЕ (те, що скаржилися):</b> раніше ця фіча ставила в клієнтський
 * світ справжній блок {@code minecraft:light} під ноги гравця. Блок займав
 * клітинку (→ "невидимий факел", який заважав будувати й засаджувати), а коли
 * в клітинці з'являвся справжній блок, старий код забував про фейк і блок
 * залишався лежати назавжди.
 *
 * <p>Тепер світ <b>не торкається світу взагалі</b>. Ми підміняємо відповідь
 * рушія блочного світла через {@code LightEngineMixin}: коли рушій питає, який
 * блок стоїть у нашій позиції, ми відповідаємо "це {@code minecraft:light} з
 * рівнем N". Для рушія це повноцінне джерело світла — він сам рахує
 * розповсюдження, просвіти та затемнення. Блок у світі не створюється, тож
 * нічого не можна зламати, заблокувати або залишити по собі.
 *
 * <p>Світло ставиться в позицію, де стоїть гравець, і тримається, поки в руці
 * є предмет, що світить (факел, ліхтар, лампа, багаття, гловстоун, тотем
 * безсмертя).
 */
public final class DynamicLight {

	private DynamicLight() {
	}

	/**
	 * Фейкове джерело світла: позиція + рівень + готовий стан блоку, який ми
	 * віддаємо рушію світла. Відкритий доступ — mixin читає його напряму.
	 */
	public record FakeLight(BlockPos pos, int level, BlockState state) {
	}

	/** Поточне фейкове джерело (або null, якщо світла зараз немає). */
	private static FakeLight current = null;

	/**
	 * Кількість тіків між "підтвердженнями" світла. Сервер може пересилати
	 * секції чанка зі світлом (наприклад, коли хтось поставив факел) — тоді
	 * наше світло в секції зникає. Раз на пару секунд ми нагадуємо рушію про
	 * наше джерело, і світло повертається.
	 */
	private static final int REASSERT_INTERVAL_TICKS = 40;

	private static int reassertTimer = 0;

	/** Публічний доступ для mixin'а {@code LightEngineMixin}. */
	public static FakeLight currentFakeLight() {
		return current;
	}

	/** Викликається раз на клієнтський тік з головного циклу VolyaHudClient. */
	public static void tick(Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		LocalPlayer player = mc.player;

		if (!cfg.heldItemDynamicLight || player == null || mc.level == null) {
			clear(mc);
			return;
		}

		int natural = Math.max(
				lightLevelFor(player.getMainHandItem()),
				lightLevelFor(player.getOffhandItem())
		);
		if (natural <= 0) {
			clear(mc);
			return;
		}

		// Рівень можна обмежити в налаштуваннях (наприклад, щоб світло не
		// "пробивало" напівпрозорий простір так далеко, як справжній факел).
		int level = Mth.clamp(cfg.heldItemLightLevel, 1, 15);
		level = Math.min(level, natural);

		BlockPos target = player.blockPosition();
		FakeLight fake = current;

		if (fake != null && fake.pos().equals(target) && fake.level() == level) {
			// Нічого не змінилось — раз на пару секунд просто підтверджуємо
			// джерело (дешева операція: лише читання стану блоку в рушію).
			if (++reassertTimer >= REASSERT_INTERVAL_TICKS) {
				reassertTimer = 0;
				lightEngine(mc).checkBlock(target);
			}
			return;
		}

		// Позиція або рівень змінились. Спершу знімаємо старе джерело, потім
		// ставимо нове. Старе знімається ПОТІМ, як current уже звільнено —
		// інакше рушій побачив би на старій позиції "світящий" блок і не
		// погасив світло там.
		if (fake != null) {
			current = null;
			lightEngine(mc).checkBlock(fake.pos());
		}

		current = new FakeLight(target, level, lightState(level));
		reassertTimer = 0;
		lightEngine(mc).checkBlock(target);
	}

	/** Прибрати наше фейкове світло (погасити джерело в рушію світла). */
	private static void clear(Minecraft mc) {
		FakeLight fake = current;
		if (fake == null) return;

		current = null;
		reassertTimer = 0;

		// Ставимо null ПЕРЕД цим викликом, щоб рушій світла побачив на тій
		// позиції справжній блок (а не наш фейк) — тільки тоді він зрозуміє,
		// що джерело згасло, і погасить світло.
		if (mc.level != null) {
			lightEngine(mc).checkBlock(fake.pos());
		}
	}

	private static LevelLightEngine lightEngine(Minecraft mc) {
		return mc.level.getLightEngine();
	}

	private static BlockState lightState(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, Mth.clamp(level, 0, 15));
	}

	/**
	 * Скидає внутрішній стан БЕЗ спроби погасити джерело — викликається при
	 * заході в новий світ/на новий сервер, коли попередній
	 * {@link net.minecraft.client.multiplayer.ClientLevel} вже не існує і
	 * чіпати його безглуздо.
	 */
	public static void reset() {
		current = null;
		reassertTimer = 0;
	}

	/**
	 * Рівень світла, який мав би давати предмет, якби його поставили як блок.
	 * Для звичайних blockitem-ів (факел, ліхтар, лампа, багаття, гловстоун
	 * тощо) беремо світло прямо з дефолтного стану самого блока — це
	 * автоматично враховує "лампи" з інших модів/датапаків, а не тільки
	 * жорстко зашитий список ванільних предметів.
	 * <p>
	 * Тотем безсмертя — єдиний виняток: він не blockitem, тому для нього
	 * рівень заданий вручну (як у факела).
	 */
	private static int lightLevelFor(ItemStack stack) {
		if (stack.isEmpty()) return 0;
		Item item = stack.getItem();

		if (item == Items.TOTEM_OF_UNDYING) {
			return 14; // тотем не можна поставити — світить як факел
		}
		if (item instanceof BlockItem blockItem) {
			return blockItem.getBlock().defaultBlockState().getLightEmission();
		}
		return 0;
	}
}
