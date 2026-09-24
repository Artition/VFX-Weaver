# Contributing

## Branches

The model is GitHub Flow: `main` always builds and is release-ready, all work happens in short-lived branches off `main`, merged via PR.

| Prefix | When to use | Example |
|---|---|---|
| `feature/` | new functionality | `feature/block-outline-shell-mode` |
| `fix/` | bug fix | `fix/vfx-definition-reload-crash` |
| `chore/` | routine: dependencies, configs, CI, formatting | `chore/bump-fabric-loader` |
| `refactor/` | code structure change without behavior change | `refactor/effect-manager-split` |
| `docs/` | documentation only | `docs/update-guide-v12` |
| `release/` | release preparation (version, changelog) | `release/1.1.0` |
| `hotfix/` | urgent fix straight into prod/release branch | `hotfix/network-payload-oom` |

`main` is protected: direct pushes are forbidden, only PRs with a green build (`./gradlew build`).

## Commits

[Conventional Commits](https://www.conventionalcommits.org/): `<type>(<scope>): <description>`. `scope` is optional, usually a package/subsystem (`effect`, `network`, `render`, `command`, `resource`, `client`).

| Type | Meaning |
|---|---|
| `feat` | new functionality visible to users/API |
| `fix` | bug fix |
| `docs` | documentation only (README, GUIDE, AGENTS, doc comments) |
| `style` | formatting, indentation, semicolons — no logic change |
| `refactor` | code change without behavior change and without new functionality |
| `perf` | a performance-oriented change |
| `test` | adding/fixing tests |
| `build` | build system, dependencies (`build.gradle`, `gradle.properties`) |
| `ci` | CI configuration (`.github/workflows/*`) |
| `chore` | other routine not covered by the rest |
| `revert` | revert of a previous commit |

Breaking change — an exclamation mark after type/scope (`feat!:` or `feat(network)!:`) and/or a `BREAKING CHANGE: <description>` footer.

### Examples (from project history)

```
feat(effect): add shell mode to block_outline

Adds a boolean `shell` param selecting between wall-extrusion and
scaled-shell outline rendering, per GUIDE.md v11.
```

```
fix(resource): don't abort VFX definition reload on one bad file

prepare() only caught JsonParseException/IllegalStateException/IOException,
but VFXDefinition.parse() throws IllegalArgumentException for unknown
effect types and malformed positions. A single bad datapack file was
aborting the whole reload instead of being skipped.
```

```
fix(network)!: cap VFXTriggerPayload params map size

BREAKING CHANGE: bumps PROTOCOL_VERSION to 2; clients on protocol 1
will ignore packets from servers running this version.
```

```
docs: move usage guide to docs/GUIDE.md, add README/AGENTS/CONTRIBUTING
```

```
chore(build): require JDK 25 toolchain via gradle.properties
```

```
refactor(client): extract CameraShakeManager from VFXEffectManager
```

## Pull Request

- One PR = one logical unit of work (don't mix `feat` and `refactor` unnecessarily).
- The PR must build: `./gradlew build` green.
- If effect/command/Java API behavior changes, update `docs/GUIDE.md` (add a changelog entry at the bottom of the file) in the same PR.
- For formatting/code style fixes, see `AGENTS.md`.

## Issues and ideas

Use the GitHub issue templates: **Feature request** for an idea (`.github/ISSUE_TEMPLATE/feature_request.md`), **Bug report** for a defect.
