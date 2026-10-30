# Modrinth listing

The page is the product: it is where every download starts, and it is judged in about eight seconds.
This file records what the page says, what is still missing, and the exact shots the gallery needs.

## What the listing currently says

- **Title:** `VFXWeaver`. It was `VFXWeaver API` (the word "API" reads as "a dependency you will get
  installed for you", and players skim past it), and briefly `VFXWeaver — Cinematic Effects`. The
  tagline went because Modrinth's search indexes the title and the summary, the summary already
  carries `cinematic` and `effects`, so the subtitle added no query coverage - it only made the name
  longer in every list and in Modrinth's own cards. The slug stays `vfxweaver-api`, so older links
  keep working.
- **Summary:** cinematic screen effects, camera shake, overlays and a browser node editor, one
  sentence, keyword-led (`cinematic`, `screen effects`, `post-processing`, `camera shake`, `node
  editor`, `client-side`, `datapacks`). It briefly lost `post-processing` when the page was rewritten
  and the `post processing` query fell out of the top ten with it; the word is back and so is the
  position.
- **Body:** the showcase video first, then *Try it in two minutes* (ten copy-paste commands with what
  each one looks like), *Build your own in the browser* (`/vfxeditor` was not mentioned at all before),
  the effect inventory by group, datapacks, the Java API, requirements, and links.
- **Links:** source, issue tracker, guide, changelog. The issue tracker was empty before.

The single largest omission was the editor: the mod ships a node graph that runs in a browser, exports
a plain datapack, and previews live in game, and the page never said so. Second largest: the commands
that make something happen ten seconds after install.

## What is done in the repository

- The guide site was three releases stale (`gh-pages` still served 2.3.0) because the docs workflow
  only runs on `main` and 2.4/2.5 shipped from `visual-editor`. It now serves **2.5.0** with the
  editor page and every 2.5.0 effect page.
- The showcase datapack now covers the 2.5.0 effects (`show_datamosh`, `show_torn_edge`,
  `show_spectral`, `show_hex_grid`, `show_heartbeat`, `show_hum`, `show_noise`, `show_volume_duck`),
  and its source lives in `showcase/` instead of only inside a zip. Rebuild with
  `python scripts/build-showcase-pack.py`.

## What only the owner can do

1. **Put the existing showcase video into the gallery.** The gallery is what people swipe through; it
   currently holds four stills from the test stage. The video is already made
   (`https://www.youtube.com/watch?v=iXiOvUcmyuk`) and only needs to be added as a gallery item in the
   web UI — the API accepts images only.
2. **Record six gameplay clips** (below). The clips in `docs/assets/media/` are documentation proof on
   a bare stage; they are right for the guide and wrong for the listing.
3. **Decide on a Discord.** There is no channel for feedback, so there are no bug reports and no
   community. A thread on an existing server is enough to start.
4. **CurseForge.** The mod is not there; that is a second search surface and a different audience.

## Shot list for the gallery

Record at 1920x1080, 30 fps, 6-8 s each, no UI (F1 hides it), default FOV. One clip per effect, no
cuts inside a clip, and let the effect reach full strength before the loop point. Each clip is played
with `/vfx play <id>`, and `/vfx stop <id>` between takes.

| # | Scene | Effect | Command | What must be in frame |
|---|---|---|---|---|
| 1 | Village at dusk, walking forward | `datamosh` | `/vfx play vfxweaver:datamosh` | Movement: the melt only shows when the camera moves. Pan past a lit window. |
| 2 | Sunset over water, standing still | `torn_edge` | `/vfx play vfxweaver:torn_edge` | The tear line crossing the horizon, sky on both sides. |
| 3 | Same spot as 2 | `spectral` | `/vfx play vfxweaver:spectral {[mode:1]}` | The colour smear over bright sky and dark water. |
| 4 | A built structure (city, redstone, statues) | `hex_grid` | `/vfx play vfxweaver:hex_grid` | Straight edges and depth, so the lattice reads. |
| 5 | Creeper at arm's length, backing away | `camera_shake` | `/vfx play vfxweaver:camera_shake {[amplitude_x:0.6]}` | Something that justifies the shake. |
| 6 | Dark cave, standing still, volume up | `heartbeat` | `/vfx play vfxweaver:heartbeat {[rate:90]}` | Nothing to look at: this clip sells the sound. Record game audio. |
| 7 | Browser beside the game | the editor | `/vfxeditor` | The node graph, a slider being dragged, the game reacting in the same shot. |

Then cut the six anonymous clips into one 25-30 s reel for YouTube (title card, no talking) and keep
the individual clips for the gallery.

### From a clip to a gallery tile

Modrinth accepts png / jpg / gif / webp up to 5 MiB per image. A 640 px wide, 12 fps, 4 s GIF with a
128-colour palette lands around 1-2 MB:

```
ffmpeg -ss 1.0 -t 4.0 -i clip.mp4 -vf "fps=12,scale=640:-1:flags=lanczos,split[a][b];[a]palettegen=max_colors=128:stats_mode=diff[p];[b][p]paletteuse=dither=bayer:bayer_scale=4" -loop 0 tile.gif
```

Upload through the gallery in the web UI (YouTube links go there too; the API is images only).

## Things to watch

- The listing body must stay honest: no effect is claimed to work on a version it does not support,
  and the performance note (half-resolution chain by default, fusion on) is worth keeping visible when
  the page grows.
- **Where the page ranks** (checked 2026-10-30, mod projects only):

  | query | position | results |
  |---|---|---|
  | `vfx` | 3 | 11 |
  | `post processing` / `post-processing` | 3 | 9 |
  | `camera shake` | 8 | 17 |
  | `screen effects` | 9 | 21 |
  | `cinematic effects` | 1 | 6 |
  | `cinematic`, `effects`, `shader`, `visual effects` | not in the top 20 | 75-1020 |

  The pattern is that the page wins queries with a few dozen results and is invisible on the broad
  ones, which is what a specific name and a specific summary can do. The broad words live in the
  summary (title and summary are what Modrinth indexes; the body is not), so a further rename buys
  nothing - reach has to come from outside search: the gallery, videos, and other projects that
  depend on this one.
