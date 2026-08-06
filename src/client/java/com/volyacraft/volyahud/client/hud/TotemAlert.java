package com.volyacraft.volyahud.client.hud;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;

/**
 * Заповнюється з ClientPacketListenerMixin, коли приходить
 * ClientboundEntityEventPacket зі статусом 35 (спрацював тотем).
 * Ванільна гра НЕ приховує це від клієнта — це видима частина протоколу
 * (партиклі/звук тотема все одно летять усім навколо), тому це не read-хак,
 * просто зручне візуальне сповіщення замість того, щоб вилавливати це очима.
 */
public class TotemAlert {

	public static void onTotemUsed(Entity entity) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || entity == mc.player) return;
		if (mc.player.distanceTo(entity) > 32) return;

		mc.getToasts().addToast(new SystemToast(
				SystemToast.SystemToastId.NARRATOR_TOGGLE,
				Component.literal("Тотем!"),
				Component.literal(entity.getName().getString() + " використав(ла) тотем")
		));
	}

	public static void tick(Minecraft mc) {
		// Наразі сповіщення живе саме як toast (система сама його прибирає).
		// Місце залишене для майбутнього розширення (наприклад мітка над гравцем).
	}
}
