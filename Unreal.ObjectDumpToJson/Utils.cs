using Reloaded.Memory.SigScan.ReloadedII.Interfaces;
using Reloaded.Mod.Interfaces;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Unreal.ObjectDumpToJson
{
    public class Utils
    {
        private IStartupScanner _startupScanner;
        private ILogger _logger;
        private long _baseAddress;
        public Utils(IStartupScanner startupScanner, ILogger logger, long baseAddress)
        {
            _startupScanner = startupScanner;
            _logger = logger;
            _baseAddress = baseAddress;
        }

        /// <summary>
        /// Gets the address of a global from something that references it
        /// </summary>
        /// <param name="ptrAddress">The address to the pointer to the global (like in a mov instruction or something)</param>
        /// <returns>The address of the global</returns>
        internal static unsafe nuint GetGlobalAddress(nint ptrAddress)
        {
            return (nuint)((*(int*)ptrAddress) + ptrAddress + 4);
        }

        public void SigScan(string pattern, string name, Func<int, nuint> transformCb, Action<long> hookerCb)
        {
            _startupScanner.AddMainModuleScan(pattern, result =>
            {
                if (!result.Found)
                {
                    _logger.WriteLine($"[RESEARCH] Couldn't find location for {name}, stuff will break :(");
                    return;
                }
                var addr = transformCb(result.Offset);
                _logger.WriteLine($"[RESEARCH] Found {name} at 0x{addr:X}");
                hookerCb((long)addr);
            });
        }
        public nuint GetDirectAddress(int offset) => (nuint)(_baseAddress + offset);
        public nuint GetIndirectAddressShort(int offset) => GetGlobalAddress((nint)_baseAddress + offset + 1);
        public nuint GetIndirectAddressLong(int offset) => GetGlobalAddress((nint)_baseAddress + offset + 3);
        public nuint GetIndirectAddressLong4(int offset) => GetGlobalAddress((nint)_baseAddress + offset + 4);
    }
}
