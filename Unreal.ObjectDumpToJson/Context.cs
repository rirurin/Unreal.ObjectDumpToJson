using Reloaded.Memory.SigScan.ReloadedII.Interfaces;
using Reloaded.Mod.Interfaces;
using p3rpc.commonmodutils;
using Reloaded.Hooks.ReloadedII.Interfaces;
using Reloaded.Memory;
using SharedScans.Interfaces;
using UE.Toolkit.Core.Types.Unreal.Factories;
using UE.Toolkit.Interfaces;
using Unreal.ObjectDumpToJson.Configuration;

namespace Unreal.ObjectDumpToJson
{
    public class DumperContext(
        long baseAddress,
        IConfigurable config,
        ILogger logger,
        IStartupScanner startupScanner,
        IReloadedHooks hooks,
        string modLocation,
        Utils utils,
        Memory memory,
        ISharedScans sharedScans,
        IUnrealStrings toolkitStrings,
        IUnrealObjects toolkitObjects,
        IUnrealMemory toolkitMemory,
        IUnrealState toolkitState,
        IUnrealFactory toolkitFactory,
        IUnrealSpawning toolkitSpawning,
        IUnrealClasses toolkitClasses,
        string programName)
        : UnrealToolkitContext(baseAddress, config, logger, startupScanner, hooks, modLocation, utils, memory,
            sharedScans,
            toolkitStrings, toolkitObjects, toolkitMemory, toolkitClasses, toolkitFactory, toolkitState,
            toolkitSpawning)
    {
        
        public string _programName { get; init; } = programName;
        public Config GetConfig() => (Config)_config;
    }
}
