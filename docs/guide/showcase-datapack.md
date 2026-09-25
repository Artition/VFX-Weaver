# Showcase datapack

The datapack used to record the clips on these pages. It contains one definition per built-in
effect - each looping, gently animated and tuned to be readable on camera - plus a small stage
and helper functions for building it.

## Install

1. Download <a href="../../assets/downloads/vfx-demos-showcase.zip">vfx-demos-showcase.zip</a>.
2. Put it in your world's datapack folder: saves/<world>/datapacks/ for single player, <world>/datapacks/ on a server.
3. Run /reload and check /datapack list.

Everything lives in the fx_demos namespace, so it never clashes with the mod's built-ins.

## Stage functions

| Function | What it does |
|---|---|
| /function vfx_demos:scene/setup | Builds the stage: a 13x13 smooth-stone platform (x1994-2006, y99, z1994-2006), a white wall along z2006, a red pillar, a blue block, and a villager tagged fx_showcase at the centre. Teleports you to (2000, 100, 1994) facing the wall. |
| /function vfx_demos:scene/clear | Stops your effects, removes the tagged villager and the minecart, and clears the stage. |
| /function vfx_demos:scene/minecart_setup | Builds a 7x7 rail loop with powered rails on the stage and puts the villager in a minecart riding it. Use it for the effects that need a moving subject (motion_blur, fterimage). |
| /function vfx_demos:scene/minecart_clear | Removes the minecart and the rail loop. |

## Showcases

Play them one at a time - /vfx stop @s between takes, otherwise instances stack. The clips on the
effect pages were recorded from these definitions.

| Effect | Command |
|---|---|
| `show_afterimage` | `/vfx play vfx_demos:show_afterimage` |
| `show_block_chain` | `/vfx play vfx_demos:show_block_chain` |
| `show_block_outline` | `/vfx play vfx_demos:show_block_outline` |
| `show_block_tint` | `/vfx play vfx_demos:show_block_tint` |
| `show_bloom` | `/vfx play vfx_demos:show_bloom` |
| `show_blur` | `/vfx play vfx_demos:show_blur` |
| `show_camera_roll` | `/vfx play vfx_demos:show_camera_roll` |
| `show_camera_shake` | `/vfx play vfx_demos:show_camera_shake` |
| `show_chromatic_aberration` | `/vfx play vfx_demos:show_chromatic_aberration` |
| `show_collection` | `/vfx play vfx_demos:show_collection` |
| `show_color_grade` | `/vfx play vfx_demos:show_color_grade` |
| `show_dent` | `/vfx play vfx_demos:show_dent` |
| `show_dent_field_demo` | `/vfx play vfx_demos:show_dent_field_demo` |
| `show_depth_of_field` | `/vfx play vfx_demos:show_depth_of_field` |
| `show_digital_glitch` | `/vfx play vfx_demos:show_digital_glitch` |
| `show_distortion` | `/vfx play vfx_demos:show_distortion` |
| `show_double_vision` | `/vfx play vfx_demos:show_double_vision` |
| `show_ember` | `/vfx play vfx_demos:show_ember` |
| `show_entity_displace` | `/vfx play vfx_demos:show_entity_displace` |
| `show_entity_outline` | `/vfx play vfx_demos:show_entity_outline` |
| `show_entity_tint` | `/vfx play vfx_demos:show_entity_tint` |
| `show_eyelids` | `/vfx play vfx_demos:show_eyelids` |
| `show_film_grain` | `/vfx play vfx_demos:show_film_grain` |
| `show_fov_modifier` | `/vfx play vfx_demos:show_fov_modifier` |
| `show_fog_modifier` | `/vfx play vfx_demos:show_fog_modifier` |
| `show_gradient_map` | `/vfx play vfx_demos:show_gradient_map` |
| `show_graph_demo` | `/vfx play vfx_demos:show_graph_demo` |
| `show_graph_logic_demo` | `/vfx play vfx_demos:show_graph_logic_demo` |
| `show_guide_line` | `/vfx play vfx_demos:show_guide_line` |
| `show_hue_isolation` | `/vfx play vfx_demos:show_hue_isolation` |
| `show_invert` | `/vfx play vfx_demos:show_invert` |
| `show_iris_wipe` | `/vfx play vfx_demos:show_iris_wipe` |
| `show_letterbox` | `/vfx play vfx_demos:show_letterbox` |
| `show_light_beam` | `/vfx play vfx_demos:show_light_beam` |
| `show_mask_block_demo` | `/vfx play vfx_demos:show_mask_block_demo` |
| `show_mask_block_xray_demo` | `/vfx play vfx_demos:show_mask_block_xray_demo` |
| `show_mask_custom_demo` | `/vfx play vfx_demos:show_mask_custom_demo` |
| `show_mask_custom_data_demo` | `/vfx play vfx_demos:show_mask_custom_data_demo` |
| `show_mask_custom_glsl_demo` | `/vfx play vfx_demos:show_mask_custom_glsl_demo` |
| `show_mask_custom_glsl_aura_demo` | `/vfx play vfx_demos:show_mask_custom_glsl_aura_demo` |
| `show_mask_custom_glsl_surface_demo` | `/vfx play vfx_demos:show_mask_custom_glsl_surface_demo` |
| `show_mask_entity_demo` | `/vfx play vfx_demos:show_mask_entity_demo` |
| `show_mask_pulse_demo` | `/vfx play vfx_demos:show_mask_pulse_demo` |
| `show_mask_screen_demo` | `/vfx play vfx_demos:show_mask_screen_demo` |
| `show_mask_world_demo` | `/vfx play vfx_demos:show_mask_world_demo` |
| `show_motion_blur` | `/vfx play vfx_demos:show_motion_blur` |
| `show_noise_warp` | `/vfx play vfx_demos:show_noise_warp` |
| `show_particles` | `/vfx play vfx_demos:show_particles` |
| `show_pixelate` | `/vfx play vfx_demos:show_pixelate` |
| `show_posterize` | `/vfx play vfx_demos:show_posterize` |
| `show_pulse_ring` | `/vfx play vfx_demos:show_pulse_ring` |
| `show_scanlines` | `/vfx play vfx_demos:show_scanlines` |
| `show_screen_flash` | `/vfx play vfx_demos:show_screen_flash` |
| `show_shockwave` | `/vfx play vfx_demos:show_shockwave` |
| `show_slice_shift` | `/vfx play vfx_demos:show_slice_shift` |
| `show_solarize` | `/vfx play vfx_demos:show_solarize` |
| `show_sparks` | `/vfx play vfx_demos:show_sparks` |
| `show_speed_lines` | `/vfx play vfx_demos:show_speed_lines` |
| `show_stop_motion` | `/vfx play vfx_demos:show_stop_motion` |
| `show_surface_pattern` | `/vfx play vfx_demos:show_surface_pattern` |
| `show_surface_pattern_texture` | `/vfx play vfx_demos:show_surface_pattern_texture` |
| `show_tint_field_demo` | `/vfx play vfx_demos:show_tint_field_demo` |
| `show_vhs` | `/vfx play vfx_demos:show_vhs` |
| `show_vignette` | `/vfx play vfx_demos:show_vignette` |
| `show_vortex` | `/vfx play vfx_demos:show_vortex` |
