package com.volyacraft.volyahud.client.tooltip;

import com.volyacraft.volyahud.client.VolyaHudClient;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.ConditionalEffect;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentValueEffect;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Дописує в тултип зброї (наведення курсором в інвентарі) РОЗРАХОВАНУ
 * фактичну шкоду по HP — а не "сирий" бонус на кшталт "+5 damage" від
 * Sharpness V, який сам по собі нічого не каже про підсумкове число.
 *
 * Враховується:
 *  - базовий урон САМОГО предмета (атрибут-модифікатор attack_damage,
 *    той самий, що дає ванільний рядок "X Attack Damage" при F3+H);
 *  - поточні характеристики гравця (Сила/Слабкість тощо) — читаємо ЖИВЕ
 *    значення атрибута ATTACK_DAMAGE гравця й підміняємо в ньому внесок
 *    зброї в руці на внесок наведеного предмета (детальніше — в коментарі
 *    над {@link #calculateBaseDamage});
 *  - Sharpness/Smite/Bane of Arthropods ТА будь-які кастомні зачарування
 *    (зокрема ExcellentEnchants), що впливають на шкоду — див. коментар над
 *    {@link #applyEnchantDamageEffects};
 *  - критичний удар — ОКРЕМИМ числом, за ТОЧНОЮ формулою ванільного
 *    {@code Player#attack} (Minecraft 1.21.4): спершу множиться на 1.5
 *    базова шкода, а бонус від зачарувань додається вже ПІСЛЯ крита.
 *    Тому «(база + чари) × 1.5» — неправильно (див. коментар у
 *    {@link #computeLines}).
 *
 * ВАЖЛИВО: це теоретичне число проти цілі БЕЗ броні/ефектів опору — тултип
 * інвентаря фізично не може знати, у що вдягнена ціль, тому чесно про це
 * пишемо окремим рядком, а не видаємо це за 100%-но точне "скільки знімеш".
 */
public final class WeaponDamageTooltip {

	private WeaponDamageTooltip() {
	}

	public static void register() {
		ItemTooltipCallback.EVENT.register(WeaponDamageTooltip::onTooltip);
	}

	/**
	 * ОПТИМІЗАЦІЯ (буквально найдорожче місце в моді): тултип предмета
	 * викликається НЕ один раз при наведенні, а ЩОКАДРУ — ванільний
	 * {@code AbstractContainerScreen#renderTooltip} перебирає тултипи
	 * кожного кадру, поки курсор стоїть на слоті. Тому раніше весь
	 * розрахунок шкоди (перебір усіх зачарувань, створення Component'ів,
	 * форматування чисел) виконувався 60-360 разів на секунду для
	 * ІДЕНТИЧНОГО результату.
	 *
	 * <p>Тепер результат кешується: перераховуємо його лише тоді, коли
	 * реально змінилосьось із того, від чого він залежить — сам предмет
	 * (його зачарування/модифікатори), предмет у руці або атрибут сили
	 * гравця. Інакше просто додаємо вже готові рядки з кешу.
	 *
	 * <p>БАГ-ФІКС: раніше порівнювався ЛИШЕ посилання на стек
	 * ({@code cachedStack == stack}). Якщо гра давала новий екземпляр
	 * {@link ItemStack} з тими самими компонентами (а це звичайна справа
	 * для тултипів у деяких меню), кеш скидався щокадру; з іншого боку,
	 * зміна зачарування/предмета в руці при незмінному посиланні могла
	 * залишити СТАРЕ число. Тепер порівнюються компоненти стека, живий
	 * атрибут і предмет у руці.
	 */
	private static ItemStack cachedStack = ItemStack.EMPTY;
	private static ItemStack cachedMainhand = ItemStack.EMPTY;
	private static float cachedAttackDamage = Float.NaN;
	private static List<Component> cachedLines = List.of();
	/** Чи вже сталася помилка — щоб не писати її в лог щокадру. */
	private static boolean failed = false;

	private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> lines) {
		if (!HudConfig.INSTANCE.weaponDamageTooltip) return;

		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		Player player = mc.player;
		if (player == null) return;

		try {
			// Не зброя (немає жодного впливу на attack_damage у руці) —
			// тултип не чіпаємо.
			if (!hasMainhandAttackDamage(stack)) return;

			// Живе значення атрибута вже враховує Силу/Слабкість/ефекти, тож
			// воно — саме те, що змінює результат між кадрами.
			float liveAttack = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
			ItemStack mainhand = player.getMainHandItem();

			boolean upToDate = ItemStack.isSameItemSameComponents(cachedStack, stack)
					&& ItemStack.isSameItemSameComponents(cachedMainhand, mainhand)
					&& cachedAttackDamage == liveAttack;
			if (!upToDate) {
				cachedLines = computeLines(player, stack);
				cachedStack = stack.copy();
				// Копія, а не посилання: компоненти ItemStack можуть
				// змінюватися на місці, і тоді старий кеш більше не
				// відповідав би поточній зброї в руці.
				cachedMainhand = mainhand.copy();
				cachedAttackDamage = liveAttack;
			}
			lines.addAll(cachedLines);
		} catch (Throwable t) {
			// БАГ-ФІКС: раніше стек-трейс писався в лог ЩОКАДРУ (якщо щось
			// падало) — тобто в лог летіли сотні однакових помилок на
			// секунду. Тепер пишемо один раз.
			if (!failed) {
				failed = true;
				VolyaHudClient.LOGGER.error("VolyaHUD: не вдалось порахувати шкоду для тултипа предмета '{}'", stack, t);
			}
		}
	}

	/** Будує рядки тултипа (повний розрахунок). */
	private static List<Component> computeLines(Player player, ItemStack stack) {
		List<Component> lines = new ArrayList<>();

		float baseDamage = Math.max(0f, calculateBaseDamage(player, stack));

		// ТОЧНА формула ванільного Player#attack (Minecraft 1.21.4,
		// перевірено за байткодом):
		//
		//   base      = ATTACK_DAMAGE (зброя + Сила/Слабкість + інші ефекти)
		//   enchanted = зачарування_DAMAGE(base)
		//   extra     = enchanted - base          <- бонус зачарувань
		//   normal    = base + extra
		//   crit      = base * 1.5 + extra
		//
		// Тобто крит множить ЛИШЕ базову шкоду, а Sharpness/Smite та інші
		// DAMAGE-ефекти додаються вже після крита. Попередня версія робила
		// (base + extra) * 1.5 — це завищувало крит на 0.5 * extra.
		List<EnchantContribution> breakdown = new ArrayList<>();
		float enchanted = applyEnchantDamageEffects(stack, baseDamage, breakdown);
		float enchantBonus = enchanted - baseDamage;
		float totalDamage = Math.max(0f, enchanted);
		float critDamage = Math.max(0f, baseDamage * 1.5f + enchantBonus);

		lines.add(Component.literal(""));
		lines.add(Component.literal("\u00A76\u2694 VolyaHUD \u2014 \u0440\u0435\u0430\u043B\u044C\u043D\u0430 \u0448\u043A\u043E\u0434\u0430"));
		if (!breakdown.isEmpty()) {
			lines.add(Component.literal("\u00A77\u0411\u0430\u0437\u0430 \u0437\u0431\u0440\u043E\u0457: \u00A7f" + fmt(baseDamage)));
			// Розбивка по кожному зачарюванню, що реально вплинуло на шкоду —
			// показуємо назву (з рівнем, як у ваніли), внесок і, якщо чар не
			// ванільний (інший namespace — напр. ExcellentEnchants), позначку
			// "(мод)", щоб було видно, що саме звідти прийшов бонус.
			for (EnchantContribution entry : breakdown) {
				String sign = entry.contribution() >= 0f ? "+" : "";
				Component name = Enchantment.getFullname(entry.holder(), entry.level());
				// БАГ-ФІКС (не компілювалось): якщо оголосити змінну як Component,
				// компілятор бачить лише інтерфейс Component, у якому НЕМАЄ методу
				// append() (він є лише в MutableComponent, який реально повертає
				// Component.literal(...)). Через це подальший виклик line.append(...)
				// нижче не знаходив символ. MutableComponent — правильний тип тут.
				MutableComponent line = Component.literal("  \u00A77").append(name)
						.append(Component.literal(" \u00A7f" + sign + fmt(entry.contribution()) + "\u00A77"));
				if (!isVanilla(entry.holder())) {
					line = line.append(Component.literal(" (\u043C\u043E\u0434)"));
				}
				lines.add(line);
			}
		}
		lines.add(Component.literal("\u00A77\u0417\u0432\u0438\u0447\u0430\u0439\u043D\u0438\u0439 \u0443\u0434\u0430\u0440: \u00A7a"
				+ fmt(totalDamage) + " \u0425\u041F"));
		lines.add(Component.literal("\u00A77\u041A\u0440\u0438\u0442\u0438\u0447\u043D\u0438\u0439 \u0443\u0434\u0430\u0440: \u00A7c"
				+ fmt(critDamage) + " \u0425\u041F"));
		lines.add(Component.literal("\u00A78(\u043f\u043e\u0432\u043d\u0438\u0439 \u0437\u0430\u0440\u044f\u0434; \u0431\u0435\u0437 \u0443\u0440\u0430\u0445\u0443\u0432\u0430\u043d\u043d\u044f \u0431\u0440\u043e\u043d\u0456/\u043e\u043f\u043e\u0440\u0443 \u0446\u0456\u043b\u0456)"));

		return List.copyOf(lines);
	}

	/**
	 * «Яке було б значення ATTACK_DAMAGE, якби зараз у руці був саме цей
	 * наведений предмет» — з усіма поточними ефектами гравця (Сила,
	 * Слабкість, модифікатори броні/аксесуарів), але з ПІДМІНОЮ внеску
	 * поточної зброї на внесок наведеної.
	 *
	 * <p>БАГ-ФІКС: раніше враховувалися лише {@code ADD_VALUE}-модифікатори
	 * й віднімався «сирий» внесок поточної зброї. Якщо інший мод додавав
	 * {@code ADD_MULTIPLIED_BASE}/{@code ADD_MULTIPLIED_TOTAL} (або зброя
	 * мала кілька операцій), результат був неправильним.
	 *
	 * <p>Тепер значення збирається з нуля за ТОЧНИМ порядком ванільного
	 * {@code AttributeInstance#calculateValue}:
	 * <ol>
	 *   <li>базове значення + усі ADD_VALUE (крім модифікаторів поточної
	 *       зброї — їх замінюємо);</li>
	 *   <li>+ (значення після ADD_VALUE) × сума ADD_MULTIPLIED_BASE;</li>
	 *   <li>× (1 + amount) для кожного ADD_MULTIPLIED_TOTAL.</li>
	 * </ol>
	 * Модифікатори наведеного предмета додаються в ті самі три кошики, тому
	 * підсумок збігається з тим, що гра порахувала б із цим предметом у руці.
	 */
	private static float calculateBaseDamage(Player player, ItemStack hoveredStack) {
		AttributeInstance instance = player.getAttribute(Attributes.ATTACK_DAMAGE);
		if (instance == null) return 0f;

		// ID модифікаторів поточної зброї в руці — саме їх треба прибрати
		// перед підстановкою наведеного предмета.
		Set<ResourceLocation> replacedIds = new HashSet<>();
		ItemAttributeModifiers currentModifiers =
				player.getMainHandItem().get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (currentModifiers != null) {
			for (ItemAttributeModifiers.Entry entry : currentModifiers.modifiers()) {
				if (!entry.slot().test(EquipmentSlot.MAINHAND)) continue;
				if (entry.attribute().value() != Attributes.ATTACK_DAMAGE.value()) continue;
				replacedIds.add(entry.modifier().id());
			}
		}

		double base = instance.getBaseValue();
		double addValue = 0.0;
		double multipliedBase = 0.0;
		List<Double> multipliedTotal = new ArrayList<>(2);

		for (AttributeModifier modifier : instance.getModifiers()) {
			if (replacedIds.contains(modifier.id())) continue;
			switch (modifier.operation()) {
				case ADD_VALUE -> addValue += modifier.amount();
				case ADD_MULTIPLIED_BASE -> multipliedBase += modifier.amount();
				case ADD_MULTIPLIED_TOTAL -> multipliedTotal.add(modifier.amount());
			}
		}

		ItemAttributeModifiers hoveredModifiers =
				hoveredStack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (hoveredModifiers != null) {
			for (ItemAttributeModifiers.Entry entry : hoveredModifiers.modifiers()) {
				if (!entry.slot().test(EquipmentSlot.MAINHAND)) continue;
				if (entry.attribute().value() != Attributes.ATTACK_DAMAGE.value()) continue;
				AttributeModifier modifier = entry.modifier();
				switch (modifier.operation()) {
					case ADD_VALUE -> addValue += modifier.amount();
					case ADD_MULTIPLIED_BASE -> multipliedBase += modifier.amount();
					case ADD_MULTIPLIED_TOTAL -> multipliedTotal.add(modifier.amount());
				}
			}
		}

		double value = base + addValue;
		value += value * multipliedBase;
		for (double amount : multipliedTotal) {
			value *= 1.0 + amount;
		}
		return (float) value;
	}

	/**
	 * Чи впливає предмет на {@code ATTACK_DAMAGE} у слоті «основна рука».
	 * Перевіряються ВСІ операції, а не лише ADD_VALUE: інакше зброя з
	 * множним модифікатором вважалася б «не зброєю» й тултип не показувався.
	 */
	private static boolean hasMainhandAttackDamage(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return false;
		ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (mods == null) return false;
		for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
			if (!entry.slot().test(EquipmentSlot.MAINHAND)) continue;
			if (entry.attribute().value() != Attributes.ATTACK_DAMAGE.value()) continue;
			if (entry.modifier().amount() != 0.0) return true;
		}
		return false;
	}

	/** Внесок одного зачарування (з рівнем) у підсумкову шкоду — для розбивки в тултипі. */
	private record EnchantContribution(Holder<Enchantment> holder, int level, float contribution) {
	}

	/**
	 * Проганяє РЕАЛЬНИЙ ігровий ланцюжок ефектів зачарувань, що впливають на
	 * шкоду (компонент {@code EnchantmentEffectComponents.DAMAGE}), по ВСІХ
	 * чарах на предметі — той самий механізм, яким ваніла рахує Sharpness/
	 * Smite/Bane of Arthropods.
	 *
	 * Починаючи з Minecraft 1.21 усі зачарування (і ванільні, і додані
	 * дата-пак/мод-чари, зокрема ExcellentEnchants) — це не хардкод у коді
	 * гри, а дані: кожне зачарування несе список ефектів, і клієнт отримує
	 * ці дані через синхронізацію реєстру разом із самим зачаруванням. Тому:
	 *
	 *  - ExcellentEnchants НЕ потребує окремої Java-залежності чи спеціальної
	 *    підтримки в цьому моді — будь-яке його кастомне зачарування, що
	 *    впливає на шкоду через компонент DAMAGE (а це стандартний спосіб
	 *    зробити "+ шкода" зачарування в дата-driven системі), автоматично
	 *    підхоплюється цим кодом так само, як Sharpness;
	 *  - РІВЕНЬ кожного чару читається з предмета і передається у формулу
	 *    ефекту (EnchantmentValueEffect#process(level, random, value));
	 *  - застосовується РЕАЛЬНА формула автора зачарування (додавання,
	 *    множення, встановлення значення тощо), а не наближення
	 *    "рівень × якийсь коефіцієнт" — тому число в тултипі відповідає
	 *    фактичному розрахунку гри, а не сумі рівнів;
	 *  - ефекти ВСІХ зачарувань на предметі застосовуються послідовно один
	 *    за одним до одного й того самого значення шкоди (baseDamage →
	 *    ефект чару 1 → ефект чару 2 → ...) — так само, як у грі, тому
	 *    кілька кастомних чарів одночасно рахуються РАЗОМ, а не окремо;
	 *  - якщо на предметі взагалі немає зачарувань з ефектом DAMAGE (звичайний
	 *    меч без жодного стороннього мода), цикл нижче просто нічого не
	 *    знаходить і повертає baseDamage без жодних змін — розрахунок для
	 *    таких мечів не ламається.
	 *
	 * Умови (`requirements`) на ефектах (наприклад «лише по нежиті» у Smite
	 * чи «лише по членистоногим» у Bane of Arthropods) свідомо ігноруються:
	 * у тултипі інвентаря немає реальної цілі для перевірки, тож, як і в
	 * решті цього тултипа, рахуємо "найкращий випадок".
	 *
	 * @param breakdownOut якщо не null — сюди додається внесок кожного чару,
	 *                     що реально змінив значення (для показу в тултипі)
	 */
	private static float applyEnchantDamageEffects(ItemStack stack, float baseDamage, List<EnchantContribution> breakdownOut) {
		ItemEnchantments enchantments = stack.get(DataComponents.ENCHANTMENTS);
		if (enchantments == null || enchantments.isEmpty()) return baseDamage;

		// Власний генератор із ФІКСОВАНИМ сідом на кожен перерахунок:
		// детермінований результат у тултипі (число не «мерехтить» між
		// наведеннями для чарів з випадковою складовою). Раніше один
		// статичний RandomSource поступово «просувався» між викликами, тож
		// при кожній перебудові кешу результат міг відрізнятися.
		RandomSource random = RandomSource.create(0L);
		float total = baseDamage;

		for (Holder<Enchantment> holder : enchantments.keySet()) {
			int level = enchantments.getLevel(holder);
			if (level <= 0) continue;

			List<ConditionalEffect<EnchantmentValueEffect>> damageEffects =
					holder.value().getEffects(EnchantmentEffectComponents.DAMAGE);
			if (damageEffects.isEmpty()) continue;

			float before = total;
			for (ConditionalEffect<EnchantmentValueEffect> conditionalEffect : damageEffects) {
				total = conditionalEffect.effect().process(level, random, total);
			}

			if (breakdownOut != null && total != before) {
				breakdownOut.add(new EnchantContribution(holder, level, total - before));
			}
		}
		return total;
	}

	/** true, якщо зачарування зі стандартного простору імен "minecraft" (тобто не з мода/дата-паку типу ExcellentEnchants). */
	private static boolean isVanilla(Holder<Enchantment> holder) {
		return holder.unwrapKey()
				.map(ResourceKey::location)
				.map(loc -> "minecraft".equals(loc.getNamespace()))
				.orElse(true);
	}

	/**
	 * ОПТИМІЗАЦІЯ: String.format("%.1f", ...) на кожен виклик створює
	 * Formatter, заново парсить формат і боксить Float — а викликається цей
	 * метод кілька разів на кадр. Ручне форматування без алокацій.
	 */
	private static String fmt(float v) {
		long rounded = Math.round(v);
		if (Math.abs(v - rounded) < 0.05f) {
			return Long.toString(rounded);
		}
		// Одна цифра після коми — робимо вручну, щоб не тягнути String.format.
		//
		// БАГ-ФІКС: попередня версія неправильно округлювала ВІД'ЄМНІ числа
		// (наприклад -1.2 перетворювалось на "0.2"), що теоретично могло
		// з'явитися при Слабкості/негативних модифікаторах. Тепер знак
		// зберігається окремо, а модуль округлюється.
		long tenths = Math.round(v * 10.0);
		String sign = tenths < 0 ? "-" : "";
		long abs = Math.abs(tenths);
		long whole = abs / 10;
		long frac = abs % 10;
		return frac == 0 ? sign + whole : sign + whole + "." + frac;
	}
}
