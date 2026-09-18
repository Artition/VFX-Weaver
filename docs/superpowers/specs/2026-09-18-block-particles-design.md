# Block particles (custom particle type + presets) — design

Status: draft for review.

## Goal

Let the mod (and other mods, and datapacks) spawn **particles whose quad is a block's model**, with
configurable **brightness** (BlockDisplay-style light override), **physics** (gravity, friction,
world collision, bounce), size, lifetime and spin — spawnable both through the existing `particles`
effect and directly through vanilla code (`level.addParticle(...)` with our `ParticleOptions`).

## Non-goals

- No dynamic world lighting (that is Iris/Oculus territory). "Brightness" here means the particle's
  own light value, exactly like `Display.Brightness` on block displays.
- No new effect type: the `particles` effect gains the new ids/params (option chosen by the user).
- No server-authoritative spawning: the particle is client-side; a server can spawn it only when the
  mod (and therefore the type) is present there too.
- No custom textures: the particle uses the block's own model/texture.

## Design

### 1. Particle type and payload

One registered type, `vfxweaver:block`, with a payload `VFXBlockParticleOptions implements
ParticleOptions`:

| field | meaning |
|---|---|
| `BlockState block` | the block whose model is drawn (required) |
| `int brightness` | packed light override, `-1` = no override (world light), otherwise `LightTexture.pack(blockLight, skyLight)` — same convention as `Display.Brightness`; the datapack may also give `[blockLight, skyLight]` |
| `float gravity` | downward acceleration (0 = floating, 1 = vanilla-ish) |
| `float friction` | velocity retained per tick (0..1) |
| `float collide` | 0 = no world collision, >0 = collision on with that surface friction |
| `float bounce` | restitution on collision (0 = stop, 1 = no energy loss) |
| `float size` | quad scale multiplier |
| `float life` | lifetime in ticks |
| `float spin` | rotation, degrees per tick |

One type (not one per block) because Minecraft registries freeze after startup, while the payload
carries everything: **presets can be added at any time**, and `level.addParticle(options, …)` works
with the payload alone.

- Codec: vanilla-style `StreamCodec` + `MapCodec` with the block state as a `StateHolder`
  (`BlockState.CODEC`), mirroring `BlockParticleOption` so it survives the network and commands.
- `ParticleOptions.getType()` returns our type; `ParticleType` registration is per loader.

### 2. The particle

`VFXBlockParticle extends SingleQuadParticle` (the vanilla reference is the terrain/block-crumble
particle): quad = the block's particle icon model quads; `getLightColor(partialTick)` returns
`brightness >= 0 ? brightness : super.getLightColor(partialTick)`; own integration step
(`gravity`, `friction`, `spin`, lifetime) and, when `collide > 0`, world collision using the same
minimum-translation-vector depenetration the `block_chain` rope uses — extracted into a shared
helper (`VFXWorldCollision.resolve(level, pos, padding)`) so both effects use one implementation.

### 3. Registration (platform layer)

- Particle type: Fabric `Registry.register(BuiltInRegistries.PARTICLE_TYPE, id, type)`; NeoForge
  `RegisterEvent` on the mod bus. Common side, so a server with the mod knows the type too.
- Client provider: Fabric `ParticleFactoryRegistry.getInstance().register(type, provider)`;
  NeoForge `RegisterParticleProvidersEvent`. Client-only, in `client.platform`.
- All of it lives in `dev.vfxweaver.platform` / `client.platform`, guarded with
  `//? if fabric`/`//? if neoforge`; no loader import escapes the platform packages.

### 4. Presets — datapack and API

Two layers, mirroring the existing definition manager:

- **Datapack**: `data/<namespace>/vfx_particles/<name>.json`:
  ```json
  { "block": "minecraft:stone", "brightness": [15, 15], "gravity": 0.8,
    "friction": 0.94, "collide": 1.0, "bounce": 0.2, "size": 1.0, "life": 60, "spin": 12 }
  ```
  Loaded by a reload listener (client and server), bounded (256 entries), per-file parse errors
  collected and surfaced like effect-definition errors. Full id = `<namespace>:<name>`.
- **API**: `VFXAPI.registerBlockParticle(Identifier id, VFXBlockParticleOptions options)` /
  `unregisterBlockParticle(Identifier id)` — a local layer that survives reloads and server sync
  (same rule as `registerDefinitions`), plus
  `VFXAPI.blockParticle(Identifier id) -> @Nullable ParticleOptions` to fetch a preset for use with
  `level.addParticle(...)`. Datapack layer wins for the same id.

### 5. `particles` effect integration

- `"particle": "block"` + `"block": "minecraft:stone"` — inline, params override the payload
  defaults (`emissive`-like knobs are just `brightness`, `gravity`, `friction`, `collide`, `bounce`,
  `size`, `life`, `spin`).
- `"particle": "<namespace>:<preset>"` — a registered preset by id (datapack or API).
- Everything else (shape, rate, positions, bindings, aimed mode, per-frame budget) is the existing
  `particles` plumbing, unchanged.

## Verification

- All six nodes build; no Fabric behaviour changes elsewhere.
- `javap` per line for the particle/model API the class uses (`SingleQuadParticle`, the quad
  references, `ParticleEngine.register` / `RegisterParticleProvidersEvent`, `Registry`/`RegisterEvent`):
  the 1.21.9+ render-state refactor is the top porting risk, and the class must compile on
  `1.21.11`, `26.1.2` and `26.2`.
- In game (user): spawn through the effect, through a datapack preset, through an API preset, and
  through raw `level.addParticle(...)`; check brightness override (dark room), gravity/friction,
  collision (particles rest on the ground, do not sink), lifetime, and that nothing leaks when many
  effects run at once.

## Risks / open points

- **Per-line particle API** (highest): `SingleQuadParticle`/terrain-particle internals differ across
  the three lines; the implementation must be written against the active node and guarded.
- Light semantics: `brightness` follows `Display.Brightness`; a plain "emissive 0..1 blend" is
  deliberately not offered (one knob, animatable through the timeline: `-1` → packed value).
- Registry timing: the *type* registers at mod init; only *presets* can be added later.
- Bounded: preset count, per-frame particle budget (reuses the existing limits).
