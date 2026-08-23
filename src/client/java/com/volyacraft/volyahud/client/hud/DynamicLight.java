package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * НОВА ФУНКЦІЯ: динамічне світло від предмета в руці ("як в моді Dynamic
 * Lights"). Поки гравець тримає в основній АБО другорядній руці предмет, що
 * як блок випромінює світло (факел, соул-факел, ліхтар, лампа, кістяне
 * багаття тощо), клієнт ставить у позицію гравця невидимий ванільний блок
 * {@code minecraft:light} з потрібним рівнем яскравості — той самий блок,
 * яким користуються карти для декоративного світла без видимої моделі.
 * Це НЕ "фейковий рендер": це справжнє ванільне освітлення, пораховане
 * рушієм світла гри, тому воно коректно проходить крізь кут стін/рельєфу
 * так само, як від реально поставленого факела.
 * <p>
 * Тотем безсмертя поставити як блок не можна (це не blockitem), тому для
 * нього зроблено окремий виняток — він світить рівнем факела (14).
 * <p>
 * Це суто клієнтська зміна: ніяких пакетів на сервер не йде, і жодного
 * пермʼюту в світі не відбувається. Коли предмет прибрали з рук, вимкнули
 * тумблер у налаштуваннях чи гравець вийшов із гри — блок, що лежав під
 * фейковим світлом раніше, ставиться на місце один в один.
 */
public final class DynamicLight {

	private DynamicLight() {
	}

	/** Ті самі прапори, якими ванільна гра сама виставляє блоки: розіслати
	 * оновлення клієнтським системам (рендер/світло) і перерахувати одразу,
	 * без затримки в наступний кадр. Свідомо БЕЗ UPDATE_NEIGHBORS — світловий
	 * блок не має сповіщати сусідні блоки (редстоун-пил, грядки і т.д.) про
	 * "зміну", бо це виключно наш клієнтський візуальний трюк, а не справжня
	 * зміна світу. */
	private static final int SET_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_IMMEDIATE;
	/** Той самий набір, але без примусового негайного перебудовування рендер-чанка
	 * — використовується для "тихого" підтвердження раз на секунду (нижче),
	 * щоб не смикати рендер щотика, якщо позиція й так не змінилась. */
	private static final int REASSERT_FLAGS = Block.UPDATE_CLIENTS;

	private static final int REASSERT_INTERVAL_TICKS = 20; // раз на секунду

	private static BlockPos appliedPos = null;
	private static BlockState savedState = null; // що лежало в appliedPos ДО того, як ми туди поставили світло
	private static int appliedLevel = 0;
	private static int reassertTimer = 0;

	/** Викликається раз на клієнтський тік з головного циклу VolyaHudClient. */
	public static void tick(Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		LocalPlayer player = mc.player;

		if (!cfg.heldItemDynamicLight || player == null || mc.level == null) {
			restore(mc);
			return;
		}

		int level = Math.max(
				lightLevelFor(player.getMainHandItem()),
				lightLevelFor(player.getOffhandItem())
		);

		if (level <= 0) {
			restore(mc);
			return;
		}

		BlockPos targetPos = player.blockPosition();

		boolean samePlace = appliedPos != null && appliedPos.equals(targetPos) && appliedLevel == level;
		if (samePlace) {
			// Нічого не змінилось — раз на секунду просто "підтверджуємо" стан
			// без примусового негайного перебудовування рендера, на випадок
			// якщо сервер тим часом перевідправив цей чанк і затер наш блок
			// (типово для PvP-сервера з частими оновленнями чанків).
			reassertTimer++;
			if (reassertTimer >= REASSERT_INTERVAL_TICKS) {
				reassertTimer = 0;
				if (mc.level.isLoaded(appliedPos)) {
					BlockState current = mc.level.getBlockState(appliedPos);
					if (!isOurLight(current, appliedLevel)) {
						mc.level.setBlock(appliedPos, lightState(appliedLevel), REASSERT_FLAGS);
					}
				}
			}
			return;
		}

		// Позиція або рівень світла змінились — спершу повертаємо старе місце
		// на місце, потім (якщо можна) ставимо нове.
		restore(mc);

		if (!mc.level.isLoaded(targetPos)) {
			return; // чанк ще не прогружений — почекаємо наступного тика
		}

		BlockState existing = mc.level.getBlockState(targetPos);
		// Захисна перевірка: гравець фізично не може стояти всередині суцільного
		// блока, але про всяк випадок (човен, інший мод, дивний десинк) не чіпаємо
		// позицію, якщо там дійсно щось суцільне — щоб нізащо не "з'їсти" блок.
		if (!existing.getCollisionShape(mc.level, targetPos).isEmpty()) {
			return;
		}

		savedState = existing;
		appliedPos = targetPos;
		appliedLevel = level;
		reassertTimer = 0;
		mc.level.setBlock(targetPos, lightState(level), SET_FLAGS);
	}

	/** Прибрати наше фейкове світло й повернути на місце те, що там було раніше. */
	private static void restore(Minecraft mc) {
		if (appliedPos == null) return;
		try {
			if (mc.level != null && mc.level.isLoaded(appliedPos)) {
				BlockState current = mc.level.getBlockState(appliedPos);
				// Ставимо збережений стан назад, тільки якщо там і досі наш
				// світловий блок (якщо там уже щось інше — сервер чи гравець
				// самі змінили блок, і затирати це своїм "відкатом" не треба).
				if (isOurLight(current, appliedLevel)) {
					mc.level.setBlock(appliedPos, savedState, SET_FLAGS);
				}
			}
		} finally {
			appliedPos = null;
			savedState = null;
			appliedLevel = 0;
			reassertTimer = 0;
		}
	}

	private static boolean isOurLight(BlockState state, int level) {
		return state.getBlock() == Blocks.LIGHT && state.getValue(LightBlock.LEVEL) == level;
	}

	private static BlockState lightState(int level) {
		return Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, net.minecraft.util.Mth.clamp(level, 0, 15));
	}

	/**
	 * Скидає внутрішній стан БЕЗ спроби повернути блок на місце — викликається
	 * при заході в новий світ/на новий сервер, коли попередній {@link net.minecraft.client.multiplayer.ClientLevel}
	 * вже не існує і чіпати його безглуздо (і небезпечно).
	 */
	public static void reset() {
		appliedPos = null;
		savedState = null;
		appliedLevel = 0;
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
	 * рівень заданий вручну (як у факела), як і просив автор фічі.
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
