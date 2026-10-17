# Pandorical Geyser

Lets Bedrock players - phone, tablet, Windows and console - join a server running [Pandorical](https://github.com/fatlard1993/pandorical) through [Geyser](https://geysermc.org), and play it with the server's modded blocks and items instead of an inventory that won't load and blocks they walk into without seeing.

Server only. Bedrock players install nothing: their game is sent a resource pack when they join.

## What Bedrock players get

- **In at all.** Fabric turns away any game without Fabric on a server with modded content. This mod lets Bedrock players past that check, and only them (see [The one hack](#the-one-hack)).
- **Modded items**, with their names, stack sizes, durability and enchantability. An item drawn flat on Java shows its own texture; a block item shows its block. An item the mod can't picture shows as paper, still named.
- **What the items do**: a modded tool digs at its own speeds, food is eaten (or drunk) as on Java, armour goes on its slot, an anvil knows what mends it, and a weapon's tooltip shows its damage.
- **Modded blocks**, shaped and textured from their Java models: slabs are half height, plants are crosses, and what you can walk on, walk through and aim at matches Java. Grass and leaves take the biome's colour, and glass and ice are see-through.
- **Pandorical's menus as Bedrock forms**: the mods list, settings, notices, the welcome, what's new and trust, all through `/pandorical actions`, or Geyser's `/geyser quickactions`, which on Fabric without a permissions mod is for ops. That part is Pandorical's own, and works for any player without Pandorical. This mod is what tells Pandorical who is on Bedrock.
- **The camera**, where a mod asks for one: a forced first or third person view. Bedrock has no camera distance or zoom, so a mod asking for those is told, in the server log, that it can't have them.

## What they don't

- Anything a mod draws with Pandorical's own screens or HUD beyond the menus above: those need Pandorical on the game.
- Models that aren't made of boxes, such as entity-drawn chests, show as the shape of their outline.

## Requirements

- Minecraft, Fabric Loader, Fabric API
- [Pandorical](https://github.com/fatlard1993/pandorical) 15.22.0 or later
- [Geyser-Fabric](https://geysermc.org/download) 2.12 or later
- [Floodgate](https://geysermc.org/download?project=floodgate) for Fabric, recommended: it is what lets Bedrock players in without a Java account, and the earliest a player can be told apart. Without it this mod asks Geyser alone, which also works.

## Setting up

1. Put Pandorical, this mod, Geyser-Fabric and Floodgate in the server's `mods` folder.
2. In Geyser's `config.yml`, set `enable-custom-content: true`. Without it Geyser takes no modded blocks or items, and Bedrock inventories with a modded item in them arrive empty.
3. Start the server. The log says how many modded blocks and items were mapped for Bedrock, and lists any block drawn as a plain box because its model couldn't be read.

The resource pack is rebuilt on every start, into `pandorical-geyser/bedrock-content.mcpack` in the server folder. It only changes when the mods do, so Bedrock players download it again only then.

## The one hack

Fabric API checks every joining game for its own registry sync and has no way to excuse one. This mod skips that check for a player Floodgate or Geyser says is on Bedrock, and for no one else: a Java game without Fabric is still turned away, as before. It reaches into Fabric API's internals to do it, which Fabric doesn't promise to keep still, so a Fabric API update can break it. If it does, the server refuses to start rather than quietly turning Bedrock players away.

## Licences

This mod is MIT. Its jar carries two things that aren't:

- The table of Java to Bedrock texture names, from GeyserMC's [PackConverter](https://github.com/GeyserMC/PackConverter) (MIT, GeyserMC).
- Minecraft's own block models, taken from the game jar when the mod is built, because a server doesn't have them and the blocks are drawn from them. They are Mojang's, under Mojang's terms. This repository doesn't contain them.

The details are in `NOTICE`, inside the jar.

## Development

Pandorical builds as part of this one, from `../pandorical` beside it (see `settings.gradle`). `./gradlew runServer` starts a dev server; put Geyser-Fabric and Floodgate in `run/mods` first.

### Checking how blocks come out

`tools/bedrock-check` draws every block this mod converts beside the Java model it came from, in [Blockbench](https://www.blockbench.net)'s web build, and reports any that differ. Blockbench draws Bedrock geometry the way Bedrock does, so this checks the conversion without a Bedrock client.

1. Start the dev server once with the export on, with the mods to check in `run/mods`:
   `JAVA_TOOL_OPTIONS=-Dpandorical-geyser.export=true ./gradlew runServer`. It writes `run/pandorical-geyser/export`.
2. In `tools/bedrock-check`, `npm install`, then
   `node check.cjs ../../run/pandorical-geyser/export <minecraft client jar> out`. The client jar (Loom keeps one under `~/.gradle/caches/fabric-loom/<version>/minecraft-client-only.jar`) supplies vanilla textures.
3. `out/report.md` lists each drawing compared; any that differ get an image, Java on the left, Bedrock on the right. The exit status is the number that differ.

The Java side is drawn unturned, with the camera turned instead by the check's own rotation maths, so a mistake in this mod's rotations can't copy itself into the reference. Multipart blocks whose pieces turn differently, uvlock, and z turns are skipped and listed. Headless Chrome needs a few system libraries (on Ubuntu: `libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 libxkbcommon0 libxcomposite1 libxdamage1 libxfixes3 libxrandr2 libgbm1 libpango-1.0-0 libasound2t64 libnss3`).

## License

MIT, see [LICENSE](LICENSE).
