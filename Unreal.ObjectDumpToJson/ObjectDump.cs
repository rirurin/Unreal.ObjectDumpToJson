using Newtonsoft.Json;
using Reloaded.Hooks.Definitions;
using Reloaded.Hooks.Definitions.Structs;
using Reloaded.Hooks.Definitions.X64;
using Reloaded.Mod.Interfaces;
using System;
using System.Collections.Generic;
using System.Linq;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading.Tasks;

namespace Unreal.ObjectDumpToJson
{
    // Ported from Hi-Fi RUSH Research project
    public unsafe class ObjectDump
    {

        private Context _context;

        public ExportContainer export = new();

        private IHook<UPlayerInput_ProcessInputStack> ProcessInput;

        public ObjectDump(Context context)
        {
            _context = context;
            _context._startupScanner.AddMainModuleScan("48 8B C4 55 53 57 41 56 48 8D A8 ?? ?? ?? ?? 48 81 EC D8 02 00 00", result =>
            {
                var funcPtr = _context._baseAddress + result.Offset;
                _context._logger.WriteLine($"[RESEARCH] Found UPlayerInput::ProcessInputStack at 0x{funcPtr:X}");
                ProcessInput = _context._hooks.CreateHook<UPlayerInput_ProcessInputStack>(UPlayerInput_ProcessInputStack_Impl, funcPtr).Activate();
            });
        }

