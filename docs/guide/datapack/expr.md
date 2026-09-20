# Expressions (`expr`)

`type: expression` — a param can be computed from a formula:

```jsonc
"params": {
	"intensity": { "expr": "abs(sin(t * 0.1)) * 0.8 + random() * 0.2" }
}
```

The string is compiled to an AST **once** when the effect instance is created and evaluated every
frame - the source is never re-parsed. `random()` and `noise()` are deterministic per instance, so
each instance gets its own variation.

An expression is available anywhere a param spec is:

- a param: `"radius": { "expr": "8 * sin(t)" }`;
- a graph node: `{ "kind": "expr", "inputs": { "expr": "t * 2.0" } }` (source ≤ 1024 chars);
- a live edit: `VFXAPI.sendSetParamExpr(player, effect, "radius", "1.5 + 0.5*sin(t/10)")`.

## Variables

| Name | Meaning |
|---|---|
| `t` | Ticks since the effect started |
| `x` / `y` / `z` | World/camera coordinates |
| `pi`, `e` | Constants |
| `health` | Local player's health fraction (health / max) |
| `hunger` | Saturation fraction (food / 20) |
| `speed` | Horizontal speed in blocks/s |
| `light_level` | Light level at the player's position (block/sky light / 15) |
| `time_of_day` | Fraction of the day cycle (0 = sunrise) |
| `player_x` / `player_y` / `player_z` | Local player's world position |

On a dedicated server (or with no player state) the player variables evaluate to `NaN`.

## Functions

| Function | Arity | Result |
|---|---|---|
| `sin(x)`, `cos(x)`, `tan(x)` | 1 | Trigonometric |
| `atan(y)` / `atan(y, x)` | 1 or 2 | `atan` / `atan2` |
| `abs(x)` | 1 | Absolute value |
| `sign(x)` | 1 | −1, 0 or 1 |
| `floor(x)`, `ceil(x)`, `round(x)` | 1 | Rounding |
| `fract(x)` | 1 | Fractional part (`x − floor(x)`) |
| `sqrt(x)` | 1 | Square root |
| `pow(a, b)` | 2 | `a` raised to `b` |
| `exp(x)` | 1 | `e^x` |
| `log(x)` | 1 | Natural logarithm |
| `mod(a, b)` | 2 | Positive modulo (like GLSL); `b == 0` → 0 |
| `min(a, b)`, `max(a, b)` | 2 | Minimum / maximum |
| `clamp(x, lo, hi)` | 3 | `x` clamped into `lo..hi` |
| `lerp(a, b, t)` / `mix(a, b, t)` | 3 | `a + (b − a) × t` |
| `step(edge, x)` | 2 | 0 when `x < edge`, else 1 |
| `smoothstep(e0, e1, x)` | 3 | Smooth 0→1 transition between `e0` and `e1` |
| `random()` | 0 | 0..1, deterministic per instance seed |
| `noise(x, y, z)` | 3 | 3D simplex noise, roughly −1..1 |

## Operators

Binary `+`, `-`, `*`, `/`; unary `-` and `+`; parentheses; whitespace is ignored. There is no `%` or
`^` operator - use `mod(a, b)` and `pow(a, b)`.

```jsonc
"intensity": { "expr": "(1 - health) * clamp(sin(t * 0.2), 0, 1)" }
"radius":    { "expr": "4 + 3 * noise(x * 0.1, y * 0.1, z * 0.1)" }
"seed":      { "expr": "floor(t * 8) * 0.1" }
```

## Errors

- Argument counts are validated **when the expression is compiled**, naming the function and the
  expected count.
- An unknown function or variable, or an unparseable source, makes the whole definition a per-file
  parse error; at runtime a failed compile falls back to `0`.
- The source is capped at **4096 characters** (a very deep parenthesis nest is rejected rather than
  overflowing the parser stack).
