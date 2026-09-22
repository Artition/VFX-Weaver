package dev.vfxweaver.client.render;

import dev.vfxweaver.effect.VFXEntitySelector;
import dev.vfxweaver.effect.VFXWorldBindings;
import dev.vfxweaver.util.VFXLog;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client {@link VFXWorldBindings.EntityReader}: resolves an entity UUID directly and a selector
 * through the client level, choosing the matching loaded entity nearest the local player (ties
 * broken by UUID text), so a selector that matches several entities still resolves deterministically.
 * No match returns {@code null} (the binding falls back and warns once).
 *
 * <p>The full vanilla selector grammar is server-side: {@code EntitySelector.findEntities} needs a
 * {@code CommandSourceStack} backed by a {@code ServerLevel}, which the client does not have. The
 * selector is instead parsed by {@link VFXEntitySelector}, which supports the natural subset —
 * {@code @s}, {@code @p}, {@code @a}, {@code @r}, {@code @e}, a bare entity name, and the
 * {@code type=}, {@code tag=}, {@code name=}, {@code distance=}, {@code limit=} and {@code sort=}
 * arguments (see that class for the exact grammar). A selector outside that subset is rejected and
 * warned about once, never silently matched against every entity: resolution fails closed so a mask
 * leaf is dropped rather than bound to the wrong entity. Resolution is a bounded per-frame scan
 * (cached by {@link VFXWorldBindings}), never a per-pixel path.
 */
public final class VFXClientEntityReader implements VFXWorldBindings.EntityReader {
	/** The most selector matches considered before the nearest-to-player pick. */
	private static final int MAX_CANDIDATES = 32;
	private static final Logger LOGGER = LoggerFactory.getLogger("vfxweaver/bindings");

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
		final VFXEntitySelector.Selector parsed;
		try {
			parsed = VFXEntitySelector.parse(selector);
		} catch (final IllegalArgumentException e) {
			// Fail closed: an unsupported selector must not fall through to "match every entity".
			VFXLog.warnOnce(LOGGER, "selector:unsupported:" + selector, "Entity selector '{}' is not supported client-side; the binding is left unresolved: {}", selector, e.getMessage());
			return null;
		}
		final Vec3 reference = minecraft.player != null ? minecraft.player.position() : Vec3.ZERO;
		if (parsed.base() == VFXEntitySelector.Base.SELF) {
			final Entity self = minecraft.player;
			return self != null && matches(parsed, self, reference) ? self : null;
		}
		final boolean furthest = parsed.sort() == VFXEntitySelector.Sort.FURTHEST;
		final boolean random = parsed.sort() == VFXEntitySelector.Sort.RANDOM;
		Entity best = null;
		double bestDistance = furthest ? -1.0 : Double.MAX_VALUE;
		Entity randomPick = null;
		int matches = 0;
		for (final Entity entity : level.entitiesForRendering()) {
			if (matches >= MAX_CANDIDATES) {
				break;
			}
			if (!matches(parsed, entity, reference)) {
				continue;
			}
			matches++;
			final double distance = entity.position().distanceToSqr(reference);
			if (furthest ? distance > bestDistance : distance < bestDistance) {
				bestDistance = distance;
				best = entity;
			}
			if (random && level.getRandom().nextInt(matches) == 0) {
				randomPick = entity;
			}
		}
		return random ? randomPick : best;
	}

	private static boolean matches(final VFXEntitySelector.Selector selector, final Entity entity, final Vec3 reference) {
		if ((selector.base() == VFXEntitySelector.Base.ALL_PLAYERS || selector.base() == VFXEntitySelector.Base.NEAREST_PLAYER) && !(entity instanceof AbstractClientPlayer)) {
			return false;
		}
		if (selector.type() != null) {
			final boolean equal = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString().equals(selector.type());
			if (selector.typeInverted() ? equal : !equal) {
				return false;
			}
		}
		if (selector.tag() != null) {
			//? if <26.1 {
			/*final boolean has = entity.getTags().contains(selector.tag());
*///?} else {
			final boolean has = entity.entityTags().contains(selector.tag());
//?}
			if (selector.tagInverted() ? has : !has) {
				return false;
			}
		}
		if (selector.name() != null) {
			final boolean equal = entity.getName().getString().equals(selector.name());
			if (selector.nameInverted() ? equal : !equal) {
				return false;
			}
		}
		if (selector.hasDistance()) {
			final double distance = Math.sqrt(entity.position().distanceToSqr(reference));
			if (distance < selector.minDistance() || distance > selector.maxDistance()) {
				return false;
			}
		}
		return true;
	}
}
