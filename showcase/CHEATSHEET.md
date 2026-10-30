## Recording order (go top-down)

One take per line, in this order. Run `/vfx stop @s` between takes so instances do not stack.

Motion-dependent takes (`show_motion_blur`, `show_afterimage`) need a moving subject: before them run
`/function vfx_demos:scene/minecart_clear` then `/function vfx_demos:scene/minecart_setup`, and after them go back with `/function vfx_demos:scene/setup`.

Sky takes need the sky in frame: the two `/tp` lines at line 38 point the camera north and slightly
up (horizon in frame); the `/tp` line after line 44 puts the camera back at the scene. `show_sky_anchor_sun`
is a **day** take and `show_sky_anchor_moon` / `show_sky_anchor_stars` are **night** takes - the
`/time set` lines mark the changes. The fog take looks north over the open edge, so line 73 has its own `/tp`.

```
/reload
/function vfx_demos:scene/setup
 1. /vfx play vfx_demos:show_afterimage
    /vfx stop @s
 2. /vfx play vfx_demos:show_bloom
    /vfx stop @s
 3. /vfx play vfx_demos:show_blur
    /vfx stop @s
 4. /vfx play vfx_demos:show_chromatic_aberration
    /vfx stop @s
 5. /vfx play vfx_demos:show_color_grade
    /vfx stop @s
 6. /vfx play vfx_demos:show_dent
    /vfx stop @s
 7. /vfx play vfx_demos:show_dent_field_demo
    /vfx stop @s
 8. /vfx play vfx_demos:show_depth_of_field
    /vfx stop @s
 9. /vfx play vfx_demos:show_digital_glitch
    /vfx stop @s
10. /vfx play vfx_demos:show_distortion
    /vfx stop @s
11. /vfx play vfx_demos:show_double_vision
    /vfx stop @s
12. /vfx play vfx_demos:show_eyelids
    /vfx stop @s
13. /vfx play vfx_demos:show_film_grain
    /vfx stop @s
14. /vfx play vfx_demos:show_gradient_map
    /vfx stop @s
15. /vfx play vfx_demos:show_graph_demo
    /vfx stop @s
16. /vfx play vfx_demos:show_graph_logic_demo
    /vfx stop @s
17. /vfx play vfx_demos:show_hue_isolation
    /vfx stop @s
18. /vfx play vfx_demos:show_invert
    /vfx stop @s
19. /vfx play vfx_demos:show_iris_wipe
    /vfx stop @s
20. /vfx play vfx_demos:show_letterbox
    /vfx stop @s
21. /vfx play vfx_demos:show_motion_blur
    /vfx stop @s
22. /vfx play vfx_demos:show_noise_warp
    /vfx stop @s
23. /vfx play vfx_demos:show_pixelate
    /vfx stop @s
24. /vfx play vfx_demos:show_posterize
    /vfx stop @s
25. /vfx play vfx_demos:show_scanlines
    /vfx stop @s
26. /vfx play vfx_demos:show_screen_flash
    /vfx stop @s
27. /vfx play vfx_demos:show_shockwave
    /vfx stop @s
28. /vfx play vfx_demos:show_slice_shift
    /vfx stop @s
29. /vfx play vfx_demos:show_solarize
    /vfx stop @s
30. /vfx play vfx_demos:show_speed_lines
    /vfx stop @s
31. /vfx play vfx_demos:show_stop_motion
    /vfx stop @s
32. /vfx play vfx_demos:show_surface_pattern
    /vfx stop @s
33. /vfx play vfx_demos:show_surface_pattern_texture
    /vfx stop @s
34. /vfx play vfx_demos:show_tint_field_demo
    /vfx stop @s
35. /vfx play vfx_demos:show_vhs
    /vfx stop @s
36. /vfx play vfx_demos:show_vignette
    /vfx stop @s
37. /vfx play vfx_demos:show_vortex
    /vfx stop @s
    /tp @s 2000 100 2000 180 -12
    /time set day
38. /vfx play vfx_demos:show_sky_mask
    /vfx stop @s
39. /vfx play vfx_demos:show_dome_mask
    /vfx stop @s
40. /vfx play vfx_demos:show_sky_pattern
    /vfx stop @s
41. /vfx play vfx_demos:show_sky_pattern_texture
    /vfx stop @s
42. /vfx play vfx_demos:show_sky_anchor_sun
    /vfx stop @s
    /time set midnight
43. /vfx play vfx_demos:show_sky_anchor_moon
    /vfx stop @s
44. /vfx play vfx_demos:show_sky_anchor_stars
    /vfx stop @s
    /time set day
    /tp @s 2000 100 1994 0 0
45. /vfx play vfx_demos:show_mask_screen_demo
    /vfx stop @s
46. /vfx play vfx_demos:show_mask_custom_glsl_demo
    /vfx stop @s
47. /vfx play vfx_demos:show_mask_custom_glsl_aura_demo
    /vfx stop @s
48. /vfx play vfx_demos:show_mask_custom_glsl_surface_demo
    /vfx stop @s
49. /vfx play vfx_demos:show_mask_custom_data_demo
    /vfx stop @s
50. /vfx play vfx_demos:show_mask_block_demo
    /vfx stop @s
51. /vfx play vfx_demos:show_mask_block_xray_demo
    /vfx stop @s
52. /vfx play vfx_demos:show_mask_custom_demo
    /vfx stop @s
53. /vfx play vfx_demos:show_mask_entity_demo
    /vfx stop @s
54. /vfx play vfx_demos:show_mask_world_demo
    /vfx stop @s
55. /vfx play vfx_demos:show_mask_pulse_demo
    /vfx stop @s
56. /vfx play vfx_demos:show_block_tint
    /vfx stop @s
57. /vfx play vfx_demos:show_block_outline
    /vfx stop @s
58. /vfx play vfx_demos:show_light_beam
    /vfx stop @s
59. /vfx play vfx_demos:show_light_beam_dir
    /vfx stop @s
60. /vfx play vfx_demos:show_light_beam_end_at
    /vfx stop @s
61. /vfx play vfx_demos:show_pulse_ring
    /vfx stop @s
62. /vfx play vfx_demos:show_guide_line
    /vfx stop @s
63. /vfx play vfx_demos:show_particles
    /vfx stop @s
64. /vfx play vfx_demos:show_ember
    /vfx stop @s
65. /vfx play vfx_demos:show_sparks
    /vfx stop @s
66. /vfx play vfx_demos:show_block_chain
    /vfx stop @s
67. /vfx play vfx_demos:show_entity_tint
    /vfx stop @s
68. /vfx play vfx_demos:show_entity_outline
    /vfx stop @s
69. /vfx play vfx_demos:show_entity_displace
    /vfx stop @s
70. /vfx play vfx_demos:show_camera_shake
    /vfx stop @s
71. /vfx play vfx_demos:show_camera_roll
    /vfx stop @s
72. /vfx play vfx_demos:show_fov_modifier
    /vfx stop @s
    /tp @s 2000 100 2000 180 0
73. /vfx play vfx_demos:show_fog_modifier
    /vfx stop @s
74. /vfx play vfx_demos:show_collection
    /vfx stop @s
75. /vfx play vfx_demos:show_datamosh
    /vfx stop @s
76. /vfx play vfx_demos:show_torn_edge
    /vfx stop @s
77. /vfx play vfx_demos:show_spectral
    /vfx stop @s
78. /vfx play vfx_demos:show_hex_grid
    /vfx stop @s
79. /vfx play vfx_demos:show_heartbeat
    /vfx stop @s
80. /vfx play vfx_demos:show_hum
    /vfx stop @s
81. /vfx play vfx_demos:show_noise
    /vfx stop @s
82. /vfx play vfx_demos:show_volume_duck
    /vfx stop @s
```