        public unsafe void UPlayerInput_ProcessInputStack_Impl(UObjectBase* self, IntPtr InputComponentStack, float DeltaTime, byte bGamePaused)
        {
            if ((GetAsyncKeyState((int)_context._config.Keycodes) & 0x1) != 0)
            {
                _context._logger.WriteLine($"Found {_context.g_objectArray->NumElements} objects in GUObjectArray");
                string file_path = Path.Combine(_context.ModLocation, "Dumps", $"{_context.ProgramName}.json");
                _context._logger.WriteLine($"[RESEARCH] Saving to {file_path}");
                for (int i = 0; i < _context.g_objectArray->NumElements; i++)
                {
                    if (i > _context.g_objectArray->ObjLastNonGCIndex) break;
                    FUObjectItem* target_item = &_context.g_objectArray->Objects[i >> 0x10][i & 0xFFFF];
                    UObjectBase* target_obj = target_item->Object;
                    string type_name = _context.g_namePool->GetString(((UObjectBase*)target_obj->ClassPrivate)->NamePrivate.pool_location);
                    // check that type isn't a duplicate
                    // Everything in GUObjectArray is derived from UObject, so it's safe to treat every instance as a class
                    if (!export.classes.TryGetValue(type_name, out _)) ExportClass(target_obj);
                }
                string json_out = JsonConvert.SerializeObject(export, Formatting.Indented); // get classes dictionary 
                Directory.CreateDirectory(Path.GetDirectoryName(file_path));
                using (StreamWriter out_file = new StreamWriter(file_path))
                    out_file.WriteLine(json_out);
            }
            ProcessInput.OriginalFunction(self, InputComponentStack, DeltaTime, bGamePaused);

        }
        public UnrealClass ExportClass(UObjectBase* instance) // UObject* representing an instance of the target class
                                                              // (e.g The instance of PlayerInput obtained from UPlayerInput::ProcessInputStack)
        {
            // Get runtime type reflection info
            UClass* type = instance->ClassPrivate;
            IntPtr vtable = instance->_vtable; // C++ vtable (don't have any other info on their names since Blueprints doesn't use them...)
            string class_name = _context.g_namePool->GetString(((UObjectBase*)type)->NamePrivate.pool_location); // get type name, not name of the instance
            IntPtr ctor = type->class_ctor; // InternalCosntructor<UClassName> => UClassName::UClassName
            int class_size = ((UStruct*)type)->properties_size;
            int class_align = ((UStruct*)type)->min_alignment;

            UnrealClass uclass_exp = new UnrealClass(class_name, class_size, class_align, (uint)type->class_flags);
            if (vtable != IntPtr.Zero) uclass_exp.NativeVtable = new(vtable);
            if (ctor != IntPtr.Zero) uclass_exp.Constructor = new(ctor);

            export.classes.Add(class_name, uclass_exp); // Add to classes hash map before getting fields as it may be recursive
            UClass* super_type = (UClass*)((UStruct*)type)->super_struct;
            if (super_type != null)
            {
                string super_type_name = _context.g_namePool->GetString(((UObjectBase*)super_type)->NamePrivate.pool_location);
                uclass_exp.SuperType = (export.classes.TryGetValue(super_type_name, out UnrealClass prev_class))
                    ? prev_class : ExportClass(super_type->class_default_obj);
            }
            uclass_exp.Functions = GetStructMethods((UStruct*)type);
            uclass_exp.Fields = GetStructFields((UStruct*)type);
            _context._logger.WriteLineAsync($"Created class {class_name} (count {export.classes.Count})");
            return uclass_exp;
        }
        public UnrealStruct ExportStruct(UStruct* type)
        {
            string struct_name = _context.g_namePool->GetString(((UObjectBase*)type)->NamePrivate.pool_location);
            int struct_size = type->properties_size;
            int struct_align = type->min_alignment;
            UnrealStruct ustruct_exp = new UnrealStruct(struct_name, struct_size, struct_align);
            export.structs.Add(struct_name, ustruct_exp);
            UStruct* super_type = type->super_struct;
            if (super_type != null)
            {
                string super_type_name = _context.g_namePool->GetString(((UObjectBase*)super_type)->NamePrivate.pool_location);
                if (!export.structs.TryGetValue(super_type_name, out UnrealStruct prev_str)) ustruct_exp.SuperType = ExportStruct(super_type);
                else ustruct_exp.SuperType = prev_str;
            }
            ustruct_exp.Fields = GetStructFields(type);
            _context._logger.WriteLineAsync($"Created struct {struct_name} (count {export.structs.Count})");
            return ustruct_exp;
        }
        public UnrealEnum ExportEnum(UEnum* type, int size)
        {
            string enum_name = _context.g_namePool->GetString(((UObjectBase*)type)->NamePrivate.pool_location);
            // Get values
            List<UnrealEnumEntry> entries = new(type->entries.arr_num);
            for (int i = 0; i < type->entries.arr_num; i++)
            {
                UEnumEntry* curr_entry = &type->entries.allocator_instance[i];
                string entry_name = _context.g_namePool->GetString(curr_entry->name.pool_location);
                entries.Add(new UnrealEnumEntry(entry_name, curr_entry->value));
            }
            UnrealEnum uenum_exp = new UnrealEnum(enum_name, entries, size);
            export.enums.Add(enum_name, uenum_exp);
            _context._logger.WriteLineAsync($"Created enum {enum_name} (count {export.enums.Count})");
            return uenum_exp;
        }
        private UnrealEnum GetEnumFieldProperty(UEnum* enum_data, int size = 1)
        {
            string enum_name = _context.g_namePool->GetString(((UObjectBase*)enum_data)->NamePrivate.pool_location);
            return (export.enums.TryGetValue(enum_name, out UnrealEnum prev_enum))
                ? prev_enum : ExportEnum(enum_data, size);
        }
        private UnrealField GetField(FProperty* curr_field)
        {
            IntPtr _debug_address = (IntPtr)curr_field;
            FFieldClass* type = ((FField*)curr_field)->class_private; // get property type info
            string field_name = _context.g_namePool->GetString(((FField*)curr_field)->name_private.pool_location);
            string type_name = _context.g_namePool->GetString(type->name.pool_location);
            int offset = curr_field->offset_internal;
            int size = curr_field->element_size;
            //_context._logger.WriteLine($"{type_name} {field_name} @ {size}");
            UnrealFieldType type_data;
            switch (type_name)
            {
                case "BoolProperty":
                    FBoolProperty* bool_prop = (FBoolProperty*)curr_field;
                    type_data = new UnrealFieldBoolType(type_name,
                        bool_prop->field_size, bool_prop->byte_offset,
                        bool_prop->byte_mask, bool_prop->field_mask
                    );
                    break;
                case "ByteProperty":
                    // If an enum cast as a byte, load that enum
                    UEnum* byte_maybe_enum = ((FByteProperty*)curr_field)->enum_data; // TEnumAsByte<EEnumName>
                    if (byte_maybe_enum != null)
                    {
                        var byte_enum_out = GetEnumFieldProperty(byte_maybe_enum);
                        type_data = new UnrealFieldEnumType(byte_enum_out.Name, byte_enum_out);
                    }
                    else type_data = new UnrealFieldType(type_name);
                    break;
                case "EnumProperty":
                    // regular enum declaration
                    // FProperty* underlying_type can be used to get the enum's size, though that's not needed as that's already provided
                    // so just get the enum data like with ByteProperty
                    UEnum* enum_data = ((FEnumProperty*)curr_field)->enum_data;
                    if (enum_data != null) // If it contains parameters exposed to blueprints
                    {
                        var enum_out = GetEnumFieldProperty(enum_data, size);
                        type_data = new UnrealFieldEnumType(enum_out.Name, enum_out);
                    }
                    else type_data = new UnrealFieldType(type_name);
                    break;
                case "StructProperty":
                    UStruct* inner_struct = (UStruct*)((FStructProperty*)curr_field)->struct_data;
                    if (inner_struct == null)
                    {
                        type_data = new UnrealFieldType(type_name);
                        break;
                    }
                    string inner_struct_name = _context.g_namePool->GetString(((UObjectBase*)inner_struct)->NamePrivate.pool_location);
                    UnrealStruct tgt_struct =
                        (export.structs.TryGetValue(inner_struct_name, out UnrealStruct prev_str))
                        ? prev_str : ExportStruct(inner_struct)
                    ;
                    type_data = new UnrealFieldStructType(type_name, tgt_struct);
                    break;
                case "ObjectProperty": // FObjectPropertyBase<UObject*>
                case "WeakObjectProperty": // FObjectPropertyBase<TWeakObjectPtr>
                case "LazyObjectProperty": // FObjectPropertyBase<TLazyObjectPtr>
                case "SoftObjectProperty": // FObjectPropertyBase<TSoftObjectPtr>
                case "InterfaceProperty": // TScriptInterface<IInterfaceName>
                case "ClassProperty": // TSubclassOf<UObject>
                case "SoftClassProperty": // TSoftClassPtr<UObject>
                    UClass* class_data = (type_name == "ClassProperty" || type_name == "SoftClassProperty")
                        ? ((FClassProperty*)curr_field)->meta : ((FObjectProperty*)curr_field)->prop_class;
                    _context._logger.WriteLine($"Got ObjectProperty {_context.g_namePool->GetString(((FField*)curr_field)->name_private.pool_location)} @ 0x{(nint)curr_field:X}");
                    if (class_data != null)
                    {
                        string inner_class_name = _context.g_namePool->GetString(((UObjectBase*)class_data)->NamePrivate.pool_location);
                        UnrealClass tgt_class =
                            (export.classes.TryGetValue(inner_class_name, out UnrealClass prev_class))
                            ? prev_class : ExportClass(class_data->class_default_obj)
                        ;
                        type_data = new UnrealFieldClassType(type_name, tgt_class);
                    }
                    else type_data = new UnrealFieldType(type_name);
                    break;

                case "ArrayProperty": // TArray<Type>
                case "SetProperty": // TSet<Type>
                    FProperty* inner_prop = ((FArrayProperty*)curr_field)->inner;
                    UnrealField inner_field = GetField(inner_prop);
                    type_data = new UnrealFieldArrayType(type_name, inner_field);
                    break;
                case "MapProperty": // TMap<KeyType, ValueType>
                    FProperty* key_prop = ((FMapProperty*)curr_field)->key_prop;
                    FProperty* value_prop = ((FMapProperty*)curr_field)->value_prop;
                    type_data = new UnrealFieldMapType(type_name, GetField(key_prop), GetField(value_prop));
                    break;
                case "DelegateProperty": // DECLARE_[DYNAMIC]_DELEGATE_XParams(this, ...)
                case "MulticastDelegateProperty":
                case "MulticastInlineDelegateProperty": // DECLARE_[DYNAMIC]_MULTICAST_DELEGATE(this, ...)
                case "MulticastSparseDelegateProperty": // DECLARE_[DYNAMIC]_MULTICAST_SPARSE_DELEGATE(this, ...)
                    UFunction* func = ((FDelegateProperty*)curr_field)->func;
                    if (func == null)
                    {
                        type_data = new UnrealFieldType(type_name);
                        break;
                    }
                    type_data = new UnrealFieldDelegateType(type_name, ExportFunction(func));
                    break;
                default:
                    type_data = new UnrealFieldType(type_name);
                    break;
            }
            return new UnrealField(field_name, type_data, offset, size, curr_field->property_flags);
        }

