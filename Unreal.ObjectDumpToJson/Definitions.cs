using System.Text.Json.Serialization;
using UE.Toolkit.Core.Types.Unreal.UE5_4_4;

namespace Unreal.ObjectDumpToJson
{
    
    public class MemoryAddress(nint address)
    {
        private nint Address = address;
        public string Value => $"0x{Address:X}";

        public MemoryAddress() : this(0) {}
    }
    
    public class UnrealPropertyBase(string name)
    {
        public string Name { get; set; } = name;
    }
    
    public class UnrealField(string name, UnrealFieldType type, int offset, int size, EPropertyFlags flags)
        : UnrealPropertyBase(name)
    {
        public MemoryAddress Offset { get; set; } = new(offset);
        public int Size { get; set; } = size;
        public UnrealFieldType Type { get; set; } = type;
        public EPropertyFlags Flags { get; set; } = flags;
    }
    
    public class UnrealFieldType(string name) : UnrealPropertyBase(name)
    {
        public virtual string GetTypeName() => Name;
    }
    
    public class UnrealFieldArrayType(string name, UnrealField inner) : UnrealFieldType(name)
    {
        public string InnerTypeName { get; private set; } = inner.Type.GetTypeName();
    }
    
    public class UnrealFieldMapType(string name, UnrealField key, UnrealField value) : UnrealFieldType(name)
    {
        public string KeyName { get; private set; } = key.Type.GetTypeName();
        public string ValueName { get; private set; } = value.Type.GetTypeName();
    }
    
    public class UnrealFieldStructType(string name, UnrealStruct type) : UnrealFieldType(name)
    {
        public string TypeName { get; private set; } = type.Name;

        public override string GetTypeName() => TypeName;
    }
    
    public class UnrealFieldClassType(string name, UnrealClass type) : UnrealFieldType(name)
    {
        public string TypeName { get; private set; } = type.Name;

        public override string GetTypeName() => TypeName;
    }
    
    public class UnrealFieldBoolType(string name, int fieldSize, int byteOffset, int byteMask, int fieldMask)
        : UnrealFieldType(name)
    {
        public int FieldSize { get; set; } = fieldSize;
        public int ByteOffset { get; set; } = byteOffset;
        public int ByteMask { get; set; } = byteMask;
        public int FieldMask { get; set; } = fieldMask;
    }
    public class UnrealFieldEnumType(string name, UnrealEnum uEnum) : UnrealFieldType(name)
    {
        public string EnumName { get; private set; } = uEnum.Name;

        public override string GetTypeName() => EnumName;
    }
    
    public class UnrealFieldDelegateType(string name, UnrealFunction uDelegate) : UnrealFieldType(name)
    {
        public UnrealFunction Delegate { get; set; } = uDelegate;
    }
    
    public class UnrealStruct : UnrealPropertyBase
    {
        public List<UnrealField> Fields { get; set; }
        public int Size { get; set; }
        public int Alignment { get; set; }

        [JsonIgnore]
        private UnrealStruct? _superType;

        public string SuperTypeName { get; private set; }

        // type data for main inheriting class
        [JsonIgnore]
        public UnrealStruct? SuperType
        {
            get => _superType;
            set
            {
                _superType = value;
                if (_superType != null) SuperTypeName = _superType.Name;
            }
        }
        public UnrealStruct(string name, int size, int alignment) : base(name)
        {
            Size = size;
            Alignment = alignment;
            Fields = [];
        }
    }
    
    public class UnrealClass(string name, int size, int alignment, uint flags)
        : UnrealStruct(name, size, alignment)
    {
        public MemoryAddress NativeVtable { get; set; } = new();
        public MemoryAddress Constructor { get; set; } = new(); // don't need named, will always be called the same thing
        public List<UnrealFunction> Functions { get; set; } = [];
        public uint Flags { get; set; } = flags;
    }
    
    public class UnrealEnumEntry(string name, long value) : UnrealPropertyBase(name)
    {
        public long Value { get; set; } = value;
    }
    
    public class UnrealEnum(string name, List<UnrealEnumEntry> entries, int size) : UnrealPropertyBase(name)
    {
        public int Size { get; set; } = size;
        public List<UnrealEnumEntry> Entries { get; } = entries;
    }
    
    public class UnrealFunction(
        string name,
        EFunctionFlags flags,
        IntPtr execPtr,
        UnrealField? retVal,
        List<UnrealField> fparams)
        : UnrealPropertyBase(name)
    {
        public EFunctionFlags Flags { get; set; } = flags;
        public MemoryAddress CppFunc { get; set; } = new(execPtr); // execFunction
        public UnrealField? ReturnValue { get; set; } = retVal;
        public List<UnrealField> Parameters { get; set; } = fparams;
    }

    public class UnrealDataContainer
    {
        public Dictionary<string, UnrealClass> Classes { get; } = new();
        public Dictionary<string, UnrealStruct> Structs { get; } = new();
        public Dictionary<string, UnrealEnum> Enums { get; } = new();

        public void Clear()
        {
            Classes.Clear();
            Structs.Clear();
            Enums.Clear();   
        }
    }
}