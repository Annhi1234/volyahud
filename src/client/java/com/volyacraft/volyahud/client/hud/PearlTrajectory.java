package com.volyacraft.volyahud.client.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.volyacraft.volyahud.client.config.HudConfig;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CoreShaders;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.entity.projectile.ThrownExperienceBottle;
import net.minecraft.world.entity.projectile.ThrownPotion;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Малює лінію траєкторії для всіх основних предметів для кидання:
 * ендер-перла, зілля (кидне/затяжне) та пляшка досвіду.
 * Прев'ю малюється, поки предмет тримаєш у руці; слід — за вже кинутим.
 */
public class PearlTrajectory {

	private enum Kind {
		PEARL(1.5f, 0f, 0.03f, 1f, 1f, 1f, 0.35f, 0.8f, 1f),
		POTION(0.5f, -20f, 0.05f, 0.85f, 0.5f, 1f, 0.6f, 0.2f, 0.95f),
		XP_BOTTLE(0.7f, -20f, 0.07f, 0.7f, 1f, 0.4f, 0.4f, 0.9f, 0.15f);

		final float velocity;
		final float pitchOffset;
		final float gravity;
		final float pr, pg, pb; // колір прев'ю
		final float tr, tg, tb; // колір сліду

		Kind(float velocity, float pitchOffset, float gravity, float pr, float pg, float pb, float tr, float tg, float tb) {
			this.velocity = velocity;
			this.pitchOffset = pitchOffset;
			this.gravity = gravity;
			this.pr = pr; this.pg = pg; this.pb = pb;
			this.tr = tr; this.tg = tg; this.tb = tb;
		}
	}

	private record Trail(Kind kind, List<Vec3> points) {
	}

	// траєкторія у польоті кожного живого снаряда: id сутності -> її шлях
	private static final Map<Integer, Trail> TRAILS = new HashMap<>();

	private static final float PREVIEW_WIDTH = 3.0f;
	private static final float TRAIL_WIDTH = 2.5f;

	public static void tick(Minecraft mc) {
		if (mc.level == null) return;
		if (!HudConfig.INSTANCE.pearlTrail) {
			TRAILS.clear();
			return;
		}

		TRAILS.keySet().removeIf(id -> mc.level.getEntity(id) == null);

		for (Entity e : mc.level.entitiesForRendering()) {
			Kind kind = kindOf(e);
			if (kind == null) continue;

			Trail trail = TRAILS.computeIfAbsent(e.getId(), id -> new Trail(kind, new ArrayList<>()));
			List<Vec3> points = trail.points();
			if (points.isEmpty() || points.get(points.size() - 1).distanceToSqr(e.position()) > 0.0004) {
				points.add(e.position());
			}
			while (points.size() > 200) {
				points.remove(0);
			}
		}
	}

	private static Kind kindOf(Entity e) {
		if (e instanceof ThrownEnderpearl) return Kind.PEARL;
		if (e instanceof ThrownPotion) return Kind.POTION;
		if (e instanceof ThrownExperienceBottle) return Kind.XP_BOTTLE;
		return null;
	}

	public static void render(WorldRenderContext context, Minecraft mc) {
		if (mc.player == null || mc.level == null) return;
		HudConfig cfg = HudConfig.INSTANCE;
		Vec3 camPos = context.camera().getPosition();
		float partialTick = context.tickCounter().getGameTimeDeltaPartialTick(true);
		// БАГ-ФІКС (точність далеко від спавну): раніше камеру віднімали
		// всередині матриці (matrices.translate(-camPos...)) і малювали лінію
		// в абсолютних світових координатах — на великій відстані від (0,0,0)
		// це вже великі float-числа, і віднімання на GPU в такій матриці
		// помітно втрачає точність (лінія трясеться/сіпається). Тепер віднімаємо
		// позицію камери в double ще на CPU (усі точки нижче вже камера-відносні),
		// а в матрицю саму трансляцію більше не пишемо.
		Matrix4f pose = context.matrixStack().last().pose();

		// 1. Прев'ю траєкторії, поки тримаєш предмет у руці
		if (cfg.pearlTrajectory) {
			Kind held = heldKind(mc);
			if (held != null) {
				List<Vec3> path = simulateThrow(mc, held, partialTick);
				drawThickLine(pose, relativeTo(path, camPos), held.pr, held.pg, held.pb, 0.9f, PREVIEW_WIDTH);
			}
		}

		// 2. Слід за вже кинутими снарядами
		if (cfg.pearlTrail) {
			for (Trail trail : TRAILS.values()) {
				drawThickLine(pose, relativeTo(trail.points(), camPos), trail.kind().tr, trail.kind().tg, trail.kind().tb, 0.95f, TRAIL_WIDTH);
			}
		}
	}

	/** Переводить список абсолютних світових точок у координати відносно камери (double, ще до касту в float). */
	private static List<Vec3> relativeTo(List<Vec3> points, Vec3 camPos) {
		List<Vec3> result = new ArrayList<>(points.size());
		for (Vec3 p : points) {
			result.add(p.subtract(camPos));
		}
		return result;
	}

	private static Kind heldKind(Minecraft mc) {
		ItemStack main = mc.player.getMainHandItem();
		ItemStack off = mc.player.getOffhandItem();
		Kind k = kindOfStack(main);
		if (k != null) return k;
		return kindOfStack(off);
	}

