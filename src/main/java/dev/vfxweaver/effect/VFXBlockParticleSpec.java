package dev.vfxweaver.effect;

import java.util.Locale;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

/**
 * Immutable configuration of a block-model or item-model particle: the block state or item it
 * draws plus the physics and light values the {@code VFXBlockParticleEngine} integrates and
 * submits. Exactly one of {@link #block()} / {@link #item()} is populated ({@link #hasBlock()} /
 * {@link #hasItem()}). Values match the datapack {@code data/<namespace>/vfx_particles/<name>.json}
 * schema and the {@code particles} effect parameter overrides; {@link #withOverrides(Map)} lays
 * those overrides over a base spec.
 *
 * <p>{@code brightness} uses exactly the block-display semantics: {@link #NO_BRIGHTNESS} ({@code -1})
 * means "use the world light at the particle", any other value is the packed light
 * ({@code block &lt;&lt; 4 | sky &lt;&lt; 20}, produced by the version's pack helper) applied to every
 * vertex of the model regardless of the surrounding light.</p>
 *
 * <p>Rotation is configured by {@link #spinMode()} (tumble / yaw / none), {@link #spinAxis()}
 * (a fixed axis, or {@code null} for a random one), and the {@code spinRandom} / {@code spinFriction} /
 * {@code spinRoll} tuning below.</p>
 *
 * @param block        the block model drawn by the particle, or {@code null} for an item spec
 * @param brightness   packed light override, or {@link #NO_BRIGHTNESS} for world light
 * @param gravity      downward acceleration per tick, as a multiple of {@link #GRAVITY_STEP}
 * @param friction     air drag per tick, {@code 0..1} (1 = no drag)
 * @param collide      surface friction on contact, {@code 0..1}; {@code 0} disables world collision
 * @param bounce       restitution of the normal velocity on contact, {@code 0..1}
 * @param size         model scale (the natural block model is one block)
 * @param life         lifetime in ticks
 * @param spin         rotation angular speed per tick, in degrees
 * @param item         the item model drawn by the particle, or {@link Items#AIR} for a block spec.
 *                     Stored as the load-safe {@link Item}, never an {@link ItemStack}: item
 *                     components are not bound during a datapack reload, so the stack is built by
 *                     {@link #itemStack()} at spawn time
 * @param spinMode     how the model rotates: {@link SpinMode#TUMBLE} (physical, default),
 *                     {@link SpinMode#YAW} (uniform spin about one axis) or {@link SpinMode#NONE}
 * @param spinAxis     fixed rotation axis, or {@code null} for a random axis (a {@code yaw} spec
 *                     with a {@code null} axis spins about world Y)
 * @param spinRandom   {@code 0..1}: how random the initial orientation and the tumble axis are
 *                     (0 = strictly upright and identical for every particle)
 * @param spinFriction {@code 0..1}: how much the contacted block's friction damps the tumble on
 *                     contact (0 = the spin never decays)
 * @param spinRoll     {@code 0..1}: how much tangential impact speed feeds the tumble on contact
 *                     (0 = no roll transfer)
 */
