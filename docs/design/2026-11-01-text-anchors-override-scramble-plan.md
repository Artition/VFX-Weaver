# Plan: text anchors, call-time text, and the scramble transitions

Three tasks, in this order. Each ends with its own build, its own guard run and its own commit;
nothing visual is claimed until it has been looked at in game.

## Task A - the block follows `playat`, `move` and entity anchors

**File:** `src/client/java/dev/vfxweaver/client/render/VFXTextOverlayRenderer.java`

1. In `renderWorld`, replace the `new Vec3(pos_x, pos_y, pos_z)` origin with
   `VFXWorldOverlayRenderer.effectPositions(effect, level)` (same package, already public-package
   static). Take the first slot.
2. If the resolver returns an empty list (an entity anchor whose entity is untracked this frame),
   return without drawing - the same rule every other world effect follows.
3. With a slot present, treat `pos_x/pos_y/pos_z` as an offset **from the slot**, so
   `pos_y: 2` means "two blocks above whatever block this was played at". With no slot, keep
   today's absolute behaviour, so no existing definition moves.
4. Several slots: use the first and say so in the guide. One pool per slot would mean one display set
   per slot; that is a separate task.
5. Guide: a short paragraph under the positioning params; changelog bullet.

**Checks:** `:26.2`, `:26.1.2`, `:26.2-neoforge` builds, `scripts/check-text-overlay.ps1`.

## Task B - text content from the command

Contract: `docs/design/2026-11-01-text-override-design.md`.

1. `VFXTriggerPayload`: add `@Nullable String textOverride` as the last component, encoded with the
   existing `OPTIONAL_STRING`, bump `PROTOCOL_VERSION` to 7, thread the field through every
   `play`/`playAt`-style factory with a `null` where the caller does not care.
2. `VFXAPI`: overloads of `sendEffect` that take `@Nullable String text`; the old signatures delegate
   with `null`. Nothing removed, nothing reordered.
3. Command: a `text "<string>"` literal branch on `play`, `playat` and `playentity`, in the position
   `params` occupies today (`{` still starts params, a quoted string can only follow the literal).
   Cap the string at `MAX_TEXT_OVERRIDE = 512` characters, cut and warn once past that.
4. `VFXTextSpec.withContent(String)`: same mode/font/align/anchor, other content.
5. Apply in the one place that builds an instance (`VFXEffectManager`, the `new VFXActiveEffect(...)`
   call): non-blank override plus a text spec means `spec.withContent(override)`; a definition
   without a text block ignores it and logs one warning.
6. Docs: guide section "Setting the text at play time" with the macro example, changelog bullet,
   guard patterns for `textOverride`, `withContent` and the command literal.

**Checks:** all four nodes build (the payload is shared), guard, schema check.

## Task C - the scramble transitions

**Files:** `src/main/java/dev/vfxweaver/editor/VFXEffectSchema.java`,
`src/client/java/dev/vfxweaver/client/render/VFXTextOverlayRenderer.java`

1. Params: `scramble` (0..1 switch, bounded), `scramble_rate` (swaps per second, default 15, no upper
   limit), `scramble_band` (characters ahead of the reveal frontier, default 4, no upper limit),
   `scramble_time` (seconds before detachment on exit, default 0.35, no upper limit).
2. Noise glyph: hash of (instance id, character index, swap number) into the pool
   `A-Z0-9#$%&*+=-<>/?`. Deterministic, so a replay and a screenshot agree.
3. Reveal: characters between the frontier and `scramble_band` ahead of it show noise, with opacity
   rising towards the frontier; the character snaps to itself when the frontier passes it. Computed
   from the `reveal` param alone - no history.
4. Exit: during the `scramble_time` seconds before `detachTime`, the character shows noise, then
   leaves by whichever exit is set.
5. Both paths: 3D updates `setText` only when the noise glyph actually changes; 2D draws the noise
   character instead of its own, at no extra cost.
6. Docs: guide section with a JSON example, changelog bullet, guard patterns.

**Checks:** all four nodes build, guard, schema check, and the existing demo keeps parsing.

## Task E - real physics for the fall

The analytic fall has been replaced piecemeal: a cached floor per column, a landing lift, a frozen
spin, a forced tip-to-flat. Each of those was a patch over the same mistake - a rigid body cannot be
described by a closed-form offset once it has to interact with geometry. The block-particle engine
already models exactly what a text character needs, because a character is a flat rectangle and a
particle is a cube.

**Model.** One body per unit, in world space:

| field | meaning |
|---|---|
| `pos`, `prev` | position, and where it was at the start of the tick |
| `velocity` | blocks per tick |
| `orientation`, `prevOrientation` | full rotation, slerped for display |
| `angularVelocity` | radians per tick about a body-fixed axis |
| `resting` | true once the body has settled and the physics stops touching it |

**Step.** Once per client tick, 20 Hz, mirroring `VFXBlockParticleEngine.integrate`:

1. `velocity.y -= GRAVITY_STEP * gravity`;
2. `velocity *= friction` (the same tangential damping the engine applies);
3. `next = pos + velocity`;
4. with `collision` on, `VFXWorldCollision.resolve(level, next, padding, prev)`; on contact drop the
   inward normal component of the velocity, damp what is left by `friction`, reflect the normal part
   by `bounce`, and turn the tangential slip into roll about `n x v_t` exactly as the engine does;
5. `pos = next`, then integrate the orientation about the angular-velocity axis.

**Rest.** When both the linear and the angular speed fall under a small threshold for a few
consecutive ticks, the body snaps its orientation to the flat, face-up one and stops being stepped.
That is what "lies on the floor" means; no `settle`, no `flat_land`, no cached floor.

**What goes away.** The per-column floor scan, the landing lift, the frozen spin, `settle`,
`flat_land`, and the special case for a free fall - with no collision the body simply keeps being
stepped until the effect stops, which is the honest version of "falls through the world".

**What stays.** `exit_at`/`exit_stagger` decide when a body detaches and gets its initial state,
`gravity`, `spread`, `bounce`, `friction`, `collision`, `spin_*` and `tumble` seed and shape that
state, and `hold`/`fade_out` still drive the fade after the body comes to rest.

**Determinism, stated honestly.** The frame is no longer a closed-form function of the effect clock:
state accumulates per tick, so a replay from the effect's start matches frame for frame, while
jumping into the middle of a run does not reproduce the same resting arrangement. The block particles
already live with this, and the guide must say it instead of promising closed-form everywhere.

---

## Done means

`text_overlay` follows a played-at position and an entity anchor, `text "<string>"` overrides the
content per call without breaking any old signature, and `scramble` turns both transitions into the
decode effect - with the guide and changelog saying exactly that, and **Not verified in game** on
every claim about how it looks.