	private static Kind kindOfStack(ItemStack stack) {
		if (stack.is(Items.ENDER_PEARL)) return Kind.PEARL;
		if (stack.is(Items.SPLASH_POTION) || stack.is(Items.LINGERING_POTION)) return Kind.POTION;
		if (stack.is(Items.EXPERIENCE_BOTTLE)) return Kind.XP_BOTTLE;
		return null;
	}

	/**
	 * Симуляція фізики снаряда, максимально наближена до ванільної
	 * (Projectile#shootFromRotation + Projectile#tick): стартова точка трохи
	 * нижче ока гравця, напрямок з урахуванням зміщення тангажу (наприклад,
	 * -20° для зілля/пляшки досвіду — саме тому вони летять по дузі вгору),
	 * далі рух, опір повітря x0.99 та гравітація застосовуються ПІСЛЯ руху.
	 *
	 * ВАЖЛИВО (фікс траєкторії): раніше напрямок рахувався з НЕінтерпольованих
	 * player.getXRot()/getYRot(), які на швидких поворотах миші на 1 тік
	 * "відстають" від того, куди насправді дивиться камера — лінія трохи
	 * не збігалась із прицілом. Тепер беремо ту саму інтерпольовану позицію
	 * ока та кут огляду (partial tick), що використовує рендер камери,
	 * тому прев'ю завжди точно збігається з напрямком погляду.
	 */
	private static List<Vec3> simulateThrow(Minecraft mc, Kind kind, float partialTick) {
		List<Vec3> points = new ArrayList<>();
		Player player = mc.player;
		Vec3 eye = player.getEyePosition(partialTick);
		Vec3 pos = new Vec3(eye.x, eye.y - 0.1, eye.z);
		Vec3 vel = shootDirection(player, partialTick, kind.pitchOffset).scale(kind.velocity);

		points.add(pos);
		for (int i = 0; i < 140; i++) {
			pos = pos.add(vel);
			vel = vel.scale(0.99).subtract(0, kind.gravity, 0);
			points.add(pos);
			if (hitsSolid(mc, pos)) {
				break;
			}
		}
		return points;
	}

	/**
	 * Раніше зупинка лінії перевіряла лише "чи блок = повітря" (isAir()).
	 * Це змушувало прев'ю обриватись навіть у траві, квітах, килимках, знаку —
	 * тобто в блоках, крізь які снаряд насправді пролітає без перешкод.
	 * Перевірка через форму колізії — набагато точніша.
	 */
	private static boolean hitsSolid(Minecraft mc, Vec3 pos) {
		BlockPos blockPos = BlockPos.containing(pos);
		var state = mc.level.getBlockState(blockPos);
		if (state.isAir()) return false;
		return !state.getCollisionShape(mc.level, blockPos).isEmpty();
	}

	private static Vec3 shootDirection(Player player, float partialTick, float pitchOffset) {
		float pitch = player.getViewXRot(partialTick);
		float yaw = player.getViewYRot(partialTick);
		double pitchRad = Math.toRadians(pitch);
		double yawRad = Math.toRadians(yaw);
		double x = -Math.sin(yawRad) * Math.cos(pitchRad);
		double y = -Math.sin(Math.toRadians(pitch + pitchOffset));
		double z = Math.cos(yawRad) * Math.cos(pitchRad);
		return new Vec3(x, y, z).normalize();
	}

	/**
	 * Малює товсту, добре видну лінію напряму через Tesselator, а не через
	 * усереднений RenderType.lines() — той форсує ширину 1px незалежно від
	 * RenderSystem.lineWidth(...), тому лінія практично не була видна.
	 * Тут ширина керується напряму перед відправкою буфера на відмальовку
	 * (той самий прийом, яким ваніла малює товсті контури хітбоксів).
	 */
	private static void drawThickLine(Matrix4f pose, List<Vec3> points, float r, float g, float b, float a, float width) {
		if (points.size() < 2) return;

		Tesselator tesselator = Tesselator.getInstance();
		BufferBuilder buffer = tesselator.begin(VertexFormat.Mode.LINES, DefaultVertexFormat.POSITION_COLOR_NORMAL);

		for (int i = 0; i < points.size() - 1; i++) {
			Vec3 a1 = points.get(i);
			Vec3 a2 = points.get(i + 1);
			Vec3 normal = a2.subtract(a1).normalize();
			buffer.addVertex(pose, (float) a1.x, (float) a1.y, (float) a1.z)
					.setColor(r, g, b, a)
					.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
			buffer.addVertex(pose, (float) a2.x, (float) a2.y, (float) a2.z)
					.setColor(r, g, b, a)
					.setNormal((float) normal.x, (float) normal.y, (float) normal.z);
		}

		RenderSystem.setShader(CoreShaders.RENDERTYPE_LINES);
		RenderSystem.enableBlend();
		RenderSystem.defaultBlendFunc();
		RenderSystem.enableDepthTest();
		RenderSystem.depthMask(false);
		RenderSystem.lineWidth(width);

		BufferUploader.drawWithShader(buffer.buildOrThrow());

		RenderSystem.lineWidth(1.0f);
		RenderSystem.depthMask(true);
		RenderSystem.disableBlend();
	}
}
