# Unreal Object Dumper

A tool for Reloaded-II to dump the runtime reflection info from Unreal Engine into a JSON format that can be imported into Ghidra to automatically construct data types and label functions.

## Usage

### Dumping Game Objects (basic)

Open the game from Reloaded (or from your game launcher if ASI loader is deployed), then wait for the game to load at least to the main menu.

In Reloaded when the "Unreal Object Dumper" mod is selected in the list, select "Configure Mod" then either Save or close the window to execute the Object Dumper. This will generate a JSON called `ReflectionInfo.json` inside the folder `/Dumps/[executable_name]`.

In Ghidra, run the script `UnrealGen.java`, select the Unreal version that the game uses, then select `ReflectionInfo.json`. The script will then automatically perform all the labelling and structure creation.

### Additional Scripts

Other scripts are available to run after `UnrealGen.java` to add additional labelling.

#### UnrealExportVTables.java
*Run this script on a blank UE game compiled with debug symbols*

A script that scrapes the vtable contents from an executable with debug symbols and saves it into `VTables.json`. It also stores information on non-zero offset vtables (these occur in classes with multiple inheritance) into a file called `Offsets.json`. The object dumper can use `Offsets.json` to retrieve where those vtables exist in the target game and output the result into `OffsetAddresses.json`.

#### UnrealImportVTables.java
*Run this script on your game's executable*

Reads the vtable information from `VTable.json`, `Offsets.json` and `OffsetAddresses.json` to fill out the function names for all the virtual functions that are shared between the game and the executable used for the export script.

## Games Tested

| Name | Version |
| - | - |
| Persona 3 Reload | 4.27 |
| Shin Megami Tensei V: Vengenance | 4.27 |
| Rune Factory: Guardians of Azuma | 5.4 |
| Clair Obscur: Expedition 33 | 5.4 |