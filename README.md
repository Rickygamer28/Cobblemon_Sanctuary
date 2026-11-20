# Cobblemon: Sanctuary

A NeoForge 1.21.1 addon for **Cobblemon 1.8.4** that gives your PC Pokémon a living home.

Place a **Sanctuary Core** and every Pokémon in your PC moves into a valley of its own: a town in the middle, a ring of habitats around it, and residents who live, play, nap, make friends and start families while you're there — and while you're away.

**Download:** [latest jar](https://github.com/Rickygamer28/Cobblemon_Sanctuary/tree/downloads) (current version 1.16.1)

## Requirements
- Minecraft 1.21.1 with NeoForge 21.1
- Cobblemon 1.8.4
- Kotlin for Forge (Cobblemon already needs it)
- Optional: Mega Showdown, Gravelmon, Lost Lore

## Getting started
1. Craft a **Sanctuary Core**: a Cobblemon PC in the middle, Pasture Blocks on the four sides, Type Gems in the corners.
2. Place it in an open area, pick a size, and press **Confirm**.
3. Step through to your valley and watch it build. The Sanctuary Journal you get opens the screen anywhere.

The sanctuary grows for free: Medium at 100 Pokémon, Large at 300, Huge at 600.

## A valley that builds itself
- **11 habitats around a town**: Lake & coast, Snowy peak, Cave & mountain, Desert, Volcano, Dragon's den, Haunted tower, Swamp, Forest, Flower garden and Power plant. Flying types roam the skies.
- **3 looks for every habitat**, chosen by the owner on the Region tab (one change a minute; only that habitat is rebuilt). The town can be an Old Kanto village or Park & gardens.
- Habitats wake up as you catch Pokémon of their types, and are sized by how many live there.
- **Caves under the mountains** — ice cavern, crystal grotto, a magma chamber with real lava — joined by deep tunnels.
- Landmarks in every habitat, graded routes from the town, pockets of one habitat inside another, a region map.
- Built in waves over a few minutes, and kept tidy. Nobody can build in the sanctuary except with the mod's own blocks; **Reshape** lays it all out again, **Repair here** rebuilds the land around you.

## Residents with a life
- Personalities, friendships and rivalries: they meet, greet, nuzzle, play-fight with their own animations and nap in Cobblemon's sleep poses.
- Daily routines: dens, jobs by type, berry meals, favourite spots.
- Families: eggs appear in the Nest, the Incubator hatches them.
- Life goes on while you're away, with a summary when you come back.

## Other mods
- **Mega Showdown**: Mega Evolution, Primal Reversion and its other form changes (Shaymin's Sky Forme, Aegislash's Blade Forme…) happen during play-fights, then wear off.
- **Gravelmon, Lost Lore and other type addons**: a custom type gets a pocket at 5 Pokémon, a sub-biome with its own landmark at 10, and a habitat of its own at 15 once it's big next to the others. The owner picks which habitat it lives in. Gravelmon's ??? type lives in the town.

## Items
Sanctuary Core · Sanctuary Journal · Whistle · Hub Bell · Gathering Basket · Incubator · Pokémon beds (3 sizes) · Feeding Trough · Bouncy Ball

## Commands
| Command | Who | What |
|---|---|---|
| `/sanctuary journal` | Anyone | Opens your Sanctuary Journal |
| `/sanctuary invite <player>` | Owner | Invites a player to share your sanctuary |
| `/sanctuary accept` | Anyone | Accepts an invite |
| `/sanctuary testpc add` / `clear` | Op | Puts every Pokémon and form in your PC to try the valley out, and takes them out again |

## Settings
Everything is in `serverconfig/cobblemon_sanctuary-server.toml` in the world folder: residents out at once, Mega and form changes, eggs, life while away, caves, custom type thresholds and homes, and `hiddenSpecies` for addon models that crash the game.

## Building
`./gradlew build` — the jar lands in `build/libs/`. Pushing a commit whose message contains `[selftest]` also runs a server that builds a whole valley and checks it.
