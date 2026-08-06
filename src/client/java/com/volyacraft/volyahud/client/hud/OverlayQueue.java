package com.volyacraft.volyahud.client.hud;

import net.minecraft.world.entity.player.Player;

import java.util.ArrayList;
import java.util.List;

/**
 * WorldRenderEvents (3D) виконується раніше за HudRenderCallback (2D) у тому ж
 * кадрі, тому тут просто зберігаємо вже спроєктовані на екран координати
 * гравців, а малюємо текст/іконки вже у 2D-проході (там простіше й дешевше
 * малювати текст/предмети, ніж напряму у world render context).
 */
public final class OverlayQueue {

	public record Entry(int x, int y, Player player, double distance) {
	}

	private static final List<Entry> QUEUE = new ArrayList<>();

	private OverlayQueue() {
	}

	public static void queue(int x, int y, Player player, double distance) {
		QUEUE.add(new Entry(x, y, player, distance));
	}

	public static List<Entry> drain() {
		List<Entry> copy = new ArrayList<>(QUEUE);
		QUEUE.clear();
		return copy;
	}
}
