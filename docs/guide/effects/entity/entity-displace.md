# entity_displace

`type: "entity_displace"`

Displaces the target entity's model vertices themselves (rendered with the vanilla body material, so lighting/shadows stay normal) - the body tears/glitches, no ghost copy on top.

## Fields

| Field | Type | Default | Meaning |
|---|---|---|---|
| `amplitude` | float | 0.1 (fades to 0) | Max displacement in blocks (0..2) |
| `scale` | float | 4 | Field detail: higher = neighbours diverge more (0.5..32) |
| `seed` | float | 0 | Random phase; step it (`expr: "floor(t*8)*0.1"`) for 8x/s snaps, animate for smooth morphing |


Targets are set via `/vfx playentity <effect> <selector>`, via the Java API (see [Java API](../../../API.md)) or via the `entity_selector` field in the definition (then `/vfx play <effect>` is enough - the server finds the targets itself). One effect can target up to 16 entities; several effects can hang on one entity. On the first-person hand the local player's own effects are rendered too (`through_blocks` is ignored there - the hand always draws on top).

## Example

```json
{
	"type": "entity_displace",
	"duration": 60,
	"params": { "amplitude": 0.1, "scale": 4, "seed": 0 }
}
```

```
/vfx playentity vfxweaver:entity_displace @e[type=zombie,limit=1] {[amplitude:0.2],[scale:6]}
```
