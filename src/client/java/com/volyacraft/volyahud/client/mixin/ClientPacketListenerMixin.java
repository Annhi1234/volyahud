package com.volyacraft.volyahud.client.mixin;

import com.volyacraft.volyahud.client.config.HudConfig;
import com.volyacraft.volyahud.client.hud.DamageMeter;
import com.volyacraft.volyahud.client.hud.TotemAlert;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * ClientboundEntityEventPacket зі status == 35 — це ванільний "статус-івент",
 * яким сервер повідомляє клієнтів про спрацювання тотема безсмертя
 * (щоб показати партиклі/звук всім навколо). Ми лише читаємо цей вже
 * публічний мережевий пакет трохи раніше, ніж він доходить до рендеру
 * партиклів, і показуємо власне сповіщення.
 *
 * ПРИМІТКА: якщо після компіляції Loom скаже, що метод handleEntityEvent
 * не знайдено — назва методу в офіційних мапінгах могла трохи відрізнятись
 * між збірками 1.21.4; тоді треба звірити актуальну назву через
 * genSources/decompile і поправити тут @Inject(method = "...").
 */
@Mixin(ClientPacketListener.class)
public class ClientPacketListenerMixin {

	@Inject(method = "handleEntityEvent", at = @At("HEAD"))
	private void volyahud$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
		byte id = packet.getEventId();
		boolean isTotem = id == 35 && HudConfig.INSTANCE.totemAlert;
		// id 4 = звичайний критичний удар, id 5 = магічний крит — той самий
		// вже публічний статус-івент, яким сервер шле крит-партиклі всім
		// навколо, лише читаємо його трохи раніше за рендер партиклів
		// (детальніше — у коментарі над DamageMeter).
		boolean isCrit = (id == 4 || id == 5) && HudConfig.INSTANCE.damageMeter;
		if (!isTotem && !isCrit) return;

		Level level = net.minecraft.client.Minecraft.getInstance().level;
		if (level == null) return;

		Entity entity = packet.getEntity(level);
		if (entity == null) return;

		if (isTotem) {
			TotemAlert.onTotemUsed(entity);
		}
		if (isCrit) {
			DamageMeter.onCrit(entity);
		}
	}
}
