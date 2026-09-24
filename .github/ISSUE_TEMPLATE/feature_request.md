---
name: Feature request
about: Suggest a new effect, parameter, command or API feature
title: "[Feature] "
labels: enhancement
assignees: ''
---

<!-- Thanks for the idea! The more specific you are, the easier it is to judge. -->

## What problem does this solve?

What are you trying to achieve that the mod cannot do today? Describe the situation, not the
implementation.

## Proposed behaviour

What should happen? If it is an effect, name its type and the params it would take, and how it
should look; if it is a command or a Java API method, sketch the signature. A datapack JSON or
command example is welcome.

## Your setup

- **Minecraft line + loader** (pick the one you play):
  - [ ] 26.2 × Fabric
  - [ ] 26.2 × NeoForge
  - [ ] 26.1.2 × Fabric
  - [ ] 26.1.2 × NeoForge
  - [ ] 1.21.11 × Fabric
  - [ ] 1.21.11 × NeoForge
- **Do you run an Iris shaderpack?** (yes / no — a pack changes what a client-side post effect can
  reach)

## Heads-up: what this mod is

`vfxweaver` is a **client-side post-processing library**. It composes effects *over* the finished
frame and drives datapack-defined effects; it does **not** mixin into vanilla's sky or star
rendering. A request that needs to hook or replace the vanilla sky/star/sun/moon render path cannot
work under an Iris shaderpack (a pack draws those itself, so such a hook is a silent no-op) — see
the [sky_pattern compatibility note](https://artition.github.io/VFX-Weaver/guide/effects/screen/sky-pattern/#compatibility-note-deliberately-not-implemented).
Ideas that stay in the post pass, world overlays or the Java API are welcome.
