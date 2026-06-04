using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text.Json;
using p3rpc.commonmodutils;
using Reloaded.Mod.Interfaces;
using RyoTune.Reloaded;
using UE.Toolkit.Core.Types.Unreal.Factories.Interfaces;
using UE.Toolkit.Core.Types.Unreal.UE5_4_4;

namespace Unreal.ObjectDumpToJson
{
    // ReSharper disable once ClassNeverInstantiated.Global
    public unsafe class Dumper(DumperContext context, Dictionary<string, ModuleBase<DumperContext>> modules)
        : ModuleBase<DumperContext>(context, modules)
    {
        private UnrealDataContainer ToExport = new();

        public override void Register() {}

        public override void OnConfigUpdated(IConfigurable newConfig)
        {
            base.OnConfigUpdated(newConfig);
            var objects = _context._toolkitObjects.GUObjectArray;
            Log.Debug($"Found {objects.NumElements} objects in GUObjectArray");
            var filePath = Path.Combine(_context._modLocation, "Dumps", $"{_context._programName}.json");
                
            var sw = new Stopwatch();
            sw.Start();
            for (var i = 0; i < objects.NumElements; i++)
            {
                var Obj = objects.IndexToObject(i);
                if (Obj == null) continue;
                // check that type isn't a duplicate
                // Everything in GUObjectArray is derived from UObject, so it's safe to treat every instance as a class
                if (!ToExport.Classes.ContainsKey(Obj.ClassPrivate.NamePrivate.ToString())) ExportClass(Obj);
            }
            Directory.CreateDirectory(Path.GetDirectoryName(filePath)!);
            using (var outFile = new StreamWriter(filePath))
                outFile.WriteLine(JsonSerializer.Serialize(ToExport));
            sw.Stop();
            Log.Information($"""
                             Dumped {ToExport.Classes.Count} classes, {ToExport.Structs.Count} structs and {ToExport.Enums.Count} enums
                             Completed in {sw.ElapsedMilliseconds.ToString()} ms, saved to {filePath}
                             """);
            ToExport.Clear();
        }
        
        // IUObject representing an instance of the target class
        private UnrealClass ExportClass(IUObject instance)
        {
            // Get runtime type reflection info
            var type = instance.ClassPrivate;
            var vtable = instance.VTable; // C++ vtable (don't have any other info on their names since Blueprints doesn't use them...)
            var ctor = type.Constructor;
            Log.Information($"Exporting Class: {type.NamePrivate.ToString()}");

            var newClass = new UnrealClass(
                type.NamePrivate.ToString(), 
                type.PropertiesSize, 
                type.MinAlignment,
                (uint)type.ClassFlags);
            
            if (vtable != nint.Zero) newClass.NativeVtable = new(vtable);
            if (ctor != nint.Zero) newClass.Constructor = new(ctor);
            ToExport.Classes.Add(type.NamePrivate.ToString(), newClass);
            var super = type.SuperStruct;
            if (super != null)
            {
                ToExport.Classes.TryGetValue(super.NamePrivate.ToString(), out var superClass);
                newClass.SuperType = superClass ?? ExportClass(_context._toolkitFactory.CreateUClass(super.Ptr).ClassDefaultObject!);
            }
            newClass.Functions = GetStructMethods(type);
            newClass.Fields = GetStructFields(type);
            return newClass;
        }
        
        private UnrealStruct ExportStruct(IUStruct type)
        {
            var structName = type.NamePrivate.ToString();
            Log.Information($"Exporting Struct: {structName}");
            var newStruct = new UnrealStruct(structName, type.PropertiesSize, type.MinAlignment);
            ToExport.Structs.Add(structName, newStruct);
            var super = type.SuperStruct;
            if (super != null)
            {
                ToExport.Structs.TryGetValue(super.NamePrivate.ToString(), out var superStruct);
                newStruct.SuperType = superStruct ?? ExportStruct(super);
            }
            newStruct.Fields = GetStructFields(type);
            return newStruct;
        }
        
        private UnrealEnum ExportEnum(IUEnum type, int size)
        {
            var enumName = type.NamePrivate.ToString();
            Log.Information($"Exporting Enum: {enumName}");
            List<UnrealEnumEntry> entries = new(type.Names.ArrayNum);
            for (var i = 0; i < type.Names.ArrayNum; i++)
            {
                var currEntry = &type.Names.AllocatorInstance[i];
                entries.Add(new UnrealEnumEntry(currEntry->Key.ToString(), currEntry->Value));
            }
            var newEnum = new UnrealEnum(enumName, entries, size);
            ToExport.Enums.Add(enumName, newEnum);
            return newEnum;
        }
        
        private UnrealEnum GetEnumProperty(IUEnum enumData, int size = 1)
        {
            var enumName = enumData.NamePrivate.ToString();
            ToExport.Enums.TryGetValue(enumName, out var targetEnum);
            return targetEnum ?? ExportEnum(enumData, size);
        }

        private UnrealFieldBoolType CreateBoolPropertyType(IFProperty property)
        {
            var boolProperty = _context._toolkitFactory.CreateFBoolProperty(property.Ptr);
            return new UnrealFieldBoolType(property.ClassPrivate.Name,
                boolProperty.FieldSize, boolProperty.ByteOffset,
                boolProperty.ByteMask, boolProperty.FieldMask
            );
        }

        private UnrealFieldEnumType CreateEnumProperty(IUEnum uEnum, int size = 1)
        {
            var byteEnumOut = GetEnumProperty(uEnum, size);
            return new UnrealFieldEnumType(byteEnumOut.Name, byteEnumOut);
        }

        private UnrealFieldType CreateBytePropertyType(IFProperty property)
            => _context._toolkitFactory.CreateFByteProperty(property.Ptr).Enum switch
            {
                null => new UnrealFieldType(property.ClassPrivate.Name),
                var v => CreateEnumProperty(v)
            };

        private UnrealFieldType CreateEnumPropertyType(IFProperty property)
            => _context._toolkitFactory.CreateFEnumProperty(property.Ptr).Enum switch
            {
                null => new UnrealFieldType(property.ClassPrivate.Name),
                var v => CreateEnumProperty(v, property.ElementSize)
            };
        
        private UnrealFieldType CreateStructPropertyType(IFProperty property)
        {
            var structData = _context._toolkitFactory.CreateFStructProperty(property.Ptr).Struct;
            if (structData == null) return new UnrealFieldType(property.ClassPrivate.Name);
            ToExport.Structs.TryGetValue(structData.NamePrivate.ToString(), out var target);
            return new UnrealFieldStructType(property.ClassPrivate.Name, target ?? ExportStruct(structData));
        }
        
        private UnrealFieldType CreateObjectPropertyType(IFProperty property)
        {
            var classData = property.ClassPrivate.Name switch
            {
                "ClassProperty" or "SoftClassProperty" => _context._toolkitFactory.CreateFClassProperty(property.Ptr)
                    .MetaClass,
                _ => _context._toolkitFactory.CreateFObjectProperty(property.Ptr).PropertyClass
            };
            if (classData == null) return new UnrealFieldType(property.ClassPrivate.Name);
            ToExport.Classes.TryGetValue(classData.NamePrivate.ToString(), out var target);
            return new UnrealFieldClassType(property.ClassPrivate.Name, target ?? ExportClass(classData.ClassDefaultObject!));
        }
        
        private UnrealFieldArrayType CreateArrayPropertyType(IFProperty property)
        {
            var Map = _context._toolkitFactory.CreateFArrayProperty(property.Ptr);
            return new UnrealFieldArrayType(property.ClassPrivate.Name, GetField(Map.Inner));
        }
        
        private UnrealFieldMapType CreateMapPropertyType(IFProperty property)
        {
            var Map = _context._toolkitFactory.CreateFMapProperty(property.Ptr);
            return new UnrealFieldMapType(property.ClassPrivate.Name, GetField(Map.KeyProp), GetField(Map.ValueProp));
        }
        
        private UnrealFieldType CreateDelegatePropertyType(IFProperty property)
        {
            // TODO: Delegate
            return new UnrealFieldType(property.ClassPrivate.Name);
        }
        
        private UnrealField GetField(IFProperty field)
        {
            // Log.Information($"\tField {field.NamePrivate}");
            var FieldType = field.ClassPrivate; // get property type info
            var typeData = FieldType.Name switch
            {
                "BoolProperty" => CreateBoolPropertyType(field),
                "ByteProperty" => CreateBytePropertyType(field),
                "EnumProperty" => CreateEnumPropertyType(field),
                "StructProperty" => CreateStructPropertyType(field), 
                "ObjectProperty" or // FObjectPropertyBase<UObject*>
                    "WeakObjectProperty" or // FObjectPropertyBase<TWeakObjectPtr> 
                    "LazyObjectProperty" or // FObjectPropertyBase<TLazyObjectPtr> 
                    "SoftObjectProperty" or // FObjectPropertyBase<TSoftObjectPtr>
                    "InterfaceProperty" or // TScriptInterface<IInterfaceName>
                    "ClassProperty" or // TSubclassOf<UObject>
                    "SoftClassProperty" => CreateObjectPropertyType(field), // TSoftClassPtr<UObject>
                "ArrayProperty" or // TArray<Type>
                    "SetProperty" => CreateArrayPropertyType(field), // TSet<Type>
                "MapProperty" => CreateMapPropertyType(field), // TMap<KeyType, ValueType>
                "DelegateProperty" or // DECLARE_[DYNAMIC]_DELEGATE_XParams(this, ...)
                    "MulticastDelegateProperty" or
                    "MulticastInlineDelegateProperty" or // DECLARE_[DYNAMIC]_MULTICAST_DELEGATE(this, ...)
                    "MulticastSparseDelegateProperty" => CreateDelegatePropertyType(field), // DECLARE_[DYNAMIC]_MULTICAST_SPARSE_DELEGATE(this, ...)
                var name => new UnrealFieldType(name)
            };
            return new UnrealField(field.NamePrivate, typeData, field.Offset_Internal, field.ElementSize, field.PropertyFlags);
        }

        private UnrealFunction ExportFunction(IUFunction func)
        {
            UnrealField? Return = null;
            List<UnrealField> FnParams = [];
            foreach (var Field in func.ChildProperties)
            {
                var Property = _context._toolkitFactory.CreateFProperty(Field.Ptr);
                var FieldCnv = GetField(Property);
                if ((Property.PropertyFlags & EPropertyFlags.CPF_ReturnParm) != 0) Return = FieldCnv;
                else FnParams.Add(FieldCnv);
            }
            return new UnrealFunction(func.NamePrivate.ToString(), func.FunctionFlags, func.FunctionPtr, Return, FnParams);
        }

        private List<UnrealField> GetStructFields(IUStruct target)
            => target.PropertyLink.Select(Field => GetField(_context._toolkitFactory.CreateFProperty(Field.Ptr)))
                .ToList();

        private List<UnrealFunction> GetStructMethods(IUStruct target)
            => target.Children.Select(Child => ExportFunction(_context._toolkitFactory.CreateUFunction(Child.Ptr)))
                .ToList();
    }
}
