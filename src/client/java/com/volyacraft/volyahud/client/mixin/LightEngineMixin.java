package com.volyacraft.volyahud.client.mixin;

import com.volyacraft.volyahud.client.hud.DynamicLight;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.lighting.BlockLightEngine;
import net.minecraft.world.level.lighting.LightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * ГОЛОВНИЙ ФІКС "НЕВИДИМИХ ФАКЕЛІВ".
 *
 * <p>Раніше фіча "світло від предмета в руці" працювала так: клієнт ставив у
 * світ справжній ванільний блок {@code minecraft:light} у позицію гравця.
 * Це і є причиною скарги "невидимі факели під собою, заважають будувати та
 * засаджувати":
 *
 * <ul>
 *   <li>блок реально існував у клієнтському світі й займав клітинку, у якій
 *       гравець стояв (а отже — туди не мож було нормально поставити блок,
 *       засадити насіння тощо);</li>
 *   <li>коли в цій клітинці з'являвся справжній блок, старий код просто
 *       "забував" про наш фейковий блок ({@code appliedPos = null}) — і блок
 *       {@code minecraft:light} залишався лежати назавжди, невидимий, і вже
 *       ніхто не міг його прибрати.</li>
 * </ul>
 *
 * <p>Тепер ми НЕ чіпаємо світ узагалі. Натомість підміняємо відповідь
 * рушія світла: коли він питає "який блок стоїть ось тут?" — ми кажемо, що
 * це {@code minecraft:light} з потрібним рівнем яскравості. Для рушія світла
 * це повноцінне джерело світла: він сам порахує розповсюдження, просвіти
 * та пересвічення з темрявою — рівно так, як для справжнього факела.
 *
 * <p>Результат:
 * <ul>
 *   <li>у світі НІЧОГО не змінюється — жодного фейкового блоку, жодної
 *       можливості щось зламати, заблокувати або "залишити по собі";</li>
 *   <li>світло не зникає при пересинхронізації чанка сервером — бо блоку, який
 *       можна було б затерти, просто немає;</li>
 *   <li>навантаження на рендер — таке саме, як раніше: зміна світла і так
 *       перебудовує секції чанка (через {@code setSectionDirty}).</li>
 * </ul>
 *
 * <p>Підміна діє ЛИШЕ для блочного світла ({@link BlockLightEngine}) і ЛИШЕ
 * для однієї позиції — тієї, де тримають предмет-джерело. Небельний світ
 * (сонячне) не чіпається взагалі.
 */
@Mixin(LightEngine.class)
public class LightEngineMixin {

	@Inject(method = "getState", at = @At("HEAD"), cancellable = true)
	private void volyahud$fakeHeldItemLight(BlockPos pos, CallbackInfoReturnable<BlockState> cir) {
		// Тільки блочне світло: сонячний рушій uses іншу логіку і нам це не треба.
		if (!((Object) this instanceof BlockLightEngine)) {
			return;
		}

		DynamicLight.FakeLight fake = DynamicLight.currentFakeLight();
		if (fake == null || !fake.pos().equals(pos)) {
			return;
		}

		cir.setReturnValue(fake.state());
	}
}
