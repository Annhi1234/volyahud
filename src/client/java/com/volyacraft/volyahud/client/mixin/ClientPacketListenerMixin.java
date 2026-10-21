package com.volyacraft.volyahud.client.mixin;

import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.hud.DamageMeter;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехоплення двох публічних серверних пакетів, які ми й так отримуємо:
 *
 * <ul>
 *   <li><b>ClientboundEntityEventPacket</b> зі статусом 35 — ванільний
 *       "статус-івент", яким сервер повідомляє клієнтів про спрацювання тотема
 *       безсмертя (щоб показати партиклі/звук усім навколо). Ми лише читаємо
 *       цей вже публічний мережевий пакет трохи раніше, ніж він доходить до
 *       рендеру партиклів, і показуємо власне текстове сповіщення.</li>
 *   <li><b>ClientboundAnimatePacket</b> з дією 4 (звичайний крит) або 5
 *       (магічний крит) — саме так сервер повідомляє про критичний удар
 *       (щоб показати крит-партиклі).</li>
 * </ul>
 *
 * <p><b>ВАЖЛИВИЙ ФІКС (панель реальної шкоди не працювала взагалі):</b>
 * раніше крит ловився в тому самому {@code handleEntityEvent}, де й тотем —
 * тобто ми чекали пакет, який сервер НІКОЛИ не надсилає для крита. Насправді
 * крит приходить окремим пакетом {@link ClientboundAnimatePacket} (дії 4 і 5),
 * і саме він обробляється у {@link ClientPacketListener#handleAnimate}. Тому
 * "Реальна шкода (крит)" не показувалася ніколи — тепер ловимо правильний
 * пакет у правильному методі.
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

	/**
	 * Тотем безсмертя: статус-івент 35.
	 */
	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void volyahud$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		if (packet.getEventId() != 35 || !HudConfig.INSTANCE.totemAlert) {
			return;
		}

		Level level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return;

		Entity entity = packet.getEntity(level);
		if (entity == null) return;

		TotemAlert.onTotemUsed(entity);
	}

	/**
	 * Критичний удар: анімація-пакет, дії 4 (звичайний крит) і 5 (магічний крит).
	 */
	@Inject(method = "handleAnimate", at = @At("HEAD"))
	private void volyahud$onAnimate(ClientboundAnimatePacket packet, CallbackInfo ci) {
		if (!HudConfig.INSTANCE.damageMeter) {
			return;
		}

		int action = packet.getAction();
		if (action != ClientboundAnimatePacket.CRITICAL_HIT
				&& action != ClientboundAnimatePacket.MAGIC_CRITICAL_HIT) {
			return;
		}

		Level level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return;

		Entity entity = level.getEntity(packet.getId());
		if (entity == null) return;

		DamageMeter.onCrit(entity);
	}
}
