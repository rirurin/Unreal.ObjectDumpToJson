using Reloaded.Mod.Interfaces.Structs;
using System.ComponentModel;
using RyoTune.Reloaded;
using Unreal.ObjectDumpToJson.Template.Configuration;

namespace Unreal.ObjectDumpToJson.Configuration
{
    public class Config : Configurable<Config>
    {
        
        [Category("Debug")]
        [DisplayName("Log Level")]
        [DefaultValue(LogLevel.Information)]
        public LogLevel LogLevel { get; set; } = LogLevel.Information;

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
