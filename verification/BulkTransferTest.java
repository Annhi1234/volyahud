import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Проверяет выбор слотов для быстрого закидывания/забирания одинаковых
 * предметов.
 *
 * <p>Сам перенос делает сервер (QUICK_MOVE), поэтому здесь проверяется
 * клиентская часть: какие именно слоты будут отправлены, что клик по
 * содержимому сундука/бочки забирает все одинаковые предметы, а предметы с
 * другими компонентами (например, с другим именем) не считаются одинаковыми.
 */
public class BulkTransferTest {

	public static void main(String[] args) throws Exception {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		int failures = 0;

		Class<?> mixin = Class.forName(
				"com.volyacraft.volyahud.client.mixin.ContainerScreenBulkTransferMixin");
		Method find = mixin.getDeclaredMethod("findMatchingInventorySlots",
				ChestMenu.class, Slot.class, Player.class, net.minecraft.world.Container.class);
		find.setAccessible(true);
		Method findContainer = mixin.getDeclaredMethod("findMatchingContainerSlots",
				ChestMenu.class, Slot.class, Player.class);
		findContainer.setAccessible(true);
		// Mixin дозволяє додавати в цільовий клас лише private static
		// методи; інакше завантаження гри падає з InvalidMixinException.
		failures += expect("inventory helper is private static", true,
				java.lang.reflect.Modifier.isPrivate(find.getModifiers())
						&& java.lang.reflect.Modifier.isStatic(find.getModifiers()));
		failures += expect("container helper is private static", true,
				java.lang.reflect.Modifier.isPrivate(findContainer.getModifiers())
						&& java.lang.reflect.Modifier.isStatic(findContainer.getModifiers()));

		// --- 1. Обычная скриня/бочка: 3 ряда, 27 слотов контейнера. ---
		Inventory inv = new Inventory(null);
		ChestMenu chest = ChestMenu.threeRows(0, inv);
		failures += expect("3-row container has 27 slots", 27, chest.getRowCount() * 9);
		failures += expect("first player slot starts after container", 27,
				chest.slots.get(27).index);
		failures += expect("player slots use the supplied inventory", true,
				chest.slots.get(27).container == inv);

		chest.slots.get(27).set(new ItemStack(Items.STONE, 3));
		chest.slots.get(31).set(new ItemStack(Items.STONE, 7));
		chest.slots.get(54).set(new ItemStack(Items.STONE, 1));
		chest.slots.get(40).set(new ItemStack(Items.DIRT, 2));
		// Предметы в самом контейнере: авто-закид их не трогает, а авто-сбор
		// должен забрать все одинаковые стеки.
		chest.slots.get(0).set(new ItemStack(Items.STONE, 64));
		chest.slots.get(5).set(new ItemStack(Items.STONE, 12));

		int[] matching = invoke(find, chest, chest.slots.get(27), null, inv);
		failures += expectArray("matching stone stacks from inventory", matching,
				new int[]{27, 31, 54});

		int[] fromChest = invoke(find, chest, chest.slots.get(0), null, inv);
		failures += expect("deposit helper ignores chest slots", 0, fromChest.length);

		int[] fromEmpty = invoke(find, chest, chest.slots.get(28), null, inv);
		failures += expect("empty clicked slot does nothing", 0, fromEmpty.length);

		int[] containerMatching = invokeContainer(findContainer, chest, chest.slots.get(0), null);
		failures += expectArray("matching stone stacks from container", containerMatching,
				new int[]{0, 5});

		int[] containerFromInventory = invokeContainer(findContainer, chest, chest.slots.get(27), null);
		failures += expect("withdraw helper ignores inventory slots", 0,
				containerFromInventory.length);

		int[] containerFromEmpty = invokeContainer(findContainer, chest, chest.slots.get(1), null);
		failures += expect("empty clicked container slot does nothing", 0,
				containerFromEmpty.length);

		// Другой компонент (имя) — это уже не «такой же» предмет.
		ItemStack namedStone = new ItemStack(Items.STONE, 1);
		namedStone.set(DataComponents.CUSTOM_NAME, Component.literal("named"));
		chest.slots.get(32).set(namedStone);
		chest.slots.get(6).set(namedStone.copy());
		matching = invoke(find, chest, chest.slots.get(27), null, inv);
		failures += expectArray("different components are ignored (inventory)", matching,
				new int[]{27, 31, 54});
		containerMatching = invokeContainer(findContainer, chest, chest.slots.get(0), null);
		failures += expectArray("different components are ignored (container)", containerMatching,
				new int[]{0, 5});

		// --- 2. Двойная скриня: 6 рядов, 54 слота контейнера. ---
		Inventory doubleInv = new Inventory(null);
		ChestMenu doubleChest = ChestMenu.sixRows(0, doubleInv);
		failures += expect("6-row container has 54 slots", 54,
				doubleChest.getRowCount() * 9);
		doubleChest.slots.get(54).set(new ItemStack(Items.DIAMOND, 4));
		doubleChest.slots.get(60).set(new ItemStack(Items.DIAMOND, 2));
		doubleChest.slots.get(0).set(new ItemStack(Items.DIAMOND, 64));
		doubleChest.slots.get(20).set(new ItemStack(Items.DIAMOND, 8));

		int[] doubleMatching = invoke(find, doubleChest, doubleChest.slots.get(54), null,
				doubleInv);
		failures += expectArray("double chest matches only player slots", doubleMatching,
				new int[]{54, 60});

		int[] doubleContainerMatching = invokeContainer(findContainer, doubleChest,
				doubleChest.slots.get(0), null);
		failures += expectArray("double chest matches all container slots", doubleContainerMatching,
				new int[]{0, 20});

		if (failures == 0) {
			System.out.println("BulkTransferTest: all checks passed");
		} else {
			System.out.println("BulkTransferTest: " + failures + " check(s) FAILED");
			System.exit(1);
		}
	}

	private static int[] invoke(Method find, ChestMenu menu, Slot clicked, Player player,
			net.minecraft.world.Container inventory) throws Exception {
		return (int[]) find.invoke(null, menu, clicked, player, inventory);
	}

	private static int[] invokeContainer(Method find, ChestMenu menu, Slot clicked,
			Player player) throws Exception {
		return (int[]) find.invoke(null, menu, clicked, player);
	}

	private static int expect(String name, int expected, int actual) {
		if (expected != actual) {
			System.out.println("FAIL: " + name + " (expected " + expected + ", got "
					+ actual + ')');
			return 1;
		}
		return 0;
	}

	private static int expect(String name, boolean expected, boolean actual) {
		if (expected != actual) {
			System.out.println("FAIL: " + name + " (expected " + expected + ", got "
					+ actual + ')');
			return 1;
		}
		return 0;
	}

	private static int expectArray(String name, int[] actual, int[] expected) {
		if (!Arrays.equals(actual, expected)) {
			System.out.println("FAIL: " + name + " (expected "
					+ Arrays.toString(expected) + ", got " + Arrays.toString(actual) + ')');
			return 1;
		}
		return 0;
	}
}
