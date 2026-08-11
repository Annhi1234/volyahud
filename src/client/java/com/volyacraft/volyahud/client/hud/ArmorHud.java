package com.volyacraft.volyahud.client.hud;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public class ArmorHud {

	// Порядок промальовування власної броні у сітці 2x2:
	// [ШОЛОМ]     [НАГРУДНИК]
	// [ШТАНИ]     [ЧОБОТИ]
	// (БАГ-ФІКС: раніше було HEAD,LEGS / CHEST,FEET — верхній ряд показував
	// шолом поруч зі штаньми, а нижній нагрудник поруч з чоботами, що не
	// відповідає природному порядку зверху вниз по тілу.)
	private static final EquipmentSlot[] GRID = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST,
			EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	// Порядок для рядка (і своєї лінійної панелі, і мітки над іншими гравцями):
	// голова -> груди -> ноги -> ступні, зліва направо.
	private static final EquipmentSlot[] ROW = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private static final int SLOT = 20;
	private static final int GAP = 2;

	// ---------------------------------------------------------------
	// Власна броня — панель у нижньому куті, над хотбаром (або притиснута
	// до самого низу, якщо увімкнено armorFlushBottom).
	// ---------------------------------------------------------------
	public static void renderOwn(GuiGraphics graphics, Minecraft mc) {
		Player player = mc.player;
		if (player == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		boolean line = cfg.armorLayout == HudConfig.ArmorLayout.LINE;
		int panelW = line ? (SLOT * 4 + GAP * 3) : (SLOT * 2 + GAP);
		int panelH = line ? SLOT : (SLOT * 2 + GAP);

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		boolean onRight = cfg.armorCorner == HudConfig.ScreenCorner.BOTTOM_RIGHT
				|| cfg.armorCorner == HudConfig.ScreenCorner.TOP_RIGHT;
		boolean onTop = cfg.armorCorner == HudConfig.ScreenCorner.TOP_LEFT
				|| cfg.armorCorner == HudConfig.ScreenCorner.TOP_RIGHT;

		int x = onRight ? (screenW - 8 - panelW) : 8;
		int y;
		if (onTop) {
			y = 8;
		} else if (cfg.armorFlushBottom) {
			// впритул до самого низу екрана (може лягати поверх країв хотбару)
			y = screenH - 4 - panelH;
		} else {
			// 22px запасу знизу — щоб не залазити на ванільний хотбар,
			// ще 8px — щоб не впритул до нього.
			y = screenH - 22 - 8 - panelH;
		}

		x += cfg.armorOffsetX;
		y += cfg.armorOffsetY;

		// не даємо панелі повністю зникнути за межами екрана від ручного зміщення
		x = clamp(x, -panelW + 8, screenW - 8);
		y = clamp(y, 0, screenH - 8);

		HudStyle.panel(graphics, x - 3, y - 3, x + panelW + 3, y + panelH + 3);

		if (line) {
			for (int i = 0; i < ROW.length; i++) {
				drawSlot(graphics, mc, player.getItemBySlot(ROW[i]), x + i * (SLOT + GAP), y);
			}
		} else {
			drawSlot(graphics, mc, player.getItemBySlot(GRID[0]), x, y);
			drawSlot(graphics, mc, player.getItemBySlot(GRID[1]), x + SLOT + GAP, y);
			drawSlot(graphics, mc, player.getItemBySlot(GRID[2]), x, y + SLOT + GAP);
			drawSlot(graphics, mc, player.getItemBySlot(GRID[3]), x + SLOT + GAP, y + SLOT + GAP);
		}
	}

	private static int clamp(int v, int min, int max) {
		return Math.max(min, Math.min(max, v));
	}

	private static void drawSlot(GuiGraphics graphics, Minecraft mc, ItemStack stack, int x, int y) {
		HudStyle.mcSlot(graphics, x, y, SLOT);
		if (stack.isEmpty()) return;

		int ix = x + (SLOT - 16) / 2;
		int iy = y + (SLOT - 16) / 2;
		graphics.renderItem(stack, ix, iy);
		graphics.renderItemDecorations(mc.font, stack, ix, iy);

		drawDurabilityOverlay(graphics, mc.font, stack, x, y, SLOT, SLOT);
	}

	/**
	 * Малює % міцності предмета ПОВЕРХ самого слота (у нижньому правому куті
	 * іконки), а не під ним. Раніше текст малювався нижче слота і в сітці
	 * 2x2 його миттєво перекривав наступний ряд броні знизу (GAP між рядами
	 * лише 2px) — саме тому верхній відсоток був майже не видно. Малювання
	 * всередині меж слота повністю прибирає цей конфлікт незалежно від
	 * розкладки (сітка/лінія) і звільняє місце під панеллю.
	 */
	private static void drawDurabilityOverlay(GuiGraphics graphics, Font font, ItemStack stack, int x, int y, int w, int h) {
		if (!stack.isDamageableItem()) return;
		int max = stack.getMaxDamage();
		int cur = max - stack.getDamageValue();
		int percent = Math.round(100f * cur / max);
		int color = percent > 50 ? 0x55FF55 : percent > 20 ? 0xFFFF55 : 0xFF5555;
		String text = percent + "%";

		// зменшуємо шрифт, тільки якщо текст не влазить у ширину слота
		int textW = font.width(text);
		float fontScale = textW > w - 2 ? (w - 2f) / textW : 1f;

		graphics.pose().pushPose();
		int tx = x + w - Math.round(textW * fontScale) - 1;
		int ty = y + h - 9;
		graphics.pose().translate(tx, ty, 0);
		graphics.pose().scale(fontScale, fontScale, 1f);
		graphics.fill(-1, -1, Math.round(textW * fontScale) + 1, 9, 0xA0000000);
		graphics.drawString(font, text, 0, 0, color, true);
		graphics.pose().popPose();
	}

	// ---------------------------------------------------------------
	// Броня / HP інших гравців — над ніком, у світі (3D -> 2D проекція).
	// Використовуємо інтерпольовану позицію гравця (partial tick), інакше
	// мітка "відстає" від моделі й здається, що вона літає під час руху.
	// ---------------------------------------------------------------
	public static void renderOthers(WorldRenderContext context, Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		Matrix4f viewProj = new Matrix4f(context.projectionMatrix())
				.mul(context.matrixStack().last().pose());

		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);

		for (Player other : mc.level.players()) {
			if (other == mc.player) continue;
			if (other.isSpectator()) continue;
			double dist = other.distanceTo(mc.player);
			if (dist > cfg.otherPlayersRenderDistance) continue;
			if (other.isInvisibleTo(mc.player)) continue;

			Vec3 camPos = context.camera().getPosition();
			// Інтерпольована позиція — та сама, яку використовує рендер моделі
			// і ванільна табличка з ніком, тому мітка більше не "відстає"/"літає".
			Vec3 interpolated = other.getPosition(partialTick);
			double wx = interpolated.x - camPos.x;
			double wy = interpolated.y + other.getBbHeight() + 0.5 - camPos.y;
			double wz = interpolated.z - camPos.z;

			Vector4f clip = new Vector4f((float) wx, (float) wy, (float) wz, 1.0f);
			clip.mul(viewProj);
			// БАГ-ФІКС ("броня і хп літають по екрану"): поруч із площиною камери
			// (типово впритул до когось у ближньому бою — саме там, де цей HUD
			// найпотрібніший) clip.w наближається до нуля, і clip.x/clip.w та
			// clip.y/clip.w вибухають до астрономічних значень. Далі це множилось
			// на screenW/screenH і кастилось у int — при переповненні int
			// координата "переверталась" у випадкове число всередині екрана,
			// і мітка на кадр стрибала будь-куди замість того, щоб просто
			// зникнути. Поріг 0.01f був занадто малим, щоб це відсікти.
			if (clip.w <= 0.3f) continue; // задто близько до камери — не проєктуємо

			double ndcX = clip.x / clip.w;
			double ndcY = clip.y / clip.w;
			// Додатковий запобіжник: якщо NDC все одно вийшли за адекватні межі
			// (NaN/Infinity або багатократно за краєм екрана), не малюємо мітку
			// замість ризикувати переповненням int при переведенні в пікселі.
			if (!Double.isFinite(ndcX) || !Double.isFinite(ndcY) || Math.abs(ndcX) > 25 || Math.abs(ndcY) > 25) continue;

			int screenW = mc.getWindow().getGuiScaledWidth();
			int screenH = mc.getWindow().getGuiScaledHeight();
			int sx = (int) ((ndcX * 0.5 + 0.5) * screenW);
			int sy = (int) ((1 - (ndcY * 0.5 + 0.5)) * screenH);

			if (sx < -60 || sx > screenW + 60 || sy < -60 || sy > screenH + 60) continue;

			OverlayQueue.queue(sx, sy, other, dist);
		}
	}

	// Розмір комірки екіпіровки над головою іншого гравця. Навмисно менший за
	// власну панель (SLOT=20), щоб увесь блок лишався компактним і не
	// заступав огляд у PvP; 18px — це той самий розмір, що й у ванільному
	// інвентарі (16px іконка + по 1px рамки з кожного боку).
	private static final int OTHER_SLOT = 18;
	private static final int OTHER_ROW_W = ROW.length * OTHER_SLOT;
	// Висота рядка HP (текст + внутрішні відступи фону).
	private static final int HP_ROW_H = 10;
	// Абсолютний мінімальний проміжок (px) між сусідніми рядками стосу.
	// Незалежно від того, який зазор виставлено в налаштуваннях (otherHpEquipmentGap)
	// і які ручні offsetY застосовані, рендер нижче ніколи не дає фактичній
	// відстані впасти менше за це значення — тому рядки НЕ можуть накластися
	// одне на одного, хай там які значення хтось виставить у меню.
	private static final int MIN_ROW_GAP = 1;

	/**
	 * Малює накопичені за цей кадр мітки гравців (заповнюється в renderOthers,
	 * викликається останнім проходом HudRenderCallback).
	 *
	 * Блок над головою противника має ЧІТКИЙ, завжди однаковий порядок рядків
	 * знизу вгору (кожен наступний рядок відраховується від розміру
	 * попереднього, тому порядок не може "поплисти", а рядки — накластися):
	 *   1) [нік]           — малює сама ваніла;
	 *   2) [HP]            — поточне здоров'я (+ дистанція), одразу над ніком;
	 *   3) [екіпіровка]    — компактний ряд слотів шолом/нагрудник/штани/чоботи;
	 *   4) [зброя в руках] — предмет в основній та другорядній руці.
	 * Усі рядки центруються по тій самій X-координаті (entry.x()), яку
	 * renderOthers() щокадру перераховує з інтерпольованої позиції гравця —
	 * тому блок лишається строго над головою і не "відстає"/не гуляє вліво-
	 * вправо під час руху, стрибків чи бою: він рухається синхронно з моделлю.
	 *
	 * Позиція HP (otherHpOffsetY) і позиція екіпіровки (otherEquipmentOffsetY)
	 * та зазор між ними (otherHpEquipmentGap) — окремо налаштовувані з меню.
	 * HP-зсув рухає HP-рядок і все, що стосом стоїть над ним (екіпіровку,
	 * зброю), — тому вони завжди їдуть разом, як один блок, замість того щоб
	 * "розсипатись". Зсув екіпіровки рухає лише блок екіпіровка+зброя
	 * відносно HP. Обидва зсуви в кінці проганяються через MIN_ROW_GAP, тож
	 * хай яке значення виставить користувач — рядки фізично не можуть
	 * накластися одне на одного.
	 */
	public static void drawQueuedOverlays(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		Font font = mc.font;
		int gap = Math.max(MIN_ROW_GAP, cfg.otherHpEquipmentGap);

		for (OverlayQueue.Entry entry : OverlayQueue.drain()) {
			if (entry.player().isRemoved() || !entry.player().isAlive()) continue;

			int centerX = entry.x();
			// entry.y() відповідає приблизно висоті, де ваніла малює нік гравця.
			int nickY = entry.y();

			int hpY = nickY - gap - HP_ROW_H + cfg.otherHpOffsetY;

			// Бажана позиція екіпіровки — заданий зазор над HP плюс ручний
			// зсув користувача, але з гарантованим мінімумом MIN_ROW_GAP між
			// нижнім краєм екіпіровки і верхнім краєм HP, навіть якщо offsetY
			// зсуває рядки назустріч один одному.
			int armorY = Math.min(
					hpY - gap - OTHER_SLOT + cfg.otherEquipmentOffsetY,
					hpY - MIN_ROW_GAP - OTHER_SLOT
			);
			int peekY = Math.min(armorY - gap - OTHER_SLOT, armorY - MIN_ROW_GAP - OTHER_SLOT);

			if (cfg.healthTags) {
				float hp = Math.max(0f, entry.player().getHealth());
				float maxHp = Math.max(1f, entry.player().getMaxHealth());
				int hpColor = hp > maxHp * 0.5f ? 0x55FF55 : hp > maxHp * 0.2f ? 0xFFFF55 : 0xFF5555;
				String hpText = Math.round(hp) + "/" + Math.round(maxHp) + " \u2764";
				// Відстань до гравця поруч з HP — корисно в PvP, щоб на око
				// оцінити, чи встигнеш добігти/докинути перлу.
				if (cfg.healthTagDistance) {
					hpText += "  " + Math.round(entry.distance()) + "m";
				}
				int hpW = font.width(hpText);
				HudStyle.panel(graphics, centerX - hpW / 2 - 2, hpY - 1, centerX + hpW / 2 + 2, hpY + 9);
				graphics.drawString(font, hpText, centerX - hpW / 2, hpY, hpColor, true);
			}

			// Ряд екіпіровки: компактний "інвентарний" рядок з 4 слотів у
			// стилі ваніли (HudStyle.mcSlot — сіра утоплена комірка з рамкою
			// й фаскою, як у справжньому GUI), у фіксованому порядку зліва
			// направо: шолом -> нагрудник -> штани -> чоботи (ROW). Слоти
			// домальовуються навіть якщо предмет відсутній (порожня комірка),
			// тому позиція кожного типу броні завжди однакова і не "стрибає"
			// залежно від того, що зараз надіте на противника.
			if (cfg.otherPlayersArmorHud) {
				int ix = centerX - OTHER_ROW_W / 2;
				HudStyle.panel(graphics, ix - 2, armorY - 2, ix + OTHER_ROW_W + 2, armorY + OTHER_SLOT + 2);
				for (EquipmentSlot slot : ROW) {
					ItemStack stack = entry.player().getItemBySlot(slot);
					HudStyle.mcSlot(graphics, ix, armorY, OTHER_SLOT);
					if (!stack.isEmpty()) {
						int iconX = ix + (OTHER_SLOT - 16) / 2;
						int iconY = armorY + (OTHER_SLOT - 16) / 2;
						graphics.renderItem(stack, iconX, iconY);
						drawDurabilityOverlay(graphics, font, stack, ix, armorY, OTHER_SLOT, OTHER_SLOT);
					}
					ix += OTHER_SLOT;
				}
			}

			// Ряд зброї в руках: до 2 слотів (основна + другорядна рука) тим
			// самим ванільним стилем, що й екіпіровка. Ряд завжди над
			// екіпіровкою (peekY < armorY), тому навіть при увімкнених обох
			// опціях блоки ніколи не перекриваються.
			if (cfg.inventoryPeek) {
				ItemStack mainHand = entry.player().getMainHandItem();
				ItemStack offHand = entry.player().getOffhandItem();
				boolean hasMain = !mainHand.isEmpty();
				boolean hasOff = !offHand.isEmpty();
				if (hasMain || hasOff) {
					int cellGap = 2;
					int cells = (hasMain ? 1 : 0) + (hasOff ? 1 : 0);
					int totalW = cells * OTHER_SLOT + (cells - 1) * cellGap;
					int px = centerX - totalW / 2;
					HudStyle.panel(graphics, px - 2, peekY - 2, px + totalW + 2, peekY + OTHER_SLOT + 2);
					if (hasMain) {
						HudStyle.mcSlot(graphics, px, peekY, OTHER_SLOT);
						int iconX = px + (OTHER_SLOT - 16) / 2;
						int iconY = peekY + (OTHER_SLOT - 16) / 2;
						graphics.renderItem(mainHand, iconX, iconY);
						drawDurabilityOverlay(graphics, font, mainHand, px, peekY, OTHER_SLOT, OTHER_SLOT);
						px += OTHER_SLOT + cellGap;
					}
					if (hasOff) {
						HudStyle.mcSlot(graphics, px, peekY, OTHER_SLOT);
						int iconX = px + (OTHER_SLOT - 16) / 2;
						int iconY = peekY + (OTHER_SLOT - 16) / 2;
						graphics.renderItem(offHand, iconX, iconY);
						drawDurabilityOverlay(graphics, font, offHand, px, peekY, OTHER_SLOT, OTHER_SLOT);
					}
				}
			}
		}
	}
}
