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

	private static final EquipmentSlot[] SLOTS = {
			EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
	};

	// ---------------------------------------------------------------
	// Власна броня — панель знизу-зліва (як у вигляді ТАБ-плагіна, але наша)
	// ---------------------------------------------------------------
	public static void renderOwn(GuiGraphics graphics, Minecraft mc) {
		Player player = mc.player;
		if (player == null) return;

		int x = 8;
		int y = graphics.guiHeight() - 8 - (18 * SLOTS.length);

		for (EquipmentSlot slot : SLOTS) {
			ItemStack stack = player.getItemBySlot(slot);
			if (!stack.isEmpty()) {
				drawSlot(graphics, mc, stack, x, y);
			}
			y += 18;
		}
	}

	private static void drawSlot(GuiGraphics graphics, Minecraft mc, ItemStack stack, int x, int y) {
		graphics.renderItem(stack, x, y);
		graphics.renderItemDecorations(mc.font, stack, x, y);

		if (stack.isDamageableItem()) {
			int max = stack.getMaxDamage();
			int cur = max - stack.getDamageValue();
			int percent = Math.round(100f * cur / max);
			int color = percent > 50 ? 0x55FF55 : percent > 20 ? 0xFFFF55 : 0xFF5555;
			graphics.drawString(mc.font, percent + "%", x + 19, y + 4, color, true);
		}
	}

	// ---------------------------------------------------------------
	// Броня / HP інших гравців — над головою, у світі (3D -> 2D проекція)
	// ---------------------------------------------------------------
	public static void renderOthers(WorldRenderContext context, Minecraft mc) {
		if (mc.level == null || mc.player == null) return;
		HudConfig cfg = HudConfig.INSTANCE;

		Matrix4f viewProj = new Matrix4f(context.projectionMatrix())
				.mul(context.matrixStack().last().pose());

		for (Player other : mc.level.players()) {
			if (other == mc.player) continue;
			double dist = other.distanceTo(mc.player);
			if (dist > cfg.otherPlayersRenderDistance) continue;

			Vec3 camPos = context.camera().getPosition();
			double wx = other.getX() - camPos.x;
			double wy = other.getY() + other.getBbHeight() + 0.5 - camPos.y;
			double wz = other.getZ() - camPos.z;

			Vector4f clip = new Vector4f((float) wx, (float) wy, (float) wz, 1.0f);
			clip.mul(viewProj);
			if (clip.w <= 0) continue; // позаду камери

			int screenW = mc.getWindow().getGuiScaledWidth();
			int screenH = mc.getWindow().getGuiScaledHeight();
			int sx = (int) ((clip.x / clip.w * 0.5 + 0.5) * screenW);
			int sy = (int) ((1 - (clip.y / clip.w * 0.5 + 0.5)) * screenH);

			if (sx < -50 || sx > screenW + 50 || sy < -50 || sy > screenH + 50) continue;

			// Малюємо вже у 2D просторі HUD, тож фактичний виклик переносимо
			// в чергу, яку розбирає HudRenderCallback — тут спрощено малюємо напряму
			// через graphics з context (context.consumers() недоступний для 2D тексту,
			// тому позначки малюються окремим HUD-проходом нижче).
			OverlayQueue.queue(sx, sy, other, dist);
		}
	}

	/**
	 * Малює накопичені за цей кадр мітки гравців (заповнюється в renderOthers,
	 * викликається останнім проходом HudRenderCallback).
	 */
	public static void drawQueuedOverlays(GuiGraphics graphics, Minecraft mc) {
		HudConfig cfg = HudConfig.INSTANCE;
		Font font = mc.font;
		for (OverlayQueue.Entry entry : OverlayQueue.drain()) {
			int x = entry.x() - 30;
			int y = entry.y();

			if (cfg.healthTags) {
				float hp = entry.player().getHealth();
				float maxHp = entry.player().getMaxHealth();
				int hpColor = hp > maxHp * 0.5f ? 0x55FF55 : hp > maxHp * 0.2f ? 0xFFFF55 : 0xFF5555;
				String hpText = Math.round(hp) + "/" + Math.round(maxHp) + " \u2764";
				graphics.drawString(font, hpText, entry.x() - font.width(hpText) / 2, y - 12, hpColor, true);
			}

			if (cfg.otherPlayersArmorHud) {
				int ix = x;
				for (EquipmentSlot slot : SLOTS) {
					ItemStack stack = entry.player().getItemBySlot(slot);
					if (!stack.isEmpty()) {
						graphics.renderItem(stack, ix, y);
						if (stack.isDamageableItem()) {
							int max = stack.getMaxDamage();
							int cur = max - stack.getDamageValue();
							int percent = Math.round(100f * cur / max);
							int color = percent > 50 ? 0x55FF55 : percent > 20 ? 0xFFFF55 : 0xFF5555;
							graphics.drawString(font, percent + "%", ix, y + 17, color, true);
						}
					}
					ix += 16;
				}

				if (cfg.inventoryPeek) {
					ItemStack mainHand = entry.player().getMainHandItem();
					ItemStack offHand = entry.player().getOffhandItem();
					if (!mainHand.isEmpty()) {
						graphics.renderItem(mainHand, ix + 4, y);
						ix += 16;
					}
					if (!offHand.isEmpty()) {
						graphics.renderItem(offHand, ix + 4, y);
					}
				}
			}
		}
	}
}
