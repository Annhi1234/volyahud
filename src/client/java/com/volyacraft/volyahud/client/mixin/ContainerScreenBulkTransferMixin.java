package com.volyacraft.volyahud.client.mixin;

import com.volyacraft.volyahud.client.config.HudConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.NonNullList;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Швидке перенесення ВСІХ однотипних предметів між інвентарем і скринею.
 *
 * <p>Два незалежні режими (кожен вмикається своєю комбінацією):
 * <ul>
 *   <li><b>Авто-закид</b> (Alt+F6): Shift+ЛКМ або Shift+ПКМ по предмету у
 *       СВОЄМУ інвентарі переносить у відкриту скриню/бочку ВСІ такі самі
 *       предмети, а не лише один стек.</li>
 *   <li><b>Авто-збір</b> (Alt+F7): Shift+ЛКМ або Shift+ПКМ по предмету в
 *       скрині/бочці забирає в інвентар ВСІ такі самі предмети з цього
 *       контейнера.</li>
 * </ul>
 *
 * <p>У Minecraft перенесення предметів між слотами робить СЕРВЕР: клієнт лише
 * надсилає пакет кліку, а сервер виконує переміщення. Тому для кожного
 * знайденого слота ми надсилаємо звичайний Shift+клік ({@code QUICK_MOVE}).
 * Сервер сам зливає стеки й перевіряє ліміти — клієнт не підміняє вміст
 * інвентаря «своїми руками».
 *
 * <p>Спрацьовує лише для {@link ChestMenu} (скриня, подвійна скриня, бочка),
 * щоб не ламати верстак, піч та інші меню.
 */
@Mixin(AbstractContainerScreen.class)
public class ContainerScreenBulkTransferMixin {

	@Inject(method = "slotClicked", at = @At("HEAD"), cancellable = true)
	private void volyahud$bulkTransfer(Slot slot, int slotId, int mouseButton,
			ClickType clickType, CallbackInfo ci) {
		// Ванільний Shift+ЛКМ приходить як QUICK_MOVE. Shift+ПКМ у різних
		// версіях/меню може приходити як PICKUP, тому додатково перевіряємо
		// реальний стан Shift — це і є та сама комбінація, яку просив гравець.
		boolean bulkClick = clickType == ClickType.QUICK_MOVE
				|| (Screen.hasShiftDown() && mouseButton == 1);
		if (!bulkClick) return;

		AbstractContainerScreen<?> screen = (AbstractContainerScreen<?>) (Object) this;
		AbstractContainerMenu menu = screen.getMenu();
		if (!(menu instanceof ChestMenu chestMenu)) return;
		if (slot == null) return;

		Minecraft mc = Minecraft.getInstance();
		MultiPlayerGameMode gameMode = mc.gameMode;
		Player player = mc.player;
		if (gameMode == null || player == null) return;

		int containerSlots = chestMenu.getRowCount() * 9;
		Container playerInventory = player.getInventory();

		// Куди клікнули: у свій інвентар (закид) чи в контейнер (забір).
		if (slot.index >= containerSlots && slot.container == playerInventory) {
			if (!HudConfig.INSTANCE.bulkChestTransfer) return;
			int[] matching = findMatchingInventorySlots(chestMenu, slot, player, playerInventory);
			if (matching.length == 0) return;
			for (int slotIndex : matching) {
				gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0,
						ClickType.QUICK_MOVE, player);
			}
			ci.cancel();
			return;
		}

		if (slot.index < containerSlots && slot.container != playerInventory) {
			if (!HudConfig.INSTANCE.bulkChestWithdraw) return;
			int[] matching = findMatchingContainerSlots(chestMenu, slot, player);
			if (matching.length == 0) return;
			for (int slotIndex : matching) {
				gameMode.handleInventoryMouseClick(menu.containerId, slotIndex, 0,
						ClickType.QUICK_MOVE, player);
			}
			ci.cancel();
		}
	}

	/**
	 * Індекси слотів СВОГО інвентаря з таким самим предметом, як у
	 * {@code clickedSlot}. Порожній масив = «не втручатись».
	 *
	 * <p>Метод винесений окремо, щоб його можна було перевірити без
	 * відкритого екрана (див. verification/BulkTransferTest).
	 */
	private static int[] findMatchingInventorySlots(ChestMenu menu, Slot clickedSlot,
			Player player, Container playerInventory) {
		if (clickedSlot == null) return EMPTY_SLOTS;

		int containerSlots = menu.getRowCount() * 9;
		if (clickedSlot.index < containerSlots || clickedSlot.container != playerInventory) {
			return EMPTY_SLOTS;
		}

		ItemStack clicked = clickedSlot.getItem();
		if (clicked.isEmpty()) return EMPTY_SLOTS;
		if (!clickedSlot.isActive() || !clickedSlot.mayPickup(player)) return EMPTY_SLOTS;

		NonNullList<Slot> slots = menu.slots;
		int[] matching = new int[slots.size()];
		int matchingCount = 0;
		for (int i = containerSlots; i < slots.size(); i++) {
			Slot invSlot = slots.get(i);
			if (invSlot.container != playerInventory) continue;
			ItemStack stack = invSlot.getItem();
			if (stack.isEmpty()) continue;
			if (!ItemStack.isSameItemSameComponents(stack, clicked)) continue;
			if (!invSlot.isActive() || !invSlot.mayPickup(player)) continue;
			matching[matchingCount++] = i;
		}
		return matchingCount == 0 ? EMPTY_SLOTS : java.util.Arrays.copyOf(matching, matchingCount);
	}

	/**
	 * Індекси слотів КОНТЕЙНЕРА з таким самим предметом, як у
	 * {@code clickedSlot}. Використовується для авто-збору.
	 */
	private static int[] findMatchingContainerSlots(ChestMenu menu, Slot clickedSlot,
			Player player) {
		if (clickedSlot == null) return EMPTY_SLOTS;

		int containerSlots = menu.getRowCount() * 9;
		if (clickedSlot.index >= containerSlots) return EMPTY_SLOTS;

		ItemStack clicked = clickedSlot.getItem();
		if (clicked.isEmpty()) return EMPTY_SLOTS;
		if (!clickedSlot.isActive() || !clickedSlot.mayPickup(player)) return EMPTY_SLOTS;

		NonNullList<Slot> slots = menu.slots;
		int[] matching = new int[containerSlots];
		int matchingCount = 0;
		for (int i = 0; i < containerSlots; i++) {
			Slot containerSlot = slots.get(i);
			ItemStack stack = containerSlot.getItem();
			if (stack.isEmpty()) continue;
			if (!ItemStack.isSameItemSameComponents(stack, clicked)) continue;
			if (!containerSlot.isActive() || !containerSlot.mayPickup(player)) continue;
			matching[matchingCount++] = i;
		}
		return matchingCount == 0 ? EMPTY_SLOTS : java.util.Arrays.copyOf(matching, matchingCount);
	}

	private static final int[] EMPTY_SLOTS = new int[0];
}
