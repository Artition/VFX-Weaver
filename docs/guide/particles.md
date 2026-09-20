# Block and item particles

**Custom particles** are reusable definitions under `data/<namespace>/vfx_particles/<name>.json`,
id `<namespace>:<name>`. A `particles` effect names one with `"particle": "<namespace>:<name>"`, or
uses the inline `block`/`item` form (see below). The file's `kind` field selects the parser:

- **omitted** (or anything other than `"spark"`) — a **block/item model** preset: the particle is a
  real block or item model (this page).
- `"kind": "spark"` — a **glowing sprite** preset: the particle is an additive camera-facing quad
  ([Spark presets](particles/sparks.md)).

Both kinds share one two-layer registry, the `vfx_particles` directory and the `particles` effect:

- **Datapack layer** — every `vfx_particles` file in the loaded datapacks, replaced on `/reload`.
- **Local layer** — presets registered from code with `VFXAPI.registerBlockParticle` /
  `VFXAPI.registerSpark`; it survives `/reload` and is never replaced by one.

The datapack layer **wins** for the same id. Presets are **client-local and never synced to other
players**: a preset only exists on a client that has its file or code registration, so a
`particles` effect that names one resolves it locally. Each layer is capped at **256** presets
(block/item and spark counted separately). Every file is parsed on its own, so one broken JSON is
recorded (and shown by `/vfx validate`) without taking down the rest of the directory.

## Block and item mode

Instead of a vanilla particle, the `particles` effect can spawn **real block or item models** (full
3D model, textures, lighting and occlusion). Each particle is a **client-side display entity** — a
`BlockDisplay` or `ItemDisplay` added to the client level — so vanilla interpolates its motion
(smooth, no stepped submits) and its brightness, scale and tumbling orientation are the display
entity's own brightness override / transformation. A model particle spawns in a random 3D
orientation and, when `spin` is non-zero, tumbles about a random axis like a real falling cube; on
landing, the contacted block's friction damps the tumble until it settles. Pick a single model
inline, or a reusable preset:

- `"particle": "block"` with `"block": "<block state>"` — an inline block spec. The `block` field
  accepts a full block state string, e.g. `"minecraft:oak_stairs[facing=east]"` (default
  `minecraft:stone`).
- `"particle": "item"` with `"item": "<item id>"` — an inline item spec, e.g.
  `"item": "minecraft:skeleton_skull"` or `"minecraft:diamond_sword"`. This is how you get a
  **skull/head particle**: the block form of a skull has no baked block model (a block-entity
  renderer draws it), but the item form does. See the preset form below for a reusable item spec.
- `"particle": "<namespace>:<preset>"` — a preset from `data/<namespace>/vfx_particles/<name>.json`
  or registered through `VFXAPI.registerBlockParticle`. A preset is block- or item-based; both
  render and share the physics below. An unknown preset is treated as a vanilla particle id; with no
  such particle nothing is emitted and the mod logs one warning (the documented fallback).