public record VFXBlockParticleSpec(
	@Nullable BlockState block,
	int brightness,
	float gravity,
	float friction,
	float collide,
	float bounce,
	float size,
	int life,
	float spin,
	Item item,
	SpinMode spinMode,
	@Nullable Vector3f spinAxis,
	float spinRandom,
	float spinFriction,
	float spinRoll
) {
	/** How a model particle rotates. */
	public enum SpinMode {
		/** Physical tumbling: full 3D orientation with angular velocity (the default). */
		TUMBLE,
		/** Uniform spin around one axis (the pre-tumble behaviour). */
		YAW,
		/** No rotation at all: upright, no angular velocity, no contact response. */
		NONE;

		/**
		 * Case-insensitive lookup of a {@code spin_mode} name.
		 *
		 * @param name the mode name ({@code tumble}/{@code yaw}/{@code none})
		 * @return the mode, or {@code null} for an unknown name
		 */
		public static @Nullable SpinMode byName(final @Nullable String name) {
			if (name == null) {
				return null;
			}
			switch (name.trim().toLowerCase(Locale.ROOT)) {
				case "tumble":
					return TUMBLE;
				case "yaw":
					return YAW;
				case "none":
					return NONE;
				default:
					return null;
			}
		}
	}

	public VFXBlockParticleSpec {
		item = item == null ? Items.AIR : item;
		spinMode = spinMode == null ? SpinMode.TUMBLE : spinMode;
		spinRandom = clamp(spinRandom, 0.0F, 1.0F);
		spinFriction = clamp(spinFriction, 0.0F, 1.0F);
		spinRoll = clamp(spinRoll, 0.0F, 1.0F);
	}
	/** Brightness sentinel meaning "use the world light at the particle position". */
	public static final int NO_BRIGHTNESS = -1;
	/** Downward velocity (blocks/tick) added per tick at {@code gravity = 1}. Vanilla-like. */
	public static final float GRAVITY_STEP = 0.04F;
	/** Safety cap on {@code life}, so a bad datapack value cannot pin a particle forever. */
	public static final int MAX_LIFE = 12000;
	/** Default spec (stone, no light override, vanilla-like fall). */
	public static final VFXBlockParticleSpec DEFAULT = builder(Blocks.STONE.defaultBlockState()).build();

	private static final float DEFAULT_GRAVITY = 1.0F;
	private static final float DEFAULT_FRICTION = 0.94F;
	private static final float DEFAULT_COLLIDE = 1.0F;
	private static final float DEFAULT_BOUNCE = 0.0F;
	private static final float DEFAULT_SIZE = 0.25F;
	private static final int DEFAULT_LIFE = 60;
	private static final float DEFAULT_SPIN = 0.0F;
	private static final float DEFAULT_SPIN_RANDOM = 1.0F;
	private static final float DEFAULT_SPIN_FRICTION = 1.0F;
	private static final float DEFAULT_SPIN_ROLL = 0.5F;

	/** @return {@code true} when the particle draws a block model */
	public boolean hasBlock() {
		return this.block != null && !this.block.isAir();
	}

	/** @return {@code true} when the particle draws an item model */
	public boolean hasItem() {
		return this.item != null && this.item != Items.AIR;
	}

	/**
	 * The item to draw as a one-item {@link ItemStack}, built on demand. The spec stores only the
	 * load-safe {@link Item} (item components are not bound during a datapack reload), so the stack
	 * is constructed here where the game is fully initialised; call this on the client thread.
	 *
	 * @return a one-item stack, or {@link ItemStack#EMPTY} for a block spec
	 */
	public ItemStack itemStack() {
		return this.item == null || this.item == Items.AIR ? ItemStack.EMPTY : new ItemStack(this.item);
	}

	/**
	 * Starts a builder for the given block with every other field at its default.
	 *
	 * @param block the block model the particle draws
	 * @return a builder carrying the defaults
	 */
	public static Builder builder(final BlockState block) {
		return new Builder(block);
	}

	/**
	 * Starts a builder for the given item with every other field at its default.
	 *
	 * @param item the item model the particle draws
	 * @return a builder carrying the defaults
	 */
	public static Builder builder(final ItemStack item) {
		return new Builder(item);
	}

	/**
	 * Starts a builder for the given load-safe {@link Item} with every other field at its default.
	 * Unlike {@link #builder(ItemStack)} this builds no {@link ItemStack}, so it is safe during a
	 * datapack reload.
	 *
	 * @param item the item model the particle draws
	 * @return a builder carrying the defaults
	 */
	public static Builder builder(final Item item) {
		return new Builder(item);
	}

	/**
	 * Builds an item-model spec with every physics/light field at its default. The code-time
	 * convenience resolves the stack immediately (the game is initialised); datapack parsing stores
	 * only the {@link Item} lazily via {@link #parseItemType(String)}.
	 *
	 * @param item the item model the particle draws
	 * @return the spec
	 */
	public static VFXBlockParticleSpec item(final ItemStack item) {
		return new Builder(item).build();
	}

	/**
	 * Builds an item-model spec from the load-safe {@link Item} with every physics/light field at
	 * its default. Builds no {@link ItemStack}, so it is safe during a datapack reload.
	 *
	 * @param item the item model the particle draws
	 * @return the spec
	 */
	public static VFXBlockParticleSpec item(final Item item) {
		return new Builder(item).build();
	}

	/**
	 * Lay effect parameter overrides over this spec: every key present in {@code params} replaces
	 * the corresponding field. {@code brightness} is a light level ({@code -1} = world light);
	 * {@code life} is clamped to {@code [1, MAX_LIFE]}, {@code spin} to {@code [-3600, 3600]} and
	 * {@code spin_random}/{@code spin_friction}/{@code spin_roll} to {@code [0, 1]}. Parameters the
	 * effect does not declare are absent from the map and keep this spec's value.
	 *
	 * @param params effect parameter values (may be empty)
	 * @return the overridden spec, or {@code this} when nothing applies
	 */
	public VFXBlockParticleSpec withOverrides(final Map<String, Float> params) {
		if (params.isEmpty()) {
			return this;
		}
		int newBrightness = this.brightness;
		float newGravity = this.gravity;
		float newFriction = this.friction;
		float newCollide = this.collide;
		float newBounce = this.bounce;
		float newSize = this.size;
		int newLife = this.life;
		float newSpin = this.spin;
		float newSpinRandom = this.spinRandom;
		float newSpinFriction = this.spinFriction;
		float newSpinRoll = this.spinRoll;
		Float value;
		if ((value = params.get("brightness")) != null) {
			final int level = value.intValue();
			newBrightness = level < 0 ? NO_BRIGHTNESS : packBrightness(level, level);
		}
		if ((value = params.get("gravity")) != null) {
			newGravity = clamp(value, 0.0F, 64.0F);
		}
		if ((value = params.get("friction")) != null) {
			newFriction = clamp(value, 0.0F, 1.0F);
		}
		if ((value = params.get("collide")) != null) {
			newCollide = clamp(value, 0.0F, 1.0F);
		}
		if ((value = params.get("bounce")) != null) {
			newBounce = clamp(value, 0.0F, 1.0F);
		}
		if ((value = params.get("size")) != null) {
			newSize = clamp(value, 0.01F, 8.0F);
		}
		if ((value = params.get("life")) != null) {
			newLife = Math.max(1, Math.min(MAX_LIFE, value.intValue()));
		}
		if ((value = params.get("spin")) != null) {
			newSpin = clamp(value, -3600.0F, 3600.0F);
		}
		if ((value = params.get("spin_random")) != null) {
			newSpinRandom = clamp(value, 0.0F, 1.0F);
		}
		if ((value = params.get("spin_friction")) != null) {
			newSpinFriction = clamp(value, 0.0F, 1.0F);
		}
		if ((value = params.get("spin_roll")) != null) {
			newSpinRoll = clamp(value, 0.0F, 1.0F);
		}
		if (newBrightness == this.brightness && newGravity == this.gravity && newFriction == this.friction
			&& newCollide == this.collide && newBounce == this.bounce && newSize == this.size
			&& newLife == this.life && newSpin == this.spin && newSpinRandom == this.spinRandom
			&& newSpinFriction == this.spinFriction && newSpinRoll == this.spinRoll) {
			return this;
		}
		return new VFXBlockParticleSpec(this.block, newBrightness, newGravity, newFriction, newCollide, newBounce, newSize, newLife, newSpin, this.item, this.spinMode, this.spinAxis, newSpinRandom, newSpinFriction, newSpinRoll);
	}

	/**
	 * Parses a datapack block-state string (e.g. {@code minecraft:oak_stairs[facing=east]}) into a
	 * {@link BlockState}. Returns {@code null} for an unknown block or property, so a caller can
	 * record a per-file parse error instead of crashing.
	 *
	 * @param value the block-state string
	 * @return the parsed state, or {@code null} when it is not a valid block state
	 */
	public static @Nullable BlockState parseBlockState(final String value) {
		try {
			return BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, value, false).blockState();
		} catch (Exception e) {
			return null;
		}
	}

	/**
	 * Parses a datapack item id (e.g. {@code minecraft:skeleton_skull}) into the load-safe
	 * {@link Item}. No {@link ItemStack} is built, so this is safe during a datapack reload before
	 * item components are bound. Returns {@code null} for an unknown item, so a caller can record a
	 * per-file parse error instead of crashing.
	 *
	 * @param value the item id string
	 * @return the item, or {@code null} when the id is not a known item
	 */
	public static @Nullable Item parseItemType(final String value) {
		final Identifier id = Identifier.tryParse(value);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
			return null;
		}
		final Item item = BuiltInRegistries.ITEM.getValue(id);
		if (item == null || item == Items.AIR) {
			return null;
		}
		return item;
	}

	/**
	 * Parses a datapack item id into a single {@link ItemStack}. This constructs the stack, so it
	 * must only be called once the game is initialised (spawn time); datapack parsing uses
	 * {@link #parseItemType(String)} instead. Returns {@code null} for an unknown item.
	 *
	 * @param value the item id string
	 * @return a one-item stack, or {@code null} when the id is not a known item
	 */
	public static @Nullable ItemStack parseItem(final String value) {
		final Item item = parseItemType(value);
		return item == null ? null : new ItemStack(item);
	}

	/**
	 * Packs two light levels (each {@code 0..15}) into the packed-light int the renderer consumes.
	 * The bit layout ({@code block << 4 | sky << 20}) is the vanilla one and is stable across the
	 * supported Minecraft lines; it is reproduced here because the 1.21.11 pack helper
	 * ({@code LightTexture}) is a client class and this method also runs on a dedicated server.
	 *
	 * @param blockLight block-light level
	 * @param skyLight   sky-light level
	 * @return the packed light value
	 */
	public static int packBrightness(final int blockLight, final int skyLight) {
		final int block = Math.max(0, Math.min(15, blockLight));
		final int sky = Math.max(0, Math.min(15, skyLight));
		return (block << 4) | (sky << 20);
	}

	/**
	 * Extracts the block-light level from a packed-light value produced by
	 * {@link #packBrightness(int, int)} or a block display's {@code brightness}.
	 *
	 * @param packedLight the packed light value
	 * @return the block-light level, {@code 0..15}
	 */
	public static int blockLight(final int packedLight) {
		return (packedLight >> 4) & 15;
	}

	/**
	 * Extracts the sky-light level from a packed-light value produced by
	 * {@link #packBrightness(int, int)} or a block display's {@code brightness}.
	 *
	 * @param packedLight the packed light value
	 * @return the sky-light level, {@code 0..15}
	 */
	public static int skyLight(final int packedLight) {
		return (packedLight >> 20) & 15;
	}

	private static float clamp(final float value, final float min, final float max) {
		return Math.max(min, Math.min(max, value));
	}

	/** Mutable builder for {@link VFXBlockParticleSpec}; every field starts at its documented default. */
	public static final class Builder {
		private final @Nullable BlockState block;
		private Item item = Items.AIR;
		private int brightness = NO_BRIGHTNESS;
		private float gravity = DEFAULT_GRAVITY;
		private float friction = DEFAULT_FRICTION;
		private float collide = DEFAULT_COLLIDE;
		private float bounce = DEFAULT_BOUNCE;
		private float size = DEFAULT_SIZE;
		private int life = DEFAULT_LIFE;
		private float spin = DEFAULT_SPIN;
		private SpinMode spinMode = SpinMode.TUMBLE;
		private @Nullable Vector3f spinAxis = null;
		private float spinRandom = DEFAULT_SPIN_RANDOM;
		private float spinFriction = DEFAULT_SPIN_FRICTION;
		private float spinRoll = DEFAULT_SPIN_ROLL;

		private Builder(final @Nullable BlockState block) {
			this.block = block;
		}

		private Builder(final Item item) {
			this.block = null;
			this.item = item == null ? Items.AIR : item;
		}

		private Builder(final ItemStack item) {
			this.block = null;
			this.item = item == null ? Items.AIR : item.getItem();
		}

		/** @param value packed light, or {@link #NO_BRIGHTNESS} for world light */
		public Builder brightness(final int value) {
			this.brightness = value;
			return this;
		}

		/**
		 * Sets the brightness from separate light levels.
		 *
		 * @param blockLight block-light level ({@code 0..15})
		 * @param skyLight   sky-light level ({@code 0..15})
		 */
		public Builder brightness(final int blockLight, final int skyLight) {
			this.brightness = packBrightness(blockLight, skyLight);
			return this;
		}

		public Builder gravity(final float value) {
			this.gravity = value;
			return this;
		}

		public Builder friction(final float value) {
			this.friction = value;
			return this;
		}

		public Builder collide(final float value) {
			this.collide = value;
			return this;
		}

		public Builder bounce(final float value) {
			this.bounce = value;
			return this;
		}

		public Builder size(final float value) {
			this.size = value;
			return this;
		}

		public Builder life(final int value) {
			this.life = value;
			return this;
		}

		public Builder spin(final float value) {
			this.spin = value;
			return this;
		}

		/** @param value the rotation mode ({@code null} = {@link SpinMode#TUMBLE}) */
		public Builder spinMode(final @Nullable SpinMode value) {
			this.spinMode = value == null ? SpinMode.TUMBLE : value;
			return this;
		}

		/** @param value the fixed rotation axis, or {@code null} for a random one */
		public Builder spinAxis(final @Nullable Vector3f value) {
			this.spinAxis = value;
			return this;
		}

		public Builder spinRandom(final float value) {
			this.spinRandom = clamp(value, 0.0F, 1.0F);
			return this;
		}

		public Builder spinFriction(final float value) {
			this.spinFriction = clamp(value, 0.0F, 1.0F);
			return this;
		}

		public Builder spinRoll(final float value) {
			this.spinRoll = clamp(value, 0.0F, 1.0F);
			return this;
		}

		/** @return the built immutable spec */
		public VFXBlockParticleSpec build() {
			return new VFXBlockParticleSpec(this.block, this.brightness, this.gravity, this.friction, this.collide, this.bounce, this.size, this.life, this.spin, this.item, this.spinMode, this.spinAxis, this.spinRandom, this.spinFriction, this.spinRoll);
		}
	}
}
