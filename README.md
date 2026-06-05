# Unreal Object Dumper

A tool for Reloaded-II to dump the runtime reflection info from Unreal Engine into a JSON format that can be imported into Ghidra to automatically construct data types and label functions.

## Usage

Open the game from Reloaded (or from your game launcher if ASI loader is deployed), then wait for the game to load at least to the main menu.

In Reloaded when the "Unreal Object Dumper" mod is selected in the list, select "Configure Mod" then either Save or close the window to execute the Object Dumper. The generated JSON will be in the mod's folder under /Dumps/[executable_name].json

In Ghidra, run the script "UnrealGen.java", select the Unreal version that the game uses, then select the dumped JSON. The script will then automatically perform all the labelling and structure creation.

## Games Tested

| Name | Version |
| - | - |
| Persona 3 Reload | 4.27 |
| Shin Megami Tensei V: Vengenance | 4.27 |
| Rune Factory: Guardians of Azuma | 5.4 |
| Clair Obscur: Expedition 33 | 5.4 |