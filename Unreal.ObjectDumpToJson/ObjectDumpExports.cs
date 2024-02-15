using Newtonsoft.Json;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Text;
using System.Threading.Tasks;

namespace Unreal.ObjectDumpToJson
{
    // Copied from Hi-Fi RUSH Research project

    public enum ClassPrefixName
    {
        InheritsUObject = 1, // U[ClassName]
        InheritsAActor = 2, // A[ClassName]
        IsEnum = 3, // E[EnumName]
        Other = 4, // F[StructName]
        InheritsSWidget = 5, // S[WidgetName]
        IsAbstractInterface = 6 // I[InterfaceName]
    }
    public class AddressDisplayHex
    {
        [JsonIgnore]
        private IntPtr _address;
        [JsonIgnore]
        public IntPtr Address
        {
            get => _address;
            set
            {
                _address = value;
                Value = $"0x{_address:X}";
            }
        }
        public string Value { get; private set; }

        public AddressDisplayHex(IntPtr value)
        {
            Address = value;
        }
    }
    public class UnrealPropertyBase
    {
        public string Name { get; set; }

        public UnrealPropertyBase(string name)
        {
            Name = name;
        }
    }
    public class UnrealField : UnrealPropertyBase
    {
        public AddressDisplayHex Offset { get; set; }
        public int Size { get; set; }
        public UnrealFieldType Type { get; set; }
        public EPropertyFlags Flags { get; set; }
        public UnrealField(string name, UnrealFieldType type, int offset, int size, EPropertyFlags flags) : base(name)
        {
            Offset = new AddressDisplayHex(offset);
            Type = type;
            Size = size;
            Flags = flags;
        }
    }
    public class UnrealFieldDebug : UnrealField
    {
        public AddressDisplayHex DebugLocation { get; set; }
        public UnrealFieldDebug(string name, UnrealFieldType type, int offset, int size, EPropertyFlags flags, IntPtr debugLocation)
            : base(name, type, offset, size, flags)
        {
            DebugLocation = new AddressDisplayHex(debugLocation);
        }
    }
    public class UnrealFieldType : UnrealPropertyBase
    {
        public UnrealFieldType(string name) : base(name) { }

        public virtual string GetTypeName() => Name;
    }
    public class UnrealFieldArrayType : UnrealFieldType
    {
        [JsonIgnore]
        private UnrealField _Inner;
        public string InnerTypeName { get; private set; }
        [JsonIgnore]
        public UnrealField Inner
        {
            get => _Inner;
            set
            {
                _Inner = value;
                InnerTypeName = Inner.Type.GetTypeName();
            }
        }
        public UnrealFieldArrayType(string name, UnrealField inner) : base(name)
        {
            Inner = inner;
        }
    }
    public class UnrealFieldMapType : UnrealFieldType
    {
        // KEY
        [JsonIgnore]
        private UnrealField _Key;
        public string KeyName { get; private set; }
        [JsonIgnore]
        public UnrealField Key
        {
            get => _Key;
            set
            {
                _Key = value;
                KeyName = Key.Type.GetTypeName();
            }
        }
        // VALUE
        [JsonIgnore]
        private UnrealField _Value;
        public string ValueName { get; private set; }
        [JsonIgnore]
        public UnrealField Value
        {
            get => _Value;
            set
            {
                _Value = value;
                ValueName = Value.Type.GetTypeName();
            }
        }
        public UnrealFieldMapType(string name, UnrealField key, UnrealField value) : base(name)
        {
            Key = key;
            Value = value;
        }
    }
    public class UnrealFieldStructType : UnrealFieldType
    {
        [JsonIgnore]
        private UnrealStruct _Type;
        public string TypeName { get; private set; }
        [JsonIgnore]
        public UnrealStruct Type
        {
            get => _Type;
            set
            {
                _Type = value;
                TypeName = _Type.Name;
            }
        }
        public UnrealFieldStructType(string name, UnrealStruct type) : base(name)
        {
            Type = type;
        }
        public override string GetTypeName() => TypeName;
    }
    public class UnrealFieldClassType : UnrealFieldType
    {
        [JsonIgnore]
        private UnrealClass _Type;
        public string TypeName { get; private set; }
        [JsonIgnore]
        public UnrealClass Type
        {
            get => _Type;
            set
            {
                _Type = value;
                TypeName = _Type.Name;
            }
        }
        public UnrealFieldClassType(string name, UnrealClass type) : base(name)
        {
            Type = type;
        }
        public override string GetTypeName() => TypeName;
    }
    public class UnrealFieldBoolType : UnrealFieldType
    {
        public int FieldSize { get; set; }
        public int ByteOffset { get; set; }
        public int ByteMask { get; set; }
        public int FieldMask { get; set; }
        public UnrealFieldBoolType(string name, int field_size, int byte_offset, int byte_mask, int field_mask) : base(name)
        {
            FieldSize = field_size;
            ByteOffset = byte_offset;
            ByteMask = byte_mask;
            FieldMask = field_mask;
        }
    }
    public class UnrealFieldEnumType : UnrealFieldType
    {

