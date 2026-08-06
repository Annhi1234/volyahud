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
	// [ШОЛОМ]     [ШТАНИ]
	// [НАГРУДНИК] [ПОНОЖІ]
	private static final EquipmentSlot[] GRID = {
			EquipmentSlot.HEAD, EquipmentSlot.LEGS,
			EquipmentSlot.CHEST, EquipmentSlot.FEET
	};

	// Порядок для рядка над іншими гравцями (голова -> ноги, зліва направо)
	private static final EquipmentSlot[] ROW = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	private static final int SLOT = 20;
	private static final int GAP = 2;

	// ---------------------------------------------------------------
	// Власна броня — панель 2x2 знизу-зліва (або знизу-справа), над хотбаром.
	// ---------------------------------------------------------------
	public static void renderOwn(GuiGraphics graphics, Minecraft mc) {
		Player player = mc.player;
		if (player == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		int panelW = SLOT * 2 + GAP;
		int panelH = SLOT * 2 + GAP;

		int screenW = graphics.guiWidth();
		int screenH = graphics.guiHeight();

		// 22px запасу знизу — щоб не залазити на ванільний хотбар,
		// ще 6px — щоб не впритул до нього.
		int x = cfg.armorOnRight ? (screenW - 8 - panelW) : 8;
		int y = screenH - 22 - 6 - panelH;

		graphics.fill(x - 3, y - 3, x + panelW + 3, y + panelH + 3, 0x60000000);
		graphics.fill(x - 3, y - 3, x + panelW + 3, y - 2, 0x40FFFFFF); // тонка світла лінія зверху панелі

		drawSlot(graphics, mc, player.getItemBySlot(GRID[0]), x, y);
		drawSlot(graphics, mc, player.getItemBySlot(GRID[1]), x + SLOT + GAP, y);
		drawSlot(graphics, mc, player.getItemBySlot(GRID[2]), x, y + SLOT + GAP);
		drawSlot(graphics, mc, player.getItemBySlot(GRID[3]), x + SLOT + GAP, y + SLOT + GAP);
	}

	private static void drawSlot(GuiGraphics graphics, Minecraft mc, ItemStack stack, int x, int y) {
		graphics.fill(x, y, x + SLOT, y + SLOT, 0x40000000);
		if (stack.isEmpty()) return;

		int ix = x + (SLOT - 16) / 2;
		int iy = y + (SLOT - 16) / 2;
		graphics.renderItem(stack, ix, iy);
		graphics.renderItemDecorations(mc.font, stack, ix, iy);

		if (stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int cur = max - stack.getDamageValue();
			int percent = Math.round(100f * cur / max);
			int color = percent > 50 ? 0x55FF55 : percent > 20 ? 0xFFFF55 : 0xFF5555;
			String text = percent + "%";
			int tx = x + SLOT - mc.font.width(text);
			graphics.drawString(mc.font, text, tx, y + SLOT + 1, color, true);
		}
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
			if (clip.w <= 0.01f) continue; // позаду камери

			int screenW = mc.getWindow().getGuiScaledWidth();
			int screenH = mc.getWindow().getGuiScaledHeight();
			int sx = (int) ((clip.x / clip.w * 0.5 + 0.5) * screenW);
			int sy = (int) ((1 - (clip.y / clip.w * 0.5 + 0.5)) * screenH);

			if (sx < -60 || sx > screenW + 60 || sy < -60 || sy > screenH + 60) continue;

			OverlayQueue.queue(sx, sy, other, dist);
		}
	}

	/**
	 * Малює накопичені за цей кадр мітки гравців (заповнюється в renderOthers,
	 * викликається останнім проходом HudRenderCallback). Порядок знизу вгору
	 * над головою: [нік (малює ваніла)] -> [HP] -> [броня].
	 */
	public static void drawQueuedOverlays(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		Font font = mc.font;

		int rowWidth = ROW.length * (16 + 2) - 2;

		for (OverlayQueue.Entry entry : OverlayQueue.drain()) {
			// entry.y() відповідає приблизно висоті, де ваніла малює нік гравця.
			int nickY = entry.y();
			int hpY = nickY - 12;
			int armorY = hpY - 18;

			if (cfg.healthTags) {
				float hp = entry.player().getHealth();
				float maxHp = entry.player().getMaxHealth();
				int hpColor = hp > maxHp * 0.5f ? 0x55FF55 : hp > maxHp * 0.2f ? 0xFFFF55 : 0xFF5555;
				String hpText = Math.round(hp) + "/" + Math.round(maxHp) + " \u2764";
				graphics.drawString(font, hpText, entry.x() - font.width(hpText) / 2, hpY, hpColor, true);
			}

			if (cfg.otherPlayersArmorHud) {
				int ix = entry.x() - rowWidth / 2;
				for (EquipmentSlot slot : ROW) {
					ItemStack stack = entry.player().getItemBySlot(slot);
					if (!stack.isEmpty()) {
						graphics.fill(ix - 1, armorY - 1, ix + 17, armorY + 17, 0x50000000);
						graphics.renderItem(stack, ix, armorY);
						if (stack.isDamageableItem()) {
							int max = stack.getMaxDamage();
							int cur = max - stack.getDamageValue();
							int percent = Math.round(100f * cur / max);
							int color = percent > 50 ? 0x55FF55 : percent > 20 ? 0xFFFF55 : 0xFF5555;
							graphics.drawString(font, percent + "%", ix, armorY + 17, color, true);
						}
					}
					ix += 18;
				}

				if (cfg.inventoryPeek) {
					ItemStack mainHand = entry.player().getMainHandItem();
					ItemStack offHand = entry.player().getOffhandItem();
					int peekY = armorY - 20;
					int peekX = entry.x() - 9;
					if (!mainHand.isEmpty()) {
						graphics.renderItem(mainHand, peekX - 10, peekY);
					}
					if (!offHand.isEmpty()) {
						graphics.renderItem(offHand, peekX + 10, peekY);
					}
				}
			}
		}
	}
}