# VFX Weaver showcase - recording cheat sheet

Datapack: `vfx_demos` (world `New World`), namespace `vfx_demos:`.
Instance: `26.2fabric`. Effects: `data/vfx_demos/vfx/*.json` (82 `show_*` definitions).
Scene functions: `data/vfx_demos/function/scene/setup.mcfunction` and `scene/clear.mcfunction`.

## Before recording

1. Load the world, then run `/reload` (loads the pack; `/vfx validate vfx_demos` should report no broken files).
2. Build the scene and get placed: `/function vfx_demos:scene/setup`
   - Flat 13x13 smooth-stone platform at **x 1994..2006, y 99, z 1994..2006**; a white-concrete
     wall (x 1994..2006, y 100..104, z 2006); a red 3-high pillar at x 1996, z 1996; a blue block
     at x 2004, z 1996; a villager tagged `vfx_showcase` at (2000, 100, 2000).
   - You are teleported to **(2000, 100, 1994)** facing south (+Z) - straight at the wall, villager ahead.
3. Play a showcase: `/vfx play vfx_demos:show_<name>` (see table).
4. Between takes: `/vfx stop @s` (fades out over the effect's `fade_ticks`), then play the next.
5. When done: `/function vfx_demos:scene/clear` (stops effects, removes the villager, clears the build).

Every showcase is `loop`+`persistent`, so it runs until stopped. Loop points are seamless
(keyframes start and end at the same value); a 6-10 s capture loops cleanly.

Commands need operator rights. Distances are from the teleport spot: wall ~12 blocks, villager ~6.

**Camera advice column:** still = stand still (screen effects); entity = look at the villager;
points = look at the marked blocks/beams. For the moving effects (motion blur, afterimage,
stop motion, mask pulse) a slow camera move is part of the shot. Sky = look north so the sky and the
horizon are both in frame.

---

## Screen post-processing (40)

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:afterimage` | `/vfx play vfx_demos:show_afterimage` | Echo trails smear behind movement, dissolving | still, then pan slowly right and back | 8 s |
| `vfxweaver:bloom` | `/vfx play vfx_demos:show_bloom` | Bright areas glow softly, growing then easing off | still, look at the white wall | 8 s |
| `vfxweaver:blur` | `/vfx play vfx_demos:show_blur` | Whole frame softens and refocuses (**re-record**: the kernel is now a fixed 12-tap separable Gaussian, so an animated radius no longer pops) | still | 8 s |
| `vfxweaver:chromatic_aberration` | `/vfx play vfx_demos:show_chromatic_aberration` | RGB fringing at the screen edges, gentle in/out | still | 8 s |
| `vfxweaver:color_grade` | `/vfx play vfx_demos:show_color_grade` | Saturation drains to near-grey and returns | still | 8 s |
| `vfxweaver:dent` | `/vfx play vfx_demos:show_dent` | A local lens warp that drifts across the view | still | 8 s |
| `vfxweaver:dent_field_demo` | `/vfx play vfx_demos:show_dent_field_demo` | Noise-mottled lens warp, size pulses | still | 10 s |
| `vfxweaver:depth_of_field` | `/vfx play vfx_demos:show_depth_of_field` | Tilt-shift band sweeps down through the scene | still | 8 s |
| `vfxweaver:digital_glitch` | `/vfx play vfx_demos:show_digital_glitch` | Rare, soft band tears with faint RGB split | still | 10 s |
| `vfxweaver:distortion` | `/vfx play vfx_demos:show_distortion` | Whole frame lens warps (pincushion to barrel) | still | 8 s |
| `vfxweaver:double_vision` | `/vfx play vfx_demos:show_double_vision` | Two ghost copies drift apart and back | still | 8 s |
| `vfxweaver:eyelids` | `/vfx play vfx_demos:show_eyelids` | Soft lids close to a narrow slit and reopen | still | 10 s |
| `vfxweaver:film_grain` | `/vfx play vfx_demos:show_film_grain` | Fine animated grain fades in and out | still | 8 s |
| `vfxweaver:gradient_map` | `/vfx play vfx_demos:show_gradient_map` | Frame grades into a dark-to-warm two-colour map | still | 8 s |
| `vfxweaver:graph_demo` | `/vfx play vfx_demos:show_graph_demo` | Blur pulse driven by a time -> curve graph | still | 10 s |
| `vfxweaver:graph_logic_demo` | `/vfx play vfx_demos:show_graph_logic_demo` | Blur ramps up, then holds off (logic subgraph) | still | 8 s |
| `vfxweaver:hue_isolation` | `/vfx play vfx_demos:show_hue_isolation` | One hue survives, the rest greys out, hue slides | still | 10 s |
| `vfxweaver:invert` | `/vfx play vfx_demos:show_invert` | Colours invert to near full and back | still | 10 s |
| `vfxweaver:iris_wipe` | `/vfx play vfx_demos:show_iris_wipe` | A circular iris closes to a small hole and opens | still | 8 s |
| `vfxweaver:letterbox` | `/vfx play vfx_demos:show_letterbox` | Cinema bars slide in and out | still | 8 s |
| `vfxweaver:motion_blur` | `/vfx play vfx_demos:show_motion_blur` | Directional blur while the camera turns | pan slowly left-right | 8 s |
| `vfxweaver:noise_warp` | `/vfx play vfx_demos:show_noise_warp` | Soft fluid value-noise warping, drifting | still | 8 s |
| `vfxweaver:pixelate` | `/vfx play vfx_demos:show_pixelate` | Image coarsens into big pixels and clears | still | 10 s |
| `vfxweaver:posterize` | `/vfx play vfx_demos:show_posterize` | Colours quantise toward flat bands and return | still | 8 s |
| `vfxweaver:scanlines` | `/vfx play vfx_demos:show_scanlines` | Faint CRT bands drift down, low visibility | still | 8 s |
| `vfxweaver:screen_flash` | `/vfx play vfx_demos:show_screen_flash` | A gentle warm glow rises and fades (max alpha 0.16) | still | 10 s |
| `vfxweaver:shockwave` | `/vfx play vfx_demos:show_shockwave` | A single soft refraction ring expands from centre | still | 8 s |
| `vfxweaver:slice_shift` | `/vfx play vfx_demos:show_slice_shift` | The frame splits and the halves slide apart, tilted | still | 8 s |
| `vfxweaver:solarize` | `/vfx play vfx_demos:show_solarize` | Bright pixels invert; threshold sweeps | still | 8 s |
| `vfxweaver:speed_lines` | `/vfx play vfx_demos:show_speed_lines` | Radial speed lines converge and fade | still | 8 s |
| `vfxweaver:stop_motion` | `/vfx play vfx_demos:show_stop_motion` | Picture holds at a low frame rate; fps eases up | walk forward slowly | 8 s |
| `vfxweaver:surface_pattern` | `/vfx play vfx_demos:show_surface_pattern` | Cyan ring spins and breathes on the floor | still, look down at the floor | 10 s |
| `vfxweaver:surface_pattern` (texture) | `/vfx play vfx_demos:show_surface_pattern_texture` | An ender-eye item texture rotates on the wall | still, look at the wall | 10 s |
| `vfxweaver:tint_field_demo` | `/vfx play vfx_demos:show_tint_field_demo` | Big noise patches tint red/cyan, gently shifting | still | 10 s |
| `vfxweaver:vhs` | `/vfx play vfx_demos:show_vhs` | Worn-tape wobble and a slow tracking band | still | 10 s |
| `vfxweaver:vignette` | `/vfx play vfx_demos:show_vignette` | Edges darken and lighten | still | 8 s |
| `vfxweaver:vortex` | `/vfx play vfx_demos:show_vortex` | A gentle twist around screen centre, both directions | still | 8 s |

### Sky (7) - the sky dome and the celestial anchors

Record these with the sky in frame (`/tp @s 2000 100 2000 180 -12`, horizon low in view). The anchors
track the vanilla sun/moon/stars, so set the time on the `/time set` lines first.

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| (mask `sky`) | `/vfx play vfx_demos:show_sky_mask` | The whole sky tints green, the tint breathing in and out | look north, sky + horizon | 10 s |
| (mask `space:"dome"`) | `/vfx play vfx_demos:show_dome_mask` | A red glow disc on the sky grows and shrinks | pitch up at one spot | 10 s |
| `vfxweaver:sky_pattern` (patch) | `/vfx play vfx_demos:show_sky_pattern` | One cyan ring decal fixed on a spot of the sky, breathing | look north at that spot | 10 s |
| `vfxweaver:sky_pattern` (fill) | `/vfx play vfx_demos:show_sky_pattern_texture` | A nether-portal texture tiles the whole sky, rotating | look north, sky in frame | 10 s |
| `vfxweaver:sky_pattern` (`anchor:"sun"`) | `/vfx play vfx_demos:show_sky_anchor_sun` | A gold ring locked onto the vanilla sun, fading in/out | day, look at the sun | 10 s |
| `vfxweaver:sky_pattern` (`anchor:"moon"`) | `/vfx play vfx_demos:show_sky_anchor_moon` | A pale ring locked onto the vanilla moon | midnight, look at the moon | 10 s |
| `vfxweaver:sky_pattern` (`anchor:"stars"`) | `/vfx play vfx_demos:show_sky_anchor_stars` | A star texture **locked to the star field** - it stays put relative to the stars as they rotate | midnight, look up | 10 s |

### Masks (11) - `color_grade`/`invert` limited to a region

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:mask_screen_demo` | `/vfx play vfx_demos:show_mask_screen_demo` | Invert inside a screen rectangle minus a circle | still | 10 s |
| `vfxweaver:mask_custom_glsl_demo` | `/vfx play vfx_demos:show_mask_custom_glsl_demo` | Cyan ring with 8 lobes, tint inside it | still | 10 s |
| (mask GLSL plugin - world aura) | `/vfx play vfx_demos:show_mask_custom_glsl_aura_demo` | Three amber blobs around the villager in `aura` mode: a **real filled 3D volume** (air and all) that breathes on `p1` | stand outside, look at the villager | 10 s |
| (mask GLSL plugin - world surface) | `/vfx play vfx_demos:show_mask_custom_glsl_surface_demo` | The same world blobs in `surface` mode: only **geometry inside them** is tinted, and the sky is **never painted** | look at the wall, sky above | 10 s |
| (mask GLSL plugin - dynamic data) | `/vfx play vfx_demos:show_mask_custom_data_demo` | Three cyan blobs **moving** as `mask.p0.d<J>` animates - the data drives the geometry | still, look at the blobs | 12 s |
| `vfxweaver:mask_block_demo` | `/vfx play vfx_demos:show_mask_block_demo` | The scene blocks tint blue, sphere grows | look at pillar/blue block | 10 s |
| `vfxweaver:mask_block_xray_demo` | `/vfx play vfx_demos:show_mask_block_xray_demo` | Same blocks tint orange see-through (x-ray) | look at pillar/blue block | 10 s |
| `vfxweaver:mask_custom_demo` | `/vfx play vfx_demos:show_mask_custom_demo` | A ringed volume SDF tints amber, pulsing | look at the villager | 10 s |
| `vfxweaver:mask_entity_demo` | `/vfx play vfx_demos:show_mask_entity_demo` | Red aura sphere around the villager plus a screen rect | look at the villager | 10 s |
| `vfxweaver:mask_world_demo` | `/vfx play vfx_demos:show_mask_world_demo` | Blue surface-only sphere on things around the villager | look at the villager | 10 s |
| `vfxweaver:mask_pulse_demo` | `/vfx play vfx_demos:show_mask_pulse_demo` | Red aura whose radius tracks your distance | walk toward/away from the villager | 10 s |

---
| `vfxweaver:datamosh` | `/vfx play vfx_demos:show_datamosh` | Old pixels are dragged along the movement and the frame breaks into blocks of stale content, then refreshes | still, then pan slowly so something moves | 8 s |
| `vfxweaver:torn_edge` | `/vfx play vfx_demos:show_torn_edge` | The frame splits along a jagged line and the two sides pull apart under a glowing edge | still, look at the wall | 8 s |
| `vfxweaver:spectral` | `/vfx play vfx_demos:show_spectral` | The picture goes through a seven-stop prism ramp (duotone by default) | still, look at the wall | 8 s |
| `vfxweaver:hex_grid` | `/vfx play vfx_demos:show_hex_grid` | A triangular lattice over the frame, its lines coloured by screen position | still | 8 s |

## World overlays (11)

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:block_tint` | `/vfx play vfx_demos:show_block_tint` | Blue translucent fill over the pillar + blue block | look at the two blocks | 8 s |
| `vfxweaver:block_outline` | `/vfx play vfx_demos:show_block_outline` | Gold outline grows/thickens on the two blocks | look at the two blocks | 8 s |
| `vfxweaver:light_beam` | `/vfx play vfx_demos:show_light_beam` | Warm light shaft above the pillar, widening/brightening | look slightly up at the pillar | 8 s |
| `vfxweaver:light_beam` (`dir_*`) | `/vfx play vfx_demos:show_light_beam_dir` | The shaft tilts and sweeps sideways (arbitrary direction) | look at the pillar | 10 s |
| `vfxweaver:light_beam` (`end_at_*`) | `/vfx play vfx_demos:show_light_beam_end_at` | A shaft drops out of the sky and its tip lands on the blue block | look at the blue block | 10 s |
| `vfxweaver:pulse_ring` | `/vfx play vfx_demos:show_pulse_ring` | Orange ring expands from the villager and recedes | look at the villager | 8 s |
| `vfxweaver:guide_line` | `/vfx play vfx_demos:show_guide_line` | Dashed green arc between the two blocks, bowing | look at the two blocks | 8 s |
| `vfxweaver:particles` | `/vfx play vfx_demos:show_particles` | Golden dust helix around the villager, breathing | look at the villager | 8 s |
| `vfxweaver:ember` | `/vfx play vfx_demos:show_ember` | Small ember spark sphere at the villager, pulsing | look at the villager | 8 s |
| `vfxweaver:sparks` | `/vfx play vfx_demos:show_sparks` | Dense ember sparks burst around the villager | look at the villager | 8 s |
| `vfxweaver:block_chain` | `/vfx play vfx_demos:show_block_chain` | Iron-chain blocks sag and swing between two anchors | look at the pillar/blue block | 8 s |

---

## Entity effects (3) - target the tagged villager (`entity_selector`)

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:entity_tint` | `/vfx play vfx_demos:show_entity_tint` | Villager fills blue, colour shifts to red, back | look at the villager | 8 s |
| `vfxweaver:entity_outline` | `/vfx play vfx_demos:show_entity_outline` | Gold silhouette rim thickens and fades | look at the villager | 8 s |
| `vfxweaver:entity_displace` | `/vfx play vfx_demos:show_entity_displace` | Villager's model vertices tear/displace gently | look at the villager | 8 s |

---

## Camera and misc (4)

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:camera_shake` | `/vfx play vfx_demos:show_camera_shake` | Low-amplitude noise shake (softened from default) | stand still | 8 s |
| `vfxweaver:camera_roll` | `/vfx play vfx_demos:show_camera_roll` | Dutch-angle tilt rocks +/-10 degrees | stand still | 8 s |
| `vfxweaver:fov_modifier` | `/vfx play vfx_demos:show_fov_modifier` | Field of view widens by 10 degrees and returns | stand still | 8 s |
| `vfxweaver:fog_modifier` | `/vfx play vfx_demos:show_fog_modifier` | Fog pulls in, tints teal (the colour ramps in), then recedes | still, look north over the open edge | 12 s |

`show_fog_modifier` is recorded on the `26.2fabric` instance **because it ships Sodium** - the fog hook
runs before Sodium's own fog mixin and the priority is what makes the change visible there.

---

## Collections (1)

| effect id | command | what it looks like | camera/scene | loop |
|---|---|---|---|---|
| (collection) | `/vfx play vfx_demos:show_collection` | Blur, then chromatic aberration, then a warm glow, in sequence | stand still | 10 s |

`show_collection` is not a built-in effect; it demonstrates `type: collection` (three children with
delays 0/60/120) because no built-in ships as a collection.

---

## Audio (4) - turn the game volume up, nothing is drawn

| effect id | command | what it sounds like | camera/scene | loop |
|---|---|---|---|---|
| `vfxweaver:heartbeat` | `/vfx play vfx_demos:show_heartbeat` | A real heartbeat, accelerating from 60 to 120 bpm and back | still | 10 s |
| `vfxweaver:hum` | `/vfx play vfx_demos:show_hum` | A low 110 Hz hum that fades in and out | still | 10 s |
| `vfxweaver:noise` | `/vfx play vfx_demos:show_noise` | Filtered noise with a slow wander, like wind or a broken fan | still | 10 s |
| `vfxweaver:volume_duck` | `/vfx play vfx_demos:show_volume_duck` | The game's own music ducks under the mix and comes back | still, with music playing | 10 s |

## Notes

- **Depth-dependent, 26.2 only.** `show_surface_pattern`, `show_sky_mask`, `show_dome_mask` and every
  mask that uses a `world`, `aura`, `block` or GLSL-plugin leaf (`show_mask_block_demo`,
  `show_mask_block_xray_demo`, `show_mask_custom_demo`, `show_mask_entity_demo`,
  `show_mask_world_demo`, `show_mask_pulse_demo`, `show_mask_custom_glsl_aura_demo`,
  `show_mask_custom_glsl_surface_demo`, `show_mask_custom_data_demo`) read scene depth and are only
  meaningful on the 26.2 client. The pure screen masks (`show_mask_screen_demo`,
  `show_mask_custom_glsl_demo`) render on every supported line, and the sky/celestial clips in the
  Sky section are recorded on `26.2fabric`.
- **GLSL-plugin mask clips.** The three plugin clips use the built-in **world** plugin
  `vfxweaver:blobs_glsl` (a union of world spheres whose centres/radii come from the leaf's dynamic
  data). `show_mask_custom_data_demo` animates `mask.p0.d<J>` (`d0`/`d5`/`d10`), so the data **is**
  what moves the spheres. `show_mask_custom_glsl_demo` keeps using the screen reference
  `vfxweaver:ringed_glsl`. A plugin leaf is inert on the 1.21.11 node (no shader-source hook), so
  these three clips are recorded on the 26.2 client.
- **One instance per take.** `/vfx play` adds another instance; always `/vfx stop @s` between takes.
- **Dialled-down flashy effects** (see the report): glitch, VHS, scanlines, film grain, pixelate,
  shockwave, screen flash and noise warp all run well below their built-in defaults and ease in/out.
- All effects are client-side; the scene build is the only world change.
