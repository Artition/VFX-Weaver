# Text at play time: overriding `text.content` from a command

## Goal

Let a datapack author write one `text_overlay` definition and choose the string when the effect is
triggered, so a command block can show a line without a definition file per phrase:

```
/vfx play vfx_demos:notice text "The gate is closed" @a
```

## Dynamic text: what this unlocks, and what it does not

Once the string is an argument, the *server* decides it. That means dynamic text needs no further
feature from this mod, because vanilla already knows how to compose command arguments:

```
# ns:notice.mcfunction
$vfx play my_ns:notice text "$(msg)" @a

# anywhere else, per player
execute store result storage ns:args kills int 1 run scoreboard players get @s kills
data modify storage ns:args msg set value "$(player) closed the gate"
function ns:notice with storage ns:args
```

So: scoreboard values, player names and anything else the server can put in a storage argument work
on day one, and they arrive per player because the payload is already sent per player. This is why
the design does not grow a `%score:kills%` template language of its own - it would be a second,
worse version of macros.

Two things stay out of reach, and should be stated plainly rather than promised:

- **A string that changes while the effect runs.** The override is read once, when the instance is
  created, so a running line cannot tick from "3" to "2". Live text would need a new packet action
  and a pool key that tolerates a changing string.
- **Client-only data.** What the player is looking at, their FOV, their own GUI scale - the server
  cannot see it, so it cannot be composed into an argument.

## Non-goals

- **No server-side string sources.** No scoreboard values, entity names or selectors resolved into
  the text. That needs a formatting language, per-player resolution and a place to run it; it is a
  different feature and would dwarf this one.
- **No multi-line override.** The command language has no escape for a newline, so an override is one
  line. Multi-line text stays in the definition's `content`.
- **No runtime use of this in the public API beyond one additive overload.** Existing
  `VFXAPI.sendEffect(...)` signatures keep working unchanged and keep meaning "no override".

## What changes, and what deliberately does not

| Layer | Change | Why it is additive |
|---|---|---|
| Definition (`text` block) | none | `mode`/`font`/`align`/`anchor` still come from the file; only `content` is replaced |
| `VFXTriggerPayload` | one more `OPTIONAL_STRING` field, `textOverride` | the codec and the optional-string helper already exist (`exprParam`, `exprSource`); the field is `null` unless a command asked for it |
| Wire protocol | `PROTOCOL_VERSION` 6 → 7 | a stale client would misparse the extra field, and this repo already refuses mismatched versions |
| `/vfx play`, `/vfx playat`, `/vfx playentity` | one new literal branch `text "<string>"` | existing branches keep their exact shapes |
| `VFXAPI` | new overloads that take `@Nullable String text` | old signatures delegate with `null`; nothing is removed or reordered |
| Renderers | none | both paths already read `spec.content()` |

## Semantics

1. The override replaces `content` and nothing else. `mode`, `font`, `align`, `anchor` still decide
   *how* the string is drawn, because they are layout decisions an author should not repeat on every
   call.
2. A definition without a `text` block ignores the override and logs one warning per effect id: there
   is no mode to draw in, and inventing a default would hide an authoring mistake.
3. An empty or blank override is treated as "not given", so `text ""` cannot blank a definition by
   accident.
4. The override is per target, not broadcast: the payload is already sent per player, so two players
   can be shown different strings by two calls.
5. Everything animated keeps working - `reveal`, `exit`, `exit_at`, `scale`, `pos_*` and the rest are
   params and are untouched by this feature.
6. The wire cap is `MAX_TEXT_OVERRIDE = 512` characters; longer input is cut and logged once. The
   renderer's own 256-glyph cap still applies afterwards, so a 512-character override still draws at
   most 256 characters.

## Command shape

```
/vfx play     <effect> text "<string>"                        <targets>
/vfx play     <effect> text "<string>" <params>               <targets>
/vfx playat   <effect> <pos> text "<string>"                  <targets>
/vfx playat   <effect> <pos> text "<string>" <params>         <targets>
/vfx playentity <effect> text "<string>" <targets> [<players>]
```

The literal `text` sits where `params` sits today, so nothing is ambiguous: `{` still starts params,
and a quoted string can only start after the literal.

## Where the override is applied

One place, the same one that already flattens the spec into the instance
(`VFXEffectManager`, the `new VFXActiveEffect(...)` call): if the payload carries a non-blank
override and the definition has a text spec, build the instance from `spec.withContent(override)`.
A `withContent` on `VFXTextSpec` keeps the other four fields by construction, so no caller can
forget one.

## Failure modes

- Effect id unknown → the existing error, unchanged.
- `text` used on a non-text effect → the override travels, nothing reads it, one warning at most.
- Client and server at different protocol versions → the existing version guard rejects the packet,
  as it already does for any format change.
- Overrides longer than the cap → cut, logged once per effect id.

## Verification

- `scripts/check-text-overlay.ps1` grows literal patterns for `textOverride`, `withContent` and the
  command's `text` branch, so the wiring cannot be silently dropped later.
- Builds for `26.2`, `26.1.2`, `1.21.11`, `26.2-neoforge`.
- In game: one definition, three different `text` calls, plus a call with no override; and a
  multi-line definition to confirm only `content` is replaced.
- Protocol bump verified by launching a client and a server from the same build (the only case that
  can be checked headless is that the constant moved).