        [JsonIgnore]
        private UnrealEnum _Enum;
        public string EnumName { get; private set; }
        [JsonIgnore]
        public UnrealEnum Enum
        {
            get => _Enum;
            set
            {
                _Enum = value;
                EnumName = _Enum.Name;
            }
        }
        public UnrealFieldEnumType(string name, UnrealEnum u_enum) : base(name)
        {
            Enum = u_enum;
        }
        public override string GetTypeName() => EnumName;
    }
    public class UnrealFieldDelegateType : UnrealFieldType
    {
        public UnrealFunction Delegate { get; set; }
        public UnrealFieldDelegateType(string name, UnrealFunction u_delegate) : base(name)
        {
            Delegate = u_delegate;
        }
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
            Fields = new List<UnrealField>();
        }
    }
    public class UnrealClass : UnrealStruct
    {
        public AddressDisplayHex NativeVtable { get; set; }
        public AddressDisplayHex Constructor { get; set; } // don't need named, will always be called the same thing
        public List<UnrealFunction> Functions { get; set; }
        public uint Flags { get; set; }
        public UnrealClass(string name, int size, int alignment, uint flags)
            : base(name, size, alignment)
        {
            Flags = flags;
        }
    }
    public class UnrealClassDebug : UnrealClass
    {
        public AddressDisplayHex DebugLocation { get; set; }
        public UnrealClassDebug(string name, int size, int alignment, IntPtr location, uint flags) : base(name, size, alignment, flags)
        {
            DebugLocation = new AddressDisplayHex(location);
            Flags = flags;
        }
    }
    public class UnrealEnumEntry : UnrealPropertyBase
    {
        public long Value { get; set; }

        public UnrealEnumEntry(string name, long value) : base(name)
        {
            Value = value;
        }
    }
    public class UnrealEnum : UnrealPropertyBase
    {
        public int Size { get; set; }
        public List<UnrealEnumEntry> Entries;

        public UnrealEnum(string name, List<UnrealEnumEntry> entries, int size) : base(name)
        {
            Entries = entries;
            Size = size;
        }
    }
    public class UnrealFunction : UnrealPropertyBase
    {
        public EFunctionFlags Flags { get; set; }
        public AddressDisplayHex CppFunc { get; set; } // execFunction
        public UnrealField? ReturnValue { get; set; }
        public List<UnrealField> Parameters { get; set; }

        public UnrealFunction(string name, EFunctionFlags flags, IntPtr exec_ptr, UnrealField? ret_val, List<UnrealField> fparams) : base(name)
        {
            Flags = flags;
            CppFunc = new AddressDisplayHex(exec_ptr);
            ReturnValue = ret_val;
            Parameters = fparams;
        }
    }
    public class ExportContainer
    {
        // public String ExecutableName
        // public String MD5Hash
        public Dictionary<string, UnrealClass> classes;
        public Dictionary<string, UnrealStruct> structs;
        public Dictionary<string, UnrealEnum> enums;

        public ExportContainer()
        {
            classes = new();
            structs = new();
            enums = new();
        }
    }
}
