namespace Unreal.ObjectDumpToJson;

// ReSharper disable once ClassNeverInstantiated.Global
public class ClassOffsets(string name, List<int> offsets)
{
    public string Name { get; set; } = name;
    public List<int> Offsets { get; set; } = offsets;
}

public class ClassOffsetLocations(string name, List<MemoryAddress> addresses)
{
    public string Name { get; set; } = name;
    public List<MemoryAddress> Addresses { get; set; } = addresses;
}