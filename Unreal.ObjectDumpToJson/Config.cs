using Reloaded.Mod.Interfaces.Structs;
using System.ComponentModel;
using Unreal.ObjectDumpToJson.Template.Configuration;

namespace Unreal.ObjectDumpToJson.Configuration
{
    public class Config : Configurable<Config>
    {
        public enum VirtualKeycodes
        {
            Num0 = 0x30,
            Num1 = 0x31,
            Num2 = 0x32,
            Num3 = 0x33,
            Num4 = 0x34,
            Num5 = 0x35,
            Num6 = 0x36,
            Num7 = 0x37,
            Num8 = 0x38,
            Num9 = 0x39,
            A = 0x41,
            B = 0x42,
            C = 0x43,
            D = 0x44,
            E = 0x45,
            F = 0x46,
            G = 0x47,
            H = 0x48,
            I = 0x49,
            J = 0x4A,
            K = 0x4B,
            L = 0x4C,
            M = 0x4D,
            N = 0x4E,
            O = 0x4F,
            P = 0x50,
            Q = 0x51,
            R = 0x52,
            S = 0x53,
            T = 0x54,
            U = 0x55,
            V = 0x56,
            W = 0x57,
            X = 0x58,
            Y = 0x59,
            Z = 0x5A,
            F1 = 112,
            F2 = 113,
            F3 = 114,
            F4 = 115,
            F5 = 116,
            F6 = 117,
            F7 = 118,
            F8 = 119,
            F9 = 120,
            F10 = 121,
            F11 = 122,
            F12 = 123,
        }

        [DisplayName("Dump Objects")]
        [Description("Dump all currently loaded objects")]
        [Category("Object Dumper")]
        [DefaultValue(VirtualKeycodes.F9)]
        public VirtualKeycodes Keycodes { get; set; } = VirtualKeycodes.F9;

    }

    /// <summary>
    /// Allows you to override certain aspects of the configuration creation process (e.g. create multiple configurations).
    /// Override elements in <see cref="ConfiguratorMixinBase"/> for finer control.
    /// </summary>
    public class ConfiguratorMixin : ConfiguratorMixinBase
    {
        // 
    }
}
