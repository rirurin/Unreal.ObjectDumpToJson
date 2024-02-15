using Reloaded.Hooks.Definitions;
using Reloaded.Memory.SigScan.ReloadedII.Interfaces;
using Reloaded.Mod.Interfaces;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Unreal.ObjectDumpToJson.Configuration;

namespace Unreal.ObjectDumpToJson
{
    public class Context
    {
        public long _baseAddress { get; }
        public Config _config { get; set; }
        public ILogger _logger { get; }
        public IStartupScanner _startupScanner { get; }
        public IReloadedHooks _hooks { get; }
        public Utils _utils { get; }

        public string ModLocation;
        public unsafe FNamePool* g_namePool { get; private set; }
        public unsafe FUObjectArray* g_objectArray { get; private set; }
        public string ProgramName { get; private set; }

        public Context(long baseAddress, Config config, ILogger logger, IStartupScanner startupScanner, IReloadedHooks hooks, string modLocation, Utils utils, string programName)
        {
            _baseAddress = baseAddress;
            _config = config;
            _logger = logger;
            _startupScanner = startupScanner;
            _hooks = hooks;
            ModLocation = modLocation;
            _utils = utils;
            ProgramName = programName;
            unsafe
            {
                _startupScanner.AddMainModuleScan("48 8B 05 ?? ?? ?? ?? 48 8B 0C ?? 48 8D 04 ?? 48 85 C0 74 ?? 44 39 40 ?? 75 ?? F7 40 ?? 00 00 00 30 75 ?? 48 8B 00", result =>
                {
                    var globalArrayPtr = Utils.GetGlobalAddress((nint)(_baseAddress + result.Offset + 3)) - 0x10;
                    _logger.WriteLine($"[RESEARCH] Found FUObjectArray at 0x{globalArrayPtr:X}");
                    g_objectArray = (FUObjectArray*)globalArrayPtr;
                });
                _startupScanner.AddMainModuleScan("4C 8D 05 ?? ?? ?? ?? EB ?? 48 8D 0D ?? ?? ?? ?? E8 ?? ?? ?? ?? 4C 8B C0 C6 05 ?? ?? ?? ?? 01 48 8B 44 24 ?? 48 8B D3 48 C1 E8 20 8D 0C ?? 49 03 4C ?? ?? E8 ?? ?? ?? ?? 48 8B C3", result =>
                {
                    var namePoolPtr = Utils.GetGlobalAddress((nint)(_baseAddress + result.Offset + 3));
                    _logger.WriteLine($"[RESEARCH] Found FGlobalNamePool at 0x{namePoolPtr:X}");
                    g_namePool = (FNamePool*)namePoolPtr;
                });
            }
        }
    }
}
