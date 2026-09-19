package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXWorldBindings;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The client {@link VFXWorldBindings.EntityReader}: resolves an entity UUID directly and a selector
 * through the client level, choosing the matching loaded entity nearest the local player (ties
 * broken by UUID text), so a selector that matches several entities still resolves deterministically.
 * No match returns {@code null} (the binding falls back and warns once).
 *
 * <p>The full vanilla selector grammar is server-side: {@code EntitySelector.findEntities} needs a
 * {@code CommandSourceStack} backed by a {@code ServerLevel}, which the client does not have. This
 * reader resolves the documented client subset — {@code @s}, {@code @p}, {@code @a}, {@code @e},
 * {@code @r}, a bare entity name, and an optional {@code type=<id>} filter — which covers the
 * entity/screen_rect binding contract. Resolution is a bounded per-frame scan (cached by
 * {@link VFXWorldBindings}), never a per-pixel path.
 */
public final class VFXClientEntityReader implements VFXWorldBindings.EntityReader {
	/** The most selector matches considered before the nearest-to-player pick. */
	private static final int MAX_CANDIDATES = 32;

	@Override
	public float @Nullable [] point(final @Nullable String selector, final @Nullable String uuid, final String point) {
		final Entity entity = resolve(selector, uuid);
		if (entity == null) {
			return null;
		}
		return switch (point) {
			case "feet" -> new float[]{(float) entity.getX(), (float) entity.getY(), (float) entity.getZ()};
			case "eyes" -> new float[]{(float) entity.getX(), (float) entity.getEyeY(), (float) entity.getZ()};
			default -> {
				final AABB box = entity.getBoundingBox();
				yield new float[]{(float) box.getCenter().x, (float) box.getCenter().y, (float) box.getCenter().z};
			}
		};
	}

	@Override
	public float @Nullable [] bounds(final @Nullable String selector, final @Nullable String uuid) {
		final Entity entity = resolve(selector, uuid);
		if (entity == null) {
			return null;
		}
		final AABB box = entity.getBoundingBox();
		return new float[]{(float) box.minX, (float) box.minY, (float) box.minZ, (float) box.maxX, (float) box.maxY, (float) box.maxZ};
	}

	private static @Nullable Entity resolve(final @Nullable String selector, final @Nullable String uuid) {
		final Minecraft minecraft = Minecraft.getInstance();
		final ClientLevel level = minecraft.level;
		if (level == null) {
			return null;
		}
		if (uuid != null) {
			try {
				return level.getEntity(UUID.fromString(uuid));
			} catch (final IllegalArgumentException e) {
				return null;
			}
		}
		if (selector == null || selector.isBlank()) {
			return null;
		}
		final String trimmed = selector.trim();
		if (trimmed.startsWith("@s")) {
			return minecraft.player;
		}
		final Vec3 reference = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
		if (trimmed.startsWith("@p")) {
			Entity best = null;
			double bestDistance = Double.MAX_VALUE;
			for (final AbstractClientPlayer player : level.players()) {
				final double distance = player.position().distanceToSqr(reference);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = player;
				}
			}
			return best;
		}
		final boolean playersOnly = trimmed.startsWith("@a");
		final String type = typeFilter(trimmed);
		final boolean bareName = !trimmed.startsWith("@");
		Entity best = null;
		double bestDistance = Double.MAX_VALUE;
		int matches = 0;
		for (final Entity entity : level.entitiesForRendering()) {
			if (matches >= MAX_CANDIDATES) {
				break;
			}
			if (playersOnly && !(entity instanceof AbstractClientPlayer)) {
				continue;
			}
			if (type != null && !BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(type)) {
				continue;
			}
			if (bareName && !entity.getName().getString().equals(trimmed)) {
				continue;
			}
			matches++;
			final double distance = entity.position().distanceToSqr(reference);
			if (distance < bestDistance) {
				bestDistance = distance;
				best = entity;
			}
		}
		return best;
	}

	/** Extracts the {@code type=<id>} value of a selector, or {@code null} when absent. */
	private static @Nullable String typeFilter(final String selector) {
		final int start = selector.indexOf("type=");
		if (start < 0) {
			return null;
		}
		final int valueStart = start + "type=".length();
		int end = valueStart;
		while (end < selector.length() && selector.charAt(end) != ',' && selector.charAt(end) != ']') {
			end++;
		}
		final String value = selector.substring(valueStart, end);
		return value.isBlank() ? null : value;
	}
}
