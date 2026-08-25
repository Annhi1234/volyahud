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
 * <p>
 * <b>ФІКС (важливий, за скаргою): "заміняє блок під тобою" / лаги під час
 * руху / світло іноді пропадає.</b> Справжня причина всіх трьох симптомів
 * була в тому, як саме ставився фейковий блок:
 * <ol>
 *   <li><b>Лаги.</b> Старий код ставив блок із прапорцем
 *   {@code UPDATE_IMMEDIATE}, який форсить СИНХРОННУ перебудову рендер-секції
 *   просто в момент виклику. Позиція гравця змінюється майже щотика під час
 *   руху → рендер-секція перебудовувалась щотика примусово, замість того щоб
 *   потрапити в звичайну чергу перебудови рендера (яка й так обробляється
 *   щокадру, але без блокування основного потоку). Це й відчувалось як
 *   "пролагування" саме під час ходьби з факелом у руці. Тепер
 *   використовується лише {@code UPDATE_CLIENTS} — світло рахується так само
 *   правильно, але рендер-секція перебудовується звичайним, не форсованим
 *   шляхом.</li>
 *   <li><b>Заміна блоку під гравцем.</b> Стара перевірка пропускала лише
 *   блоки з непорожньою формою колізії — тобто дозволяла "з'їсти" будь-що
 *   без колізії: килимок, шар снігу, редстоун-пил, рейки, високу траву,
 *   розсаду, банер на підлозі (і його NBT — назву, візерунок — при цьому
 *   ВЗАГАЛІ не зберігав, бо {@code savedState} не містить блок-ентіті-даних).
 *   Тепер функція ставить світло ЛИШЕ якщо позиція — справжнє повітря
 *   ({@code BlockState#isAir()}) і без блок-ентіті. У всіх інших випадках
 *   (стоїш у траві/воді/на килимку) вона просто нічого не займає в цьому
 *   тику — це виглядає як "світла нема, поки стоїш саме в цій клітинці", але
 *   натомість НІКОЛИ нічого не ламає й не губить.</li>
 *   <li><b>Світло іноді пропадає.</b> "Тиха" перевірка раніше йшла раз на
 *   секунду (20 тіків), тож якщо PvP-сервер перевідправляв чанк і затирав
 *   наш блок, до секунди гравець стояв у темряві. Інтервал зменшено до 5
 *   тіків (чверть секунди) — сама перевірка дешева (лише читання стану
 *   блока), і платимо запис у світ лише тоді, коли він справді потрібен.
 *   Заразом ця ж перевірка тепер поважає реальні зміни світу: якщо в цій
 *   позиції за цей час з'явився СПРАВЖНІЙ (не наш) блок, ми більше не
 *   намагаємось туди щось писати — це вже не наша територія.</li>
 * </ol>
 */
public final class DynamicLight {

	private DynamicLight() {
	}

	/** Прапор, яким ставиться/знімається наше фейкове світло: розіслати
	 * оновлення клієнтським системам (рендер/світло), АЛЕ без
	 * {@code UPDATE_IMMEDIATE} — перебудова рендер-секції піде звичайним,
	 * не форсованим шляхом (див. великий коментар до класу, пункт 1: це і є
	 * фікс лагів під час руху). Свідомо БЕЗ UPDATE_NEIGHBORS — світловий
	 * блок не має сповіщати сусідні блоки (редстоун-пил, грядки і т.д.) про
	 * "зміну", бо це виключно наш клієнтський візуальний трюк, а не справжня
	 * зміна світу. */
	private static final int SET_FLAGS = Block.UPDATE_CLIENTS;

	private static final int REASSERT_INTERVAL_TICKS = 5; // 4 рази на секунду — швидке самозцілення після ресинку чанка

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
			// Нічого не змінилось — кілька разів на секунду просто
			// "підтверджуємо" стан, на випадок якщо сервер тим часом
			// перевідправив цей чанк і затер наш блок (типово для PvP-сервера
			// з частими оновленнями чанків) — це і є фікс "світло іноді
			// пропадає". Це лише читання BlockState, запис у світ
			// відбувається, тільки якщо він справді потрібен.
			reassertTimer++;
			if (reassertTimer >= REASSERT_INTERVAL_TICKS) {
				reassertTimer = 0;
				if (mc.level.isLoaded(appliedPos)) {
					BlockState current = mc.level.getBlockState(appliedPos);
					if (isOurLight(current, appliedLevel)) {
						// Усе гаразд, наше світло на місці — нічого робити не треба.
					} else if (current.isAir()) {
						// Сервер перевідправив чанк (там знову звичайне повітря,
						// нашого блока нема) — тихо ставимо світло назад.
						mc.level.setBlock(appliedPos, lightState(appliedLevel), SET_FLAGS);
					} else {
						// У позиції з'явився СПРАВЖНІЙ (не наш) блок — гравець
						// чи сервер щось туди поставили. Це вже не наша територія:
						// перестаємо стежити за нею, щоб нізащо її не зачепити.
						appliedPos = null;
						savedState = null;
						appliedLevel = 0;
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
		// ГОЛОВНИЙ ЗАХИСТ (фікс "заміняє блок під тобою"): ставимо фейкове
		// світло ЛИШЕ якщо в позиції гравця справжнє порожнє повітря. Раніше
		// тут перевірялась тільки форма колізії, через що функція спокійно
		// "з'їдала" килимки, сніговий шар, редстоун-пил, рейки, траву, банери
		// на підлозі тощо (і губила їхні дані назавжди, бо збережений
		// BlockState не містить NBT блок-ентіті). Якщо гравець зараз стоїть
		// не в порожньому повітрі — просто не займаємо цей блок цього тика;
		// краще на мить лишитись без фейкового світла, ніж зіпсувати щось
		// у світі.
		if (!existing.isAir() || mc.level.getBlockEntity(targetPos) != null) {
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
