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
		graphics.fill(x, y, x + SLOT, y + SLOT, 0x40000000);
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

	/**
	 * Малює накопичені за цей кадр мітки гравців (заповнюється в renderOthers,
	 * викликається останнім проходом HudRenderCallback). Порядок знизу вгору
	 * над головою: [нік (малює ваніла)] -> [HP] -> [броня] -> [зброя в руках].
	 *
	 * Раніше % міцності броні малювався ПІД рядом іконок і на 1px залазив на
	 * рядок HP над ним — тепер, як і у власній броні, % малюється прямо
	 * поверх іконки, тому рядки більше не накладаються одне на одне.
	 */
	public static void drawQueuedOverlays(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		Font font = mc.font;

		int rowWidth = ROW.length * (16 + 2) - 2;

		for (OverlayQueue.Entry entry : OverlayQueue.drain()) {
			if (entry.player().isRemoved() || !entry.player().isAlive()) continue;

			// entry.y() відповідає приблизно висоті, де ваніла малює нік гравця.
			int nickY = entry.y();
			int hpY = nickY - 12;
			int armorY = hpY - 20;

			if (cfg.healthTags) {
				float hp = Math.max(0f, entry.player().getHealth());
				float maxHp = Math.max(1f, entry.player().getMaxHealth());
				int hpColor = hp > maxHp * 0.5f ? 0x55FF55 : hp > maxHp * 0.2f ? 0xFFFF55 : 0xFF5555;
				String hpText = Math.round(hp) + "/" + Math.round(maxHp) + " \u2764";
				int hpW = font.width(hpText);
				HudStyle.panel(graphics, entry.x() - hpW / 2 - 2, hpY - 1, entry.x() + hpW / 2 + 2, hpY + 9);
				graphics.drawString(font, hpText, entry.x() - hpW / 2, hpY, hpColor, true);
			}

			// БАГ-ФІКС: раніше блок inventoryPeek був ВКЛАДЕНИЙ усередину
			// "if (cfg.otherPlayersArmorHud)", тож "Показ екіпіровки цілі"
			// у меню налаштувань насправді не працював сам по собі — предмети
			// в руках цілі показувались, лише якщо ЩЕ й броня була увімкнена.
			// Тепер це два повністю незалежні тумблери, як і показано в GUI.
			if (cfg.otherPlayersArmorHud) {
				int ix = entry.x() - rowWidth / 2;
				HudStyle.panel(graphics, ix - 2, armorY - 2, ix + rowWidth + 2, armorY + 18);
				for (EquipmentSlot slot : ROW) {
					ItemStack stack = entry.player().getItemBySlot(slot);
					if (!stack.isEmpty()) {
						graphics.renderItem(stack, ix, armorY);
						drawDurabilityOverlay(graphics, font, stack, ix, armorY, 16, 16);
					}
					ix += 18;
				}
			}

			if (cfg.inventoryPeek) {
				ItemStack mainHand = entry.player().getMainHandItem();
				ItemStack offHand = entry.player().getOffhandItem();
				int peekY = armorY - 22;
				int peekX = entry.x() - 9;
				if (!mainHand.isEmpty()) {
					HudStyle.panel(graphics, peekX - 20, peekY - 1, peekX - 2, peekY + 17);
					graphics.renderItem(mainHand, peekX - 18, peekY);
				}
				if (!offHand.isEmpty()) {
					HudStyle.panel(graphics, peekX + 2, peekY - 1, peekX + 20, peekY + 17);
					graphics.renderItem(offHand, peekX + 4, peekY);
				}
			}
		}
	}
}
