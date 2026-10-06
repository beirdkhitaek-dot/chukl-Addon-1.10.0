# ChuklAddon

A Meteor Client addon for Minecraft 1.21.11 (Fabric).

## Modules
- **Deep Block Entity ESP** - highlights block entities below a Y level (default Y15), with per-block colors, tracers and a see-through-walls toggle.
- **Dug Out Highlighter** - highlights big rectangular dug out areas underground, with a see-through-walls toggle.
- **Sus Chunk Finder** - Krypton-style base finder using plant/amethyst growth and rotated deepslate.
- **Light Finder** - highlights underground light, which can reveal hidden bases.
- **Water Sus Chunk** (`water-suschunk`) - Water Client's Sus Chunk Finder: amethyst clusters plus underground chests, with flashing base chunks.
- **Vulxts Sus Chunk** (`vulxts-suschunk`) - Vulxts' Sus Chunk Finder: weighted amethyst/kelp/bamboo/berries/vines/dripstone scoring with smart zone merging.

## Building
Push to GitHub. The **Build** workflow produces the jar as an artifact (Actions -> latest run -> Artifacts).
Locally: install Gradle 9.2+ and JDK 21, then run `gradle build`. The jar is in `build/libs/`.

## Releasing an update
1. Bump `mod-version` in `gradle/libs.versions.toml`.
2. Commit and push.
3. Actions -> **Release** -> Run workflow, and enter the update name.
