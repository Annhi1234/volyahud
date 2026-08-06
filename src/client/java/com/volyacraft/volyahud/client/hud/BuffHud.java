package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.texture.MobEffectTextureManager;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public class BuffHud {

	/**
	 * Ванільні власні ефекти малюються у правому верхньому куті.
	 * Тут малюємо ефекти цілі (на кого дивишся) у лівому верхньому куті —
	 * тобто з протилежного боку екрана.
	 */
	public static void render(GuiGraphics graphics, Minecraft mc) {
		if (mc.player == null || !(mc.hitResult instanceof EntityHitResult entityHit)) return;
		if (!(entityHit.getEntity() instanceof LivingEntity target)) return;
		if (mc.hitResult.getType() != HitResult.Type.ENTITY) return;

		var effects = target.getActiveEffects();
		if (effects.isEmpty()) return;

		int x = 8;
		int y = 8;
		MobEffectTextureManager textureManager = mc.getMobEffectTextures();

		graphics.drawString(mc.font, target.getName(), x, y, 0xFFFFFF, true);
		y += 12;

		for (MobEffectInstance effect : effects) {
			var sprite = textureManager.get(effect.getEffect());
			graphics.blitSprite(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED,
					sprite.location(), x, y, 18, 18);
			String amplifier = effect.getAmplifier() > 0 ? " " + toRoman(effect.getAmplifier() + 1) : "";
			String duration = formatDuration(effect.getDuration());
			graphics.drawString(mc.font, duration + amplifier, x + 22, y + 5, 0xFFFFFF, true);
			y += 20;
		}
	}

	private static String formatDuration(int ticks) {
		int seconds = ticks / 20;
		return (seconds / 60) + ":" + String.format("%02d", seconds % 60);
	}

	private static String toRoman(int n) {
		String[] romans = {"I", "II", "III", "IV", "V"};
		return n >= 1 && n <= 5 ? romans[n - 1] : String.valueOf(n);
	}
}