Everything else (shape, `rate`, positions/bindings, aimed mode) works exactly as the vanilla
`particles` effect — see the [`particles` effect](effects.md#particles) in the effects guide. The
model's physics and light are taken from the spec and overridden per effect by these params:

| Param | Default (inline/preset) | Description |
|---|---|---|
| `brightness` | -1 | Light override with **block-display semantics**: `-1` = use the world light at each particle, `0..15` = render that light level regardless of the surroundings (`[blockLight, skyLight]` in a preset for a split value). |
| `gravity` | 1 | Downward acceleration per tick, as a multiple of the vanilla `0.04` (`0..64`). `0` = weightless. |
| `friction` | 0.94 | Air drag multiplier per tick (0..1; 1 = no drag). |
| `collide` | 1 | Surface friction on contact, `0..1`; `0` disables world collision entirely. |
| `bounce` | 0 | Restitution of the normal velocity on contact (`0..1`; `0` = no bounce, `1` = full bounce). |
| `size` | 0.25 | Model scale (1 = one full block; `0.01..8`). |
| `life` | 60 | Lifetime in ticks (`1..12000`). |
| `spin` | 0 | Rotation angular speed per tick, in degrees (`-3600..3600`; in model mode this replaces the helix-phase meaning of `spin`). `0` gives a static model. |
| `spin_random` | 1.0 | `0..1`: how random the initial orientation (and, in tumble mode, the rotation axis) is. `0` = strictly upright and identical for every particle, deterministic; `1` = fully random. |
| `spin_friction` | 1.0 | `0..1`: how much the contacted block's own friction damps the tumble on contact. `0` = the spin never decays; `1` = the full block friction. Tumble mode only. |
| `spin_roll` | 0.5 | `0..1`: how much tangential impact speed feeds the tumble on contact. `0` = no roll transfer. Tumble mode only. |

The **rotation mode** and the **rotation axis** (`spin_mode`, `spin_axis`) are preset fields (see
[Preset schema](#preset-schema-vfx_particles)) — the effect params above tune the three `0..1`
values on top of whichever preset the effect names. A model with the defaults (`spin_mode: tumble`,
`spin_axis: random`) reproduces the physical tumbling described above.

`brightness` behaves exactly like a block display's brightness (`net.minecraft.util.Brightness`):
`-1` follows the world, anything else pins that packed light (`block << 4 | sky << 20`) on the
particle's display entity. So `brightness: 15` makes the particles glow at full block+sky light even
in a pitch-black room, which is how you get readable "fireflies" or embers at night.

### How it is simulated and rendered

`VFXBlockParticleEngine` (client) integrates every particle at a fixed **1-tick step** driven by the
shared effect clock — gravity (`0.04 × gravity` per tick), air drag (`friction`), optional world
collision with surface friction and restitution (`collide`/`bounce`), lifetime and the tumble — and
uses the leftover tick fraction to interpolate the displayed position and rotation. State is kept
per running effect instance (keyed by instance id) plus one bucket for API one-shot spawns.

Each live particle owns exactly one client-side `Display.BlockDisplay` (block spec) or
`Display.ItemDisplay` (item spec) entity added to the client level. The engine writes the
interpolated position and orientation every frame, pins the old position
(`setOldPosAndRot`) so vanilla renders exactly that value, and sets the display's transformation
interpolation to one tick; the spec's `size` becomes the transform scale and its `brightness` the
display brightness override. The entity is removed when the particle dies, the effect stops or the
level changes. The block form needs a **baked block model** — a block whose world shape is drawn by
a block-entity renderer (e.g. a skull) renders nothing, so use the item form for those (the engine
warns once per such block).

### Copy-paste examples

A burst of glowing stone blocks that fall, bounce and spin (inline block mode):

```json
{
	"type": "particles",
	"particle": "block",
	"block": "minecraft:stone",
	"shape": "sphere",
	"duration": 100,
	"params": {
		"rate": 30, "radius": 1.5, "speed": 0.25, "vel_y": 0.3, "spread": 0.6,
		"brightness": 15, "gravity": 1.0, "friction": 0.96, "collide": 0.8,
		"bounce": 0.45, "size": 0.3, "life": 80, "spin": 8
	}
}
```

**Skeleton-head particles flying out of the player** (inline item mode; the preset form of the
same thing is the item preset above):

```json
{
	"type": "particles",
	"particle": "item",
	"item": "minecraft:skeleton_skull",
	"shape": "sphere",
	"duration": 200,
	"easing": "ease_out_cubic",
	"positions": [{ "entity": "@s", "point": "eyes" }],
	"params": {
		"rate": 40.0, "radius": 0.5, "speed": 0.35, "vel_y": 0.3, "spread": 0.9,
		"brightness": 15, "gravity": 0.9, "friction": 0.95, "collide": 1.0,
		"bounce": 0.35, "size": 0.45, "life": 100, "spin": 12
	}
}
```

Or through a preset id (define the file, then just name it from the effect):

```json
{ "type": "particles", "particle": "mymap:ember", "shape": "point", "loop": true,
  "params": { "rate": 20, "pos_x": { "bind": "player_x" }, "pos_y": { "bind": "player_y" }, "pos_z": { "bind": "player_z" } } }
```

## Preset schema (`vfx_particles`)

Reusable model-particle definitions live in `data/<namespace>/vfx_particles/<name>.json`, id
`<namespace>:<name>`. A `particles` effect names one with `"particle": "<namespace>:<name>"`.
Presets are client-local: they are **never synced to other players**, so a preset only exists where
its file (or registration) does.

```json
{ "block": "minecraft:stone", "brightness": [15, 15], "gravity": 0.8, "friction": 0.94,
  "collide": 1.0, "bounce": 0.2, "size": 0.35, "life": 60, "spin": 12 }
```

A preset draws a block **or** an item — exactly one of the two is required. The item form is the
reusable version of the skull example above:

```json
{ "item": "minecraft:skeleton_skull", "brightness": [15, 15], "gravity": 0.9, "friction": 0.95,
  "collide": 1.0, "bounce": 0.35, "size": 0.45, "life": 100, "spin": 12 }
```

| Field | Type | Default | Description |
|---|---|---|---|
| `block` | string | — (one of `block`/`item`) | Block state drawn by each particle, e.g. `minecraft:oak_planks` or `minecraft:oak_stairs[facing=east]`. An unknown block or property is a per-file parse error. |
| `item` | string | — (one of `block`/`item`) | Item id drawn by each particle, e.g. `minecraft:skeleton_skull` or `minecraft:diamond_sword`; the alternative to `block`. An unknown item is a per-file parse error. |
| `brightness` | int or `[blockLight, skyLight]` | -1 | `-1` = world light; `0..15` = that light level on both channels; `[b, s]` = separate block/sky levels. Block-display semantics (see the explanation in the `particles` block mode). |
| `gravity` | float | 1.0 | Downward acceleration per tick (× 0.04), `0..64` when overridden by an effect param |
| `friction` | float | 0.94 | Air drag multiplier per tick (0..1) |
| `collide` | float | 1.0 | Surface friction on contact (0..1); `0` disables world collision |
| `bounce` | float | 0.0 | Normal-velocity restitution on contact (0..1) |
| `size` | float | 0.25 | Model scale (1 = one full block) |
| `life` | int | 60 | Lifetime in ticks |
| `spin` | float | 0.0 | Rotation angular speed per tick, in degrees |
| `spin_mode` | string | `"tumble"` | Rotation model: `tumble` (physical full-3D spin, the default), `yaw` (uniform spin about one axis) or `none` (no rotation: upright, no contact response). An unknown value is a per-file parse error. |
| `spin_axis` | string or `[x, y, z]` | `"random"` | Rotation axis: `"random"`, `"x"`, `"y"`, `"z"` or a vector `[x, y, z]`. For `yaw` it is the spin axis (world Y when random/unset); for `tumble` it fixes the base axis instead of drawing one per particle. A zero-length vector is a per-file parse error. |
| `spin_random` | float | 1.0 | `0..1`: how random the initial orientation and tumble axis are (0 = strictly upright and identical for every particle) |
| `spin_friction` | float | 1.0 | `0..1`: how much the contacted block's friction damps the tumble on contact (0 = never decays) |
| `spin_roll` | float | 0.5 | `0..1`: how much tangential impact speed feeds the tumble on contact (0 = no roll transfer) |

A preset only supplies defaults: a `particles` effect that names it can still override any of the
numeric fields with the matching effect params (`brightness`, `gravity`, `friction`, `collide`,
`bounce`, `size`, `life`, `spin`, `spin_random`, `spin_friction`, `spin_roll`). This is the
`withOverrides` path: the effect's param map is laid over the base spec field by field, and only the
keys the effect actually declares are replaced. The **structural** fields — `block`/`item`,
`spin_mode`, `spin_axis` and `kind` — are not overridable, because changing them would mean
respawning every live particle.

### Rotation recipes

Three copy-paste recipes (drop each into `data/<namespace>/vfx_particles/<name>.json` and name it
from a `particles` effect with `"particle": "<namespace>:<name>"`):

**1. Tumbling cubes** — the default physical model: random orientation, end-over-end tumble about a
random axis, contact damping and roll:

```json
{ "block": "minecraft:oak_planks", "brightness": [15, 15], "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.35, "size": 0.35, "life": 80,
  "spin": 12, "spin_mode": "tumble", "spin_axis": "random",
  "spin_random": 1.0, "spin_friction": 1.0, "spin_roll": 0.5 }
```

**2. Spinning top** — a clean uniform spin about world Y (the pre-tumble behaviour), upright start,
no contact roll:

```json
{ "block": "minecraft:oak_planks", "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.1, "size": 0.35, "life": 120,
  "spin": 24, "spin_mode": "yaw", "spin_axis": "y",
  "spin_random": 0.0, "spin_friction": 0.0, "spin_roll": 0.0 }
```

**3. No rotation** — a static, strictly upright model (contact still moves it, the model just never
turns):

```json
{ "block": "minecraft:oak_planks", "gravity": 1.0, "friction": 0.94,
  "collide": 1.0, "bounce": 0.0, "size": 0.35, "life": 100,
  "spin_mode": "none" }
```

## Java API

The same registry is writable from code (the local layer) and one-shot spawns are available without
an effect instance. All of these are client-side and send no packet, so they are no-ops on a
dedicated server.

```java
// Register a block-model preset in code (survives /reload, never synced, datapack wins)
VFXAPI.registerBlockParticle(Identifier.of("mymod", "rubble"),
	VFXBlockParticleSpec.builder(Blocks.OAK_PLANKS.defaultBlockState())
		.size(0.35F).spin(12.0F).life(80).build());

// Item-model preset (the reusable skull form)
VFXAPI.registerBlockParticle(Identifier.of("mymod", "skulls"),
	VFXBlockParticleSpec.item(new ItemStack(Items.SKELETON_SKULL)));

VFXBlockParticleSpec preset = VFXAPI.blockParticle(Identifier.of("mymod", "rubble"));
VFXAPI.unregisterBlockParticle(Identifier.of("mymod", "rubble"));

// Spawn a single block particle immediately on this client
VFXAPI.spawnBlockParticle(preset, new Vec3(x, y, z), new Vec3(0.0, 0.3, 0.0));
```

`VFXAPI.registerBlockParticle(id, spec)` / `unregisterBlockParticle(id)` write and remove a local
preset, and `VFXAPI.blockParticle(id)` looks one up (datapack or local). `VFXBlockParticleSpec`
builders: `builder(BlockState)`, `builder(Item)`, `builder(ItemStack)`, `item(Item)` and
`item(ItemStack)`. `VFXAPI.spawnBlockParticle(spec, position, velocity)` spawns one particle
immediately (no effect instance, no packet). See [docs/API.md](../API.md) for the full reference.

The spark equivalents — `VFXAPI.registerSpark` / `unregisterSpark` / `spark` / `spawnSpark` — are on
the [Spark presets](particles/sparks.md) page.

## Caps and fault tolerance

- **Model particles** are capped at **2048 live particles globally**, **512 per effect instance**
  and **256 spawned per frame per effect**; emission is budgeted per instance and clamped to 1024
  particles/s. A runaway emitter cannot flood the frame. Each live particle owns one client-side
  display entity, removed when the particle dies, the effect stops or the world unloads.
- The engine tracks at most **512** effect instances and integrates at most **4** fixed tick steps
  per frame; a backlog above **8** ticks (a hitch) is dropped rather than caught up.
- The registry caps each layer at **256** presets (block/item and spark separately).
- **Per-file parse isolation**: one malformed `vfx_particles` JSON is recorded as a parse error (and
  reported by `/vfx validate`) while every other file still loads. A missing/ambiguous model, an
  unknown block state or item, an unknown `spin_mode`/`spin_axis` or a bad curve is such an error.