        private UnrealFunction ExportFunction(UFunction* func)
        {
            string func_name = _context.g_namePool->GetString(((UObjectBase*)func)->NamePrivate.pool_location);
            // Get parameters
            FProperty* curr_param = (FProperty*)((UStruct*)func)->child_properties;
            UnrealField? return_val = null;
            List<UnrealField> func_params = new();
            while (curr_param != null)
            {
                UnrealField field_out = GetField(curr_param);
                // check that it's the return value
                if ((field_out.Flags & EPropertyFlags.CPF_ReturnParm) != 0) return_val = field_out;
                else func_params.Add(field_out);
                curr_param = (FProperty*)curr_param->_super.next;
            }
            return new UnrealFunction(func_name, func->func_flags, func->exec_func_ptr, return_val, func_params);
        }
        private List<UnrealField> GetStructFields(UStruct* target)
        {
            List<UnrealField> fields = new();
            FProperty* curr_field = (FProperty*)target->child_properties;
            while (curr_field != null)
            {
                fields.Add(GetField(curr_field));
                curr_field = (FProperty*)curr_field->_super.next;
            }
            return fields;
        }
        private List<UnrealFunction> GetStructMethods(UStruct* target)
        {
            List<UnrealFunction> methods = new();
            UField* curr_field = target->children;
            while (curr_field != null)
            {
                methods.Add(ExportFunction((UFunction*)curr_field));
                curr_field = curr_field->next;
            }
            return methods;
        }

        [Function(CallingConventions.Microsoft)]
        public delegate void UPlayerInput_ProcessInputStack(UObjectBase* self, IntPtr InputComponentStack, float DeltaTime, byte bGamePaused);

        [DllImport("user32.dll")]
        public static extern ushort GetAsyncKeyState(int vKey);
    }

}
