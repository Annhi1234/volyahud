package com.volyacraft.volyahud.client.mixin;

import com.volyacraft.volyahud.client.hud.DamageMeter;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Перехоплює клієнтський виклик атаки сутності — той самий момент, коли
 * гравець б'є лівою кнопкою миші по цілі. Потрібно системі реальної шкоди
 * (DamageMeter), щоб зіставити наступний крит-івент (id 4/5, ловиться в
 * ClientPacketListenerMixin) із конкретною ціллю та зняти її здоров'я
 * "до" удару.
 *
 * Тільки читає ціль, нічого не змінює й не скасовує (HEAD-інжект без cancel).
 */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {

	@Inject(method = "attack", at = @At("HEAD"))
	private void volyahud$onAttack(Player player, Entity target, CallbackInfo ci) {
		DamageMeter.onAttack(target);
	}
}
