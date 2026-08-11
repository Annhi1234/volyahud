package com.volyacraft.volyahud.client.tooltip;

import com.volyacraft.volyahud.client.VolyaHudClient;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EquipmentSlotGroup;
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
import java.util.List;

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
 *  - критичний удар показується ОКРЕМИМ числом (×1.5 від підсумкового урону
 *    удару, як і в ваніли).
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

	private static void onTooltip(ItemStack stack, Item.TooltipContext context, TooltipFlag type, List<Component> lines) {
		if (!HudConfig.INSTANCE.weaponDamageTooltip) return;

		net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
		Player player = mc.player;
		if (player == null) return;

		try {
			float hoveredWeaponDamage = mainhandAttackDamage(stack);
			if (hoveredWeaponDamage <= 0f) return; // не зброя (немає власного attack_damage) — тултип не чіпаємо

			float baseDamage = calculateBaseDamage(player, stack, hoveredWeaponDamage);

			List<EnchantContribution> breakdown = new ArrayList<>();
			float totalDamageRaw = applyEnchantDamageEffects(stack, baseDamage, breakdown);
			float totalDamage = Math.max(0f, totalDamageRaw);
			float critDamage = totalDamage * 1.5f;

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
			lines.add(Component.literal("\u00A78(\u0431\u0435\u0437 \u0443\u0440\u0430\u0445\u0443\u0432\u0430\u043D\u043D\u044F \u0431\u0440\u043E\u043D\u0456/\u043E\u043F\u043E\u0440\u0443 \u0446\u0456\u043B\u0456)"));
		} catch (Throwable t) {
			VolyaHudClient.LOGGER.error("VolyaHUD: не вдалось порахувати шкоду для тултипа предмета '{}'", stack, t);
		}
	}

	/**
	 * "Що показувало б F3+H / атрибут ATTACK_DAMAGE, якби зараз у руці був
	 * саме цей наведений предмет" — беремо ЖИВЕ значення атрибута гравця
	 * (у ньому вже враховані Сила/Слабкість/інші активні модифікатори й
	 * поточна зброя в руці), знімаємо внесок ПОТОЧНОЇ зброї в руці і додаємо
	 * внесок НАВЕДЕНОГО предмета замість неї. Решта (базове значення 1.0
	 * "кулаком" + Сила/Слабкість) лишається такою, як є в живого гравця —
	 * саме так враховуються "характеристики гравця".
	 *
	 * Спрощення: припускаємо, що всі відповідні модифікатори — адитивні
	 * (ADD_VALUE), як і є у ваніли для attack_damage (сама зброя, Сила,
	 * Слабкість). Якщо якийсь інший мод додасть МНОЖНИЙ модифікатор на цей
	 * атрибут, підміна порахує трохи неточно — прийнятний компроміс для
	 * тултипа "орієнтовної" шкоди.
	 */
	private static float calculateBaseDamage(Player player, ItemStack hoveredStack, float hoveredWeaponDamage) {
		float liveAttack = (float) player.getAttributeValue(Attributes.ATTACK_DAMAGE);
		float currentWeaponDamage = mainhandAttackDamage(player.getMainHandItem());
		return liveAttack - currentWeaponDamage + hoveredWeaponDamage;
	}

	/** Сума ADD_VALUE-модифікаторів attack_damage предмета для слоту "основна рука" (0, якщо предмет не зброя). */
	private static float mainhandAttackDamage(ItemStack stack) {
		if (stack == null || stack.isEmpty()) return 0f;
		ItemAttributeModifiers mods = stack.get(DataComponents.ATTRIBUTE_MODIFIERS);
		if (mods == null) return 0f;

		float sum = 0f;
		for (ItemAttributeModifiers.Entry entry : mods.modifiers()) {
			if (entry.slot() != EquipmentSlotGroup.MAINHAND) continue;
			if (entry.attribute().value() != Attributes.ATTACK_DAMAGE.value()) continue;
			if (entry.modifier().operation() == AttributeModifier.Operation.ADD_VALUE) {
				sum += (float) entry.modifier().amount();
			}
		}
		return sum;
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

		// Фіксований сід — детермінований результат у тултипі (щоб число не
		// "мерехтіло" між наведеннями курсора для чарів з випадковою складовою).
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

	private static String fmt(float v) {
		return Math.abs(v - Math.round(v)) < 0.05f ? String.valueOf(Math.round(v)) : String.format("%.1f", v);
	}
}
