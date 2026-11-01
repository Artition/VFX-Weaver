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

## Done means

`text_overlay` follows a played-at position and an entity anchor, `text "<string>"` overrides the
content per call without breaking any old signature, and `scramble` turns both transitions into the
decode effect - with the guide and changelog saying exactly that, and **Not verified in game** on
every claim about how it looks.
