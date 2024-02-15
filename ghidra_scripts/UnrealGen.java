/* ###
 * IP: GHIDRA
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.stream.JsonReader;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ArrayDataType;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.Category;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DoubleDataType;
import ghidra.program.model.data.DataTypeManager;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.data.FloatDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.InvalidDataTypeException;
import ghidra.program.model.data.LongLongDataType;
import ghidra.program.model.data.Pointer64DataType;
import ghidra.program.model.data.ShortDataType;
import ghidra.program.model.data.StringDataType;
import ghidra.program.model.data.Structure;
import ghidra.program.model.data.TypedefDataType;
import ghidra.program.model.data.UnsignedIntegerDataType;
import ghidra.program.model.data.UnsignedLongLongDataType;
import ghidra.program.model.data.UnsignedShortDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.CircularDependencyException;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;
import ghidra.program.model.data.StructureDataType;

public class UnrealGen extends GhidraScript {
	
	private Gson gson;
	public Utilities utility;
	public UnrealTypeFactory unrealTypes;
	
	private HashMap<String, UnrealClass> LoadedClasses;
	private HashMap<String, UnrealStruct> LoadedStructs;
	private HashMap<String, UnrealEnum> LoadedEnums;
	
	private CategoryPath UserTypes;
	private CategoryPath BuiltInTypes;
	
	private UnrealStructDeserializeHelper structDeser;
	private Queue<UnrealArrayField> PostRegisterCreateArrays;
	
	public class Utilities {
		private DataType AddDataTypeModifier(DataType curr, String token) {
			DataType res;
			switch (token.charAt(0)) {
				case '*':
					res = new Pointer64DataType(curr);
					break;
				case '[':
					Pattern p = Pattern.compile("\\d+");
					Matcher m = p.matcher(token);
					m.find();
					int arrayEntries = Integer.parseInt(m.group()); // this operation should be safe
					res = new ArrayDataType(curr, arrayEntries, curr.getLength());
					break;
				default:
					throw new IllegalArgumentException("Invalid starting character " + token.charAt(0) + " (this error should not appear)");
			}
			return res;
		}
		
		private DataType MakeBaseDataType(String dtPure) {
			DataType res;
			switch (dtPure) {
			// Built in types
			case "char" : res = new CharDataType(); break;
			case "byte" : res = new ByteDataType(); break;
			case "short" : res = new ShortDataType(); break;
			case "ushort": res = new UnsignedShortDataType(); break;
			case "int" : res = new IntegerDataType(); break;
			case "uint" : res = new UnsignedIntegerDataType(); break;
			case "long" : case "longlong" : res = new LongLongDataType(); break;
			case "ulong" : case "ulonglong" : res = new UnsignedLongLongDataType(); break;
			case "void": res = new VoidDataType(); break;
			case "string": res = new StringDataType(); break;
			case "float": res = new FloatDataType(); break;
			case "double": res = new DoubleDataType(); break;
			default:
				// Custom type - check Unreal types, then custom game types 
				res = currentProgram.getDataTypeManager().getDataType(BuiltInTypes, dtPure);
				if (res == null) res = currentProgram.getDataTypeManager().getDataType(UserTypes, dtPure);
				//if (res == null) throw new IllegalArgumentException("Custom type '" +  dtPure + "' does not exist"); break;
			}
			return res;
		}
		
		private DataType GetDataType(String dt) {
			DataType res;
			
			Pattern p = Pattern.compile("\\[\\d+\\]|\\*");
			Matcher m = p.matcher(dt);
			String dtPure = p.split(dt)[0]; // base type name
			Queue<String> modifiers = new ArrayDeque<>();
			modifiers.add(dtPure);
			while (m.find()) {
				modifiers.add(m.group());
			}
			res = MakeBaseDataType(modifiers.poll());
			if (res != null) {				
				while (!modifiers.isEmpty()) {
					res = AddDataTypeModifier(res, modifiers.poll());
				}
			}
			return res;
		}
		
		public void CreateDataType(CategoryPath cPath, String name, Supplier<? extends DataType> factoryDelegate) {
			if (cPath == null) {
				throw new IllegalStateException("Category path must be set");
			}
			DataType outDataType = currentProgram.getDataTypeManager().getDataType(cPath, name);
			if (outDataType == null) {
				outDataType = factoryDelegate.get();
				currentProgram.getDataTypeManager().addDataType(outDataType, null);
			}
		}
		
		private void clearListingForDataType(Address addr, DataType typeToFit) throws CancelledException, MemoryAccessException {
			if (typeToFit.equals(new StringDataType())) {
				// string has no defined length, so go forward through the listing until we hit a 00 byte
				Data a = currentProgram.getListing().getDataAt(addr);
				int i = 0;
				byte av;
				do {
					av = a.getByte(i);
					i++;
				} while (av != 0);
				i--;
				clearListing(addr, addr.add(i));
			} else {
				// type with defined length
				clearListing(addr, addr.add(typeToFit.getLength() - 1));
			}
		}
		
		private Data addDataTypeToListing(Address listingLocation, DataType dt) throws CancelledException {
			Data out = null;
			try {
				out = currentProgram.getListing().getDataAt(listingLocation);
				if (!out.getDataType().equals(dt)) {
					clearListingForDataType(listingLocation, dt);
					out = currentProgram.getListing().createData(listingLocation, dt);
				} // else Type already exists at this location
			} catch (MemoryAccessException | CodeUnitInsertionException | NullPointerException e) {
				println("[TypeFinder::addDataTypeToListing] " + e.getMessage());
			}
			return out;
		}
		
		private String Word(String text) {
			if (text == null) throw new NullPointerException("No data provided for Word");
			return text.split(" ")[0];
		}
		
		private String Value(Data dt) {
			if (dt == null) throw new NullPointerException("No data provided for Value");
			return dt.getValue().toString();
		}
		
		private Data Deref(Data dt) {
			if (dt == null) throw new NullPointerException("No data provided for Deref");
			Address derefAddr = toAddr(Value(dt));
			Data out = currentProgram.getListing().getDataAt(derefAddr);
			if (out == null) throw new NullPointerException("Dereferenced to an invalid location: " + dt.getAddress().toString() + " -> " + derefAddr);
			return out;
		}
		
		private String Deref(String dt) {
			Pattern p = Pattern.compile("\\*|\\*64");
			Matcher m = p.matcher(dt);
			String dtPure = p.split(dt)[0]; // base type name
			
			String out = null;
			LinkedList<String> parts = new LinkedList<>();
			parts.add(Word(dtPure));
			while (m.find()) {
				parts.add(Word(m.group()));
			}
			parts.pollLast();
			while (!parts.isEmpty()) {
				if (out == null) out = parts.poll();
				else out += parts.poll();
			}
			return out;
		}
		
		public void AddToStruct(StructureDataType struct, String name, int offset, DataType type) {
			struct.replaceAtOffset(offset, type, type.getLength(), name, null);
		}
	}
	
	public class UnrealTypeFactory {
		
		// Create type with defined structure creation delegate
		private DataType GetOrCreate_Inner(String name, java.util.function.Function<String, DataType> createFunc, CategoryPath path) {
			DataType tgt_type = currentProgram.getDataTypeManager().getDataType(path, name);
			if (tgt_type == null) tgt_type = createFunc.apply(name);
			return tgt_type;
		}
		private DataType GetBuiltInOrCreate(String name, java.util.function.Function<String, DataType> createFunc) { return GetOrCreate_Inner(name, createFunc, BuiltInTypes); }
		private DataType GetUserTypeOrCreate(String name, java.util.function.Function<String, DataType> createFunc) { return GetOrCreate_Inner(name, createFunc, UserTypes); }
		
		// Create blank structure with size defined only
		private DataType GetOrCreate_Inner(String name, int size, CategoryPath path) {
			DataType tgt_type = currentProgram.getDataTypeManager().getDataType(path, name);
			if (tgt_type == null) CreateOpaqueType(name, path, size);
			return tgt_type;
		}
		private DataType CreateOpaqueType(String name, CategoryPath path, int size) {
			StructureDataType new_type = new StructureDataType(path, name, size);
			currentProgram.getDataTypeManager().addDataType(new_type, null);
			return new_type;
		}
		private DataType GetBuiltInOrCreate(String name, int size) { return GetOrCreate_Inner(name, size, BuiltInTypes); }
		private DataType GetUserTypeOrCreate(String name, int size) { return GetOrCreate_Inner(name, size, UserTypes); }
	}
	
	public class UnrealStructDeserializeHelper {
		public List<UnrealField> GetFields(JsonObject obj) {
			JsonArray fields_json = obj.get("Fields").getAsJsonArray();
			List<UnrealField> fields = new LinkedList<>();
			for (int i = 0; i < fields_json.size(); i++) {
				fields.add(gson.fromJson(fields_json.get(i), UnrealField.class)); // Field data TODO
			}
			return fields;
		}
		public List<UnrealFunction> GetMethods(JsonObject obj) { // for class only
			JsonArray methods_json = obj.get("Functions").getAsJsonArray();
			List<UnrealFunction> methods = new LinkedList<>();
			for (int i = 0; i < methods_json.size(); i++) {
				methods.add(gson.fromJson(methods_json.get(i), UnrealFunction.class));
			}
			return methods;
		}
	}
	
	public class UnrealClass extends UnrealStruct {
		public int Flags;
		public List<UnrealFunction> Functions;
		public Address Vtable;
		public Address Ctor;
		public Boolean IsActor;
		public Boolean DerivedChecked;
		
		public UnrealClass(
				String Name, String SuperName, int Size, int Alignment, int Flags, 
				List<UnrealField> Fields, Address Vtable, Address Ctor, List<UnrealFunction> Methods) {
			super(Name, SuperName, Size, Alignment, Fields);
			this.Flags = Flags;
			this.Vtable = Vtable;
			this.Ctor = Ctor;
			this.IsActor = false;
			this.DerivedChecked = false;
			this.Functions = Methods;
		}
		@Override public void RegisterObject() { LoadedClasses.put(Name, this); }
		@Override public String GetProperName() { return ((IsActor) ? "A" : "U") + Name; }
		
		private void CheckDerivedFromActor() {
			UnrealClass curr_class = this; // check the current class if it's Actor (so AActor doesn't get skipped)
			while (true) {
				// if the current superclass isn't a subtype of Actor, we won't waste our time going up the
				// inheritance tree to get the same result
				// Additionally, stop if we're at UObject (super_type would be null)
				if ((curr_class.DerivedChecked && !curr_class.IsActor) || curr_class.SuperName == null) break;
				if ((curr_class.DerivedChecked && curr_class.IsActor) || curr_class.Name.equals("Actor")) { // yes, this is Actor, or a derivative
					this.IsActor = true;
					break;
				}
				curr_class = LoadedClasses.get(curr_class.SuperName); // get derived class
			}
			this.DerivedChecked = true;
		}
		
		public Structure InitializeDataType() {
			CheckDerivedFromActor();
			return super.InitializeDataType();
		}
		
		public Structure GetStructDTMData(String name) { return LoadedClasses.get(name).struct_data; }
		
		public void CommitMethodData() {
			try {
				// Create class in symbol tree
				Namespace exist_space = currentProgram.getSymbolTable().getNamespace(GetProperName(), null); // check for already existing namespaces
				GhidraClass new_class;
				if (exist_space != null) new_class = currentProgram.getSymbolTable().convertNamespaceToClass(exist_space);
				else new_class = currentProgram.getSymbolTable().createClass(null, GetProperName(), SourceType.ANALYSIS);
				// Label vtable and constructor
				currentProgram.getSymbolTable().createLabel(Vtable, "vtable", new_class, SourceType.ANALYSIS);
				Function int_ctor = currentProgram.getListing().getFunctionAt(Ctor);
				if (int_ctor == null) int_ctor = createFunction(Ctor, null);
				int_ctor.setName("InternalConstructor_" + GetProperName(), SourceType.ANALYSIS);
				int_ctor.setParentNamespace(new_class);
				// Label methods
				// TODO - use least derived class as the name, then anything else that uses that same function is saved as a label
				int i = 0;
				for (var func : Functions) {
					// create function
					Function func_listing = currentProgram.getListing().getFunctionAt(func.ExecFuncAddr);
					if (func_listing == null) func_listing = createFunction(func.ExecFuncAddr, null);
					// label function (use label so that function can have multiple names)
					String adj_name = func.Name.replace(" ", "_"); // Ghidra doesn't allow function/symbol names with spaces
					try { currentProgram.getSymbolTable().createLabel(func.ExecFuncAddr, "exec" + adj_name, new_class, SourceType.ANALYSIS); } 
					catch (InvalidInputException e) {
						// what the fuck is "ｿ0､0・・､0・_\u0000B\u0000e\u0000a\u0000t\u0000_\u0000_" supposed to be hifi rush
						currentProgram.getSymbolTable().createLabel(func.ExecFuncAddr, "exec" + GetProperName() + "_func" + i, new_class, SourceType.ANALYSIS);
					}
					i++;
					/*
					func_listing.setName("exec" + func.Name, SourceType.ANALYSIS);
					func_listing.setParentNamespace(new_class);
					*/
				}
			} catch (DuplicateNameException | InvalidInputException | CircularDependencyException e) { throw new IllegalArgumentException("skill issue : " + e.getMessage()); }
		}
	}
	public class UnrealStruct {
		public String Name;
		public String SuperName;
		public int Size;
		public int Alignment;
		public List<UnrealField> Fields;
		
		public Structure struct_data;
		
		public UnrealStruct(String Name, String SuperName, int Size, int Alignment,
			List<UnrealField> Fields) {
			this.Name = Name;
			this.SuperName = SuperName;
			this.Size = Size;
			this.Alignment = Alignment;
			this.Fields = Fields;
		}
		public void RegisterObject() { LoadedStructs.put(Name, this); }
		
		// prepend F (Unreal naming convention for objects not derived from UObject)
		public String GetProperName() { return "F" + Name; }
		
		public Structure InitializeDataType() {
			// when added to DTM, StructureDataType is converted to Structure. We get that new structure reference so that we can edit it later on
			struct_data = (Structure)currentProgram.getDataTypeManager().addDataType(new StructureDataType(UserTypes, GetProperName(), Size), null);
			return struct_data;
		}
		
		public Structure GetStructDTMData(String name) { return LoadedStructs.get(name).struct_data; }
		
		public void CommitFieldData() {
			println("Committing field data for " + Name);
			if (SuperName != null) struct_data.replaceAtOffset(0, GetStructDTMData(SuperName), GetStructDTMData(SuperName).getLength(), "Super", null);
			for (var field : Fields) { field.AddToStructure(struct_data); }
		}
		public static String GetSuperType(JsonElement superElement) { return (superElement.isJsonNull()) ? null : superElement.getAsString(); }
	}
	public class UnrealClassDeserializer implements JsonDeserializer<UnrealClass> {
		@Override
		public UnrealClass deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject obj = json.getAsJsonObject();
			var fields = structDeser.GetFields(obj); // get fields
			var methods = structDeser.GetMethods(obj); // get methods
			return new UnrealClass(
				obj.get("Name").getAsString(),
				UnrealStruct.GetSuperType(obj.get("SuperTypeName")),
				obj.get("Size").getAsInt(),
				obj.get("Alignment").getAsInt(),
				obj.get("Flags").getAsInt(),
				fields,
				toAddr(obj.get("NativeVtable").getAsJsonObject().get("Value").getAsString()), // get native vtable and constructor (they are their own objects)
				toAddr(obj.get("Constructor").getAsJsonObject().get("Value").getAsString()),
				methods
			);
		}
	}
	public class UnrealStructDeserializer implements JsonDeserializer<UnrealStruct> {
		@Override
		public UnrealStruct deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject obj = json.getAsJsonObject();
			var fields = structDeser.GetFields(obj); // Get fields
			return new UnrealStruct(
				obj.get("Name").getAsString(),
				UnrealStruct.GetSuperType(obj.get("SuperTypeName")),
				obj.get("Size").getAsInt(),
				obj.get("Alignment").getAsInt(),
				fields
			);
		}
	}
	public class UnrealFunction {
		public String Name;
		public Address ExecFuncAddr;
		public int Flags;
		public UnrealField ReturnValue;
		public List<UnrealField> Parameters;
		
		public UnrealFunction(String Name, Address ExecFuncAddr, int Flags, 
			UnrealField ReturnValue, List<UnrealField> Parameters) {
			this.Name = Name;
			this.ExecFuncAddr = ExecFuncAddr;
			this.Flags = Flags;
			this.ReturnValue = ReturnValue;
			this.Parameters = Parameters;
		}
		
		@Override
		public String toString() {
			String ret_str = super.toString() + ": " + Name + ", calls " + ExecFuncAddr.toString() + "\nReturn Value: " + ReturnValue + "\nParameters:";
			for (var param : Parameters) {
				ret_str += "\n\t" + param.toString();
			}
			return ret_str;
		}
	}
	public class UnrealFunctionDeserializer implements JsonDeserializer<UnrealFunction> {
		@Override public UnrealFunction deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject json_obj = json.getAsJsonObject();
			List<UnrealField> Parameters = new LinkedList<>();
			UnrealField return_val = (json_obj.get("ReturnValue").isJsonNull()) 
				? null : gson.fromJson(json_obj.get("ReturnValue").getAsJsonObject(), UnrealField.class);
			JsonArray params = json_obj.get("Parameters").getAsJsonArray();
			for (int i = 0; i < params.size(); i++) Parameters.add(gson.fromJson(params.get(i), UnrealField.class));
			return new UnrealFunction(
				json_obj.get("Name").getAsString(),
				toAddr(json_obj.get("CppFunc").getAsJsonObject().get("Value").getAsString()),
				json_obj.get("Flags").getAsInt(),
				return_val, 
				Parameters
			);
		}
	}
	public abstract class UnrealField { // Unreal Field Base class
		public String Name;
		public long Flags;
		public int Size;
		public int Offset;
		
		public void InitializeCommonFields(String Name, long Flags, int Size, int Offset) {
			this.Name = Name;
			this.Flags = Flags;
			this.Size = Size;
			this.Offset = Offset;
		}
		@Override
		public String toString() { return super.toString() + ": " + Name + " @ " + Offset + ", Size " + Size; }
		public abstract DataType GetType();
		public void AddToStructure(Structure struct) { 
			println("Adding " + Name + " ( " + this.getClass().getName() + ") to " + struct.getName() + " @ " + Offset);
			struct.replaceAtOffset(Offset, GetType(), GetType().getLength(), Name, null); 
		}
	}
	public UnrealField UnrealFieldFactory(JsonObject field, JsonObject type) {
		 String type_name = type.get("Name").getAsString();
		 //println(type_name);
		 UnrealField new_field;
		 switch (type_name) {
		 	// simple, Ghidra built in types
		 	case "Int8Property":
		 	case "ByteProperty":
		 		new_field = new UnrealInt8Field(); break;
		 	case "Int16Property": new_field = new UnrealInt16Field(); break;
		 	case "IntProperty": new_field = new UnrealIntField(); break;
		 	case "Int64Property": new_field = new UnrealInt64Field(); break;
		 	case "UInt16Property": new_field = new UnrealUint16Field(); break;
		 	case "UInt32Property": new_field = new UnrealUint32Field(); break;
		 	case "UInt64Property": new_field = new UnrealUint64Field(); break;
		 	case "FloatProperty": new_field = new UnrealFloatField(); break;
		 	case "DoubleProperty": new_field = new UnrealDoubleField(); break;
		 	// bool property. while using built in types, it can often be bundled together into a bitflag, requiring the creation of a bitfield
		 	
		 	case "BoolProperty":
		 		int field_mask = type.get("FieldMask").getAsInt();
		 		if (field_mask == 255) new_field = new UnrealNativeBoolField(); // bool
		 		else new_field = new UnrealBitflagBoolField(type.get("ByteMask").getAsInt(), type.get("FieldMask").getAsInt(), type.get("FieldSize").getAsInt()); // bitflag
		 		break;
		 	// uses Unreal built in types (e.g FString)
		 	case "NameProperty": new_field = new UnrealNameField(); break; // FName, size 0x8
		 	case "StrProperty": new_field = new UnrealStringField(); break; // FString, size 0x10
		 	case "TextProperty": new_field = new UnrealTextField(); break; // FText, size 0x18
		 	// uses Unreal user defined types (e.g UObject*)
		 	case "StructProperty": new_field = new UnrealStructField(type.get("TypeName").getAsString()); break; // FStruct
		 	case "ObjectProperty": new_field = new UnrealObjectField(type.get("TypeName").getAsString()); break; // UObject*
		 	case "WeakObjectProperty": new_field = new UnrealWeakObjectField(type.get("TypeName").getAsString()); break; // TWeakObjectPtr<UObject>, size 0x8
		 	case "LazyObjectProperty": new_field = new UnrealLazyObjectField(type.get("TypeName").getAsString()); break; // TLazyObjectPtr<UObject>, size 0x1c
		 	case "SoftObjectProperty": new_field = new UnrealSoftObjectField(type.get("TypeName").getAsString()); break; // TSoftObjectPtr<UObject>, size 0x28
		 	case "InterfaceProperty": new_field = new UnrealInterfaceField(type.get("TypeName").getAsString()); break; // TScriptInterface<IInterfaceName>, size 0x10
		 	case "ClassProperty": new_field = new UnrealClassField(type.get("TypeName").getAsString()); break; // TSubclassOf<UClassName>, size 0x8
		 	case "SoftClassProperty": new_field = new UnrealSoftClassField(type.get("TypeName").getAsString(), field.get("Size").getAsInt());
		 	case "EnumProperty": new_field = new UnrealInt8Field(); break;
		 	// template types (e.g TArray<AActor*>)
		 	case "ArrayProperty": new_field = new UnrealArrayField(type.get("InnerTypeName").getAsString()); break; // size 0x10
		 	case "SetProperty": new_field = new UnrealSetField(type.get("InnerTypeName").getAsString()); break; // size 0x50
		 	case "MapProperty": new_field = new UnrealMapField(type.get("KeyName").getAsString(),type.get("ValueName").getAsString()); break; // size 0x50
		 	case "FieldPathProperty": // TFieldPath<FField> TODO (deal with this later, requires  editing Unreal Utilities)
		 		new_field = new UnrealInt8Field();
		 		break;
		 	// TODO delegate types
		 	case "DelegateProperty": 
		 	case "MulticastDelegateProperty":
		 	case "MulticastInlineDelegateProperty":
		 	case "MulticastSparseDelegateProperty":
		 		new_field = new UnrealDelegateField(type.get("Delegate").getAsJsonObject()); 
		 		break;
	 		default:
	 			// probably an enum
	 			if (LoadedEnums.get(type_name) != null) { new_field = new UnrealEnumField(type_name); break; }
		 		throw new IllegalArgumentException("Unimplemented field type " + type_name);
		 }
		 new_field.InitializeCommonFields(
			field.get("Name").getAsString(),
			field.get("Flags").getAsLong(),
			field.get("Size").getAsInt(),
			Integer.parseInt(field.get("Offset").getAsJsonObject().get("Value").getAsString().substring(2), 16)
		 );
		 return new_field;
	}
	public class UnrealFieldDeserializer implements JsonDeserializer<UnrealField> {
		@Override
		public UnrealField deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject json_obj = json.getAsJsonObject();
			return UnrealFieldFactory(json_obj, json_obj.get("Type").getAsJsonObject());
		}
	}
	
	public class UnrealByteField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("byte"); }
	}
	public class UnrealInt8Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("byte"); }
	}
	public class UnrealInt16Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("short"); }
	}
	public class UnrealIntField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("int"); }
	}
	public class UnrealInt64Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("longlong"); }
	}
	public class UnrealUint16Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("ushort"); }
	}
	public class UnrealUint32Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("uint"); }
	}
	public class UnrealUint64Field extends UnrealField {
		public DataType GetType() { return utility.GetDataType("ulonglong"); }
	}
	public class UnrealFloatField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("float"); }
	}
	public class UnrealDoubleField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("double"); }
	}
	
	public class UnrealNativeBoolField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("byte"); }
	}
	public class UnrealBitflagBoolField extends UnrealField {
		public DataType RepType;
		public int ByteMask;
		public int FieldMask;
		private DataType SetRepType(int FieldSize) {
			switch (FieldSize) {
				case 1: return utility.GetDataType("byte");
				case 2: return utility.GetDataType("short");
				case 4: return utility.GetDataType("int");
				case 8: return utility.GetDataType("longlong");
				default: throw new IllegalArgumentException("what");
			}
		}
		public DataType GetType() { return RepType; }
		public UnrealBitflagBoolField(int ByteMask, int FieldMask, int FieldSize) {
			RepType = SetRepType(FieldSize);
			this.ByteMask = ByteMask;
			this.FieldMask = FieldMask;
		}
		public void AddToStructure(Structure struct) {
			// create a bitfield
			println("Adding " + Name + " ( " + this.getClass().getName() + ") to " + struct.getName() + " @ " + Offset 
					+ "( bit offset" + Integer.numberOfTrailingZeros(ByteMask) + ", bit size " + Integer.bitCount(FieldMask));
			try { struct.insertBitFieldAt(
					Offset, RepType.getLength(), Integer.numberOfTrailingZeros(ByteMask), RepType, 
					Integer.bitCount(FieldMask), Name, null
				); } 
			catch (InvalidDataTypeException e) { throw new IllegalArgumentException("bitfield skill issue"); }
		}
	}
	public class UnrealNameField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("FName"); }
	}
	public class UnrealStringField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("FString"); }
	}
	public class UnrealTextField extends UnrealField {
		public DataType GetType() { return utility.GetDataType("FText"); }
	}
	public class UnrealStructField extends UnrealField {
		public String StructName;
		public UnrealStructField(String Name) { 
			StructName = Name;
		}
		public DataType GetType() { return utility.GetDataType(LoadedStructs.get(StructName).GetProperName()); }
	}
	public class UnrealObjectField extends UnrealField {
		public String ClassName;
		public UnrealObjectField(String Name) { 
			ClassName = Name;
		}
		// pointer to class
		public DataType GetType() { return utility.GetDataType(LoadedClasses.get(ClassName).GetProperName() + "*"); }
	}
	public abstract class UnrealObjectRefBase extends UnrealField {
		public String ClassName;
		public abstract String GetTemplateName();
		public String GetTypeName() { return GetTemplateName() + "<" + ClassName + ">"; }
		public UnrealObjectRefBase(String Name) {
			ClassName = Name;
			// Since there's no defined fields, this can be made in ctor
			unrealTypes.GetUserTypeOrCreate(GetTypeName(), name -> {
				DataType og_type = currentProgram.getDataTypeManager().getDataType(BuiltInTypes, GetTemplateName());
				TypedefDataType typedef = new TypedefDataType(UserTypes, GetTypeName(), og_type); 
				currentProgram.getDataTypeManager().addDataType(typedef, null);
				return typedef;
			});
		}
		public DataType GetType() { return utility.GetDataType(GetTypeName()); }
	}
	public class UnrealWeakObjectField extends UnrealObjectRefBase {
		public UnrealWeakObjectField(String Name) { super(Name); }
		public String GetTemplateName() { return "TWeakObjectPtr"; }
	}
	public class UnrealLazyObjectField extends UnrealObjectRefBase {
		public UnrealLazyObjectField(String Name) { super(Name); }
		public String GetTemplateName() { return "TLazyObjectPtr"; }
	}
	public class UnrealSoftObjectField extends UnrealObjectRefBase {
		public UnrealSoftObjectField(String Name) { super(Name); }
		public String GetTemplateName() { return "TSoftObjectPtr"; }
	}
	public class UnrealInterfaceField extends UnrealObjectRefBase {
		public UnrealInterfaceField(String Name) { super(Name); }
		public String GetTemplateName() { return "TScriptInterface"; }
	}
	public class UnrealClassField extends UnrealObjectRefBase {
		public UnrealClassField (String Name) { super(Name); }
		public String GetTemplateName() { return "TSubClassOf"; }
	}
	public class UnrealArrayField extends UnrealField {
		public String ValueName;
		public String DTMName;
		public String GetTypeName() { return "TArray<" + DTMName + ">"; }
		public UnrealArrayField(String Name) {
			ValueName = Name;
			PostRegisterCreateArrays.add(this);
		}
		
		private void SetDTMName() {
			UnrealStruct tgt_struct = LoadedClasses.get(ValueName);
			if (tgt_struct == null) tgt_struct = LoadedStructs.get(ValueName);
			if (tgt_struct != null) DTMName = tgt_struct.struct_data.getName();
			else {
				if (LoadedEnums.get(ValueName) != null) { // is enum
					DTMName = ValueName; // otherwise it's an enum, which uses the normal value name	
				} else { // unknown type
					DTMName = "void";
				} 
			}
		}
		
		private void CreateArrayType() {
			// Create TArray type once all classes and structs are defined
			// First field is pointer to value type, next two fields are count and limit
			SetDTMName();
			println("Create Array " + GetTypeName());
			unrealTypes.GetUserTypeOrCreate(GetTypeName(), name -> {
				StructureDataType new_arr = new StructureDataType(UserTypes, GetTypeName(), 16);
				utility.AddToStruct(new_arr, "Entries", 0, utility.GetDataType(DTMName + "*"));
				utility.AddToStruct(new_arr, "ArrayNum", 8, utility.GetDataType("int"));
				utility.AddToStruct(new_arr, "ArrayMax", 12, utility.GetDataType("int"));
				currentProgram.getDataTypeManager().addDataType(new_arr, null);
				return new_arr;
			});
		}
		public DataType GetType() {
			return utility.GetDataType(GetTypeName()); 
		}
	}
	public class UnrealSoftClassField extends UnrealField {
		public String ClassName;
		public String GetTypeName() { return "TSoftClassPtr<" + ClassName + ">"; } 
		public UnrealSoftClassField(String ClassName, int size) {
			this.ClassName = ClassName;
			unrealTypes.GetUserTypeOrCreate(GetTypeName(), size);
		}
		public DataType GetType() { return utility.GetDataType(GetTypeName()); }
	}
	
	public class UnrealSetField extends UnrealObjectRefBase {
		// Has no defined fields, so make typedef in ctor
		public UnrealSetField(String Name) { super(Name); }
		public String GetTemplateName() { return "TSet"; }
	}
	public class UnrealMapField extends UnrealField {
		public String KeyName;
		public String ValueName;
		public String GetTypeName() { return "TMap<" + KeyName + ", " + ValueName + ">"; }
		public UnrealMapField(String KeyName, String ValueName) {
			this.KeyName = KeyName;
			this.ValueName = ValueName;
			
			unrealTypes.GetUserTypeOrCreate(GetTypeName(), name -> {
				DataType og_type = currentProgram.getDataTypeManager().getDataType(BuiltInTypes, "TMap");
				TypedefDataType typedef = new TypedefDataType(UserTypes, GetTypeName(), og_type); 
				currentProgram.getDataTypeManager().addDataType(typedef, null);
				return typedef;
			});
		}
		public DataType GetType() { return utility.GetDataType(GetTypeName()); }
	}
	public class UnrealEnumField extends UnrealField {
		public String EnumName;
		// Enum Name is stored in UnrealField.Name
		public UnrealEnumField(String EnumName) {
			this.EnumName = EnumName;
		}
		public DataType GetType() { return utility.GetDataType(EnumName); }
	}
	public class UnrealDelegateField extends UnrealField {
		public JsonObject Function;
		public UnrealDelegateField(JsonObject Function) {
			this.Function = Function;
		}
		// TODO
		public DataType GetType() { return utility.GetDataType("byte"); }
	}
	public class UnrealEnumEntry {
		public long Value;
		public String Name;
		
		public UnrealEnumEntry(long Value, String Name) {
			this.Value = Value;
			this.Name = Name;
		}
		// Default deserialization implementation works great
	}
	public class UnrealEnum {
		public String Name;
		public int Size;
		public List<UnrealEnumEntry> Values;
		
		public UnrealEnum(String Name, int Size, List<UnrealEnumEntry> Values) {
			this.Name = Name;
			this.Size = Size;
			this.Values = Values;
		}
		
		@Override
		public String toString() {
			String ret_str = super.toString() + ": " + Name + ", Size " + Size;
			if (Values.size() > 0) ret_str += "\nEntries:";
			for (var entry : Values) {
				ret_str += "\n\t" + entry.Name + ", " + entry.Value;
			}
			return ret_str;
		}
		
		public DataType CreateDataType() {
			HashMap<String, Integer> entries_map = new HashMap<>(); // O(1) checking of duplicate names - value is to be attached to end of duplicate name
			EnumDataType new_enum = new EnumDataType(UserTypes, Name, Size);
			for (var value : Values) {
				if (value.Value <= new_enum.getMaxPossibleValue()) {
					var dup = entries_map.get(value.Name);
					String value_name = value.Name;
					if (dup != null) {
						value_name += "_Copy" + dup; // was originally supposed to just be _[value] but AkChannelConfiguration breaks that lol
						entries_map.replace(value.Name, dup + 1); // increment dup
					} else {
						entries_map.put(value.Name, 1);
					}
					new_enum.add(value_name, value.Value);
				}
			}
			currentProgram.getDataTypeManager().addDataType(new_enum, null);
			return new_enum;
		}
	}
	public class UnrealEnumDeserializer implements JsonDeserializer<UnrealEnum> {
		
		private String GetValueName(String inName) {
			// if it's formatted as a namespace, cut that off
			if (inName.indexOf("::") == -1) return inName;
			return inName.split("::")[1];
		}
		
		@Override
		public UnrealEnum deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject json_obj = json.getAsJsonObject();
			JsonArray enum_values = json_obj.get("Entries").getAsJsonArray();
			List<UnrealEnumEntry> entries = new LinkedList<>();
			for (int i = 0; i < enum_values.size(); i++) {
				JsonObject values_json = enum_values.get(i).getAsJsonObject();
				String value_name = GetValueName(values_json.get("Name").getAsString());
				entries.add(new UnrealEnumEntry(
						values_json.get("Value").getAsLong(),
						value_name
				));
			}
			return new UnrealEnum(
				json_obj.get("Name").getAsString(),
				json_obj.get("Size").getAsInt(),
				entries
			);
		}
	}
	
	// Dummy class for Export deserializer
	public class UnrealExport {
	}
	public class UnrealExportDeserializer implements JsonDeserializer<UnrealExport> {
		
		private void DeserializeEnums(JsonObject enums) {
			Set<String> keys = enums.keySet();
			for (var key : keys) {
				UnrealEnum new_enum = gson.fromJson(enums.get(key), UnrealEnum.class);
				println("Created enum " + new_enum.Name + ", size " + new_enum.Size);
				LoadedEnums.put(new_enum.Name, new_enum);
				if (currentProgram.getDataTypeManager().getDataType(UserTypes, key) == null) new_enum.CreateDataType();
			}
			println("Created " + keys.size() + " enums");
		}
		
		private void DeserializeStructs(JsonObject structs) {
			Set<String> keys = structs.keySet();
			for (var key : keys) {
				UnrealStruct new_struct = gson.fromJson(structs.get(key), UnrealStruct.class);
				println("Created struct " + new_struct.Name + ", size " + new_struct.Size);
				LoadedStructs.put(new_struct.Name, new_struct);
			}
			println("Created " + keys.size() + " structs");
		}
		
		private void DeserializeClasses(JsonObject classes) {
			Set<String> keys = classes.keySet();
			for (var key : keys) {
				UnrealClass new_struct = gson.fromJson(classes.get(key), UnrealClass.class);
				println("Created class " + new_struct.Name + ", size " + new_struct.Size);
				LoadedClasses.put(new_struct.Name, new_struct);
			}
			println("Created " + keys.size() + " classes");
		}
		
		private void AddObjectToDTM(UnrealStruct struct) {
			if (currentProgram.getDataTypeManager().getDataType(UserTypes, struct.GetProperName()) == null) struct.InitializeDataType();
		}
		@Override
		public UnrealExport deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx)
				throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("Incorrect JSON format (should be object)");
			JsonObject obj = json.getAsJsonObject();
			// Step 1: Import and create enums
			DeserializeEnums(obj.get("enums").getAsJsonObject());
			// Step 2: Import classes and structs - deserialize struct and class data
			DeserializeClasses(obj.get("classes").getAsJsonObject());
			DeserializeStructs(obj.get("structs").getAsJsonObject());
			
			// Step 3: Create DTM objects (all structs and enums)
			LoadedStructs.forEach((name, data) -> AddObjectToDTM(data));
			LoadedClasses.forEach((name, data) -> AddObjectToDTM(data));
			
			while (!PostRegisterCreateArrays.isEmpty()) {
				PostRegisterCreateArrays.poll().CreateArrayType();
			}
			
			// Step 4: Add field information for each DTM object (done in a different pass to object init so we have all objects in DTM)
			LoadedStructs.forEach((name, data) -> {
				data.CommitFieldData();
			});
			LoadedClasses.forEach((name, data) -> {
				data.CommitFieldData();
				data.CommitMethodData();
			});
			return new UnrealExport();
		}
	}
	
	private void makeCategoryIfNeeded(CategoryPath cPath) {
		Category out = currentProgram.getDataTypeManager().getCategory(cPath);
		if (out == null) out = currentProgram.getDataTypeManager().createCategory(cPath);
	}
	
	private void InitializeGlobals(String UserTypeName) {
		UserTypes = new CategoryPath("/" + UserTypeName.split("\\.")[0]); // cut off file extension
		BuiltInTypes = new CategoryPath("/Unreal");
		
		makeCategoryIfNeeded(UserTypes);
		makeCategoryIfNeeded(BuiltInTypes);
		
		utility = new Utilities();
		unrealTypes = new UnrealTypeFactory();
		structDeser = new UnrealStructDeserializeHelper();
		
		LoadedClasses = new HashMap<>();
		LoadedStructs = new HashMap<>();
		LoadedEnums   = new HashMap<>();
		
		PostRegisterCreateArrays = new ArrayDeque<>();
		
		// Make types
		unrealTypes.GetBuiltInOrCreate("TArray", name -> {
			StructureDataType new_struct = new StructureDataType(BuiltInTypes, name, 16);
			utility.AddToStruct(new_struct, "Entries", 0, utility.GetDataType("void*"));
			utility.AddToStruct(new_struct, "ArrayNum", 8, utility.GetDataType("int"));
			utility.AddToStruct(new_struct, "ArrayMax", 12, utility.GetDataType("int"));
			currentProgram.getDataTypeManager().addDataType(new_struct, null);
			return new_struct;
		});
		unrealTypes.GetBuiltInOrCreate("TSet", 0x50);
		unrealTypes.GetBuiltInOrCreate("TMap", 0x50);
		
		unrealTypes.GetBuiltInOrCreate("FName", name -> {
			StructureDataType new_struct = new StructureDataType(BuiltInTypes, name, 8);
			utility.AddToStruct(new_struct, "ComparisonIndex", 0, utility.GetDataType("int"));
			utility.AddToStruct(new_struct, "Number", 4, utility.GetDataType("int"));
			currentProgram.getDataTypeManager().addDataType(new_struct, null);
			return new_struct;
		});
		unrealTypes.GetBuiltInOrCreate("FString", 0x10);
		unrealTypes.GetBuiltInOrCreate("FText", 0x18);
		
		unrealTypes.GetBuiltInOrCreate("TWeakObjectPtr", 0x8);
		unrealTypes.GetBuiltInOrCreate("TLazyObjectPtr", 0x1c);
		unrealTypes.GetBuiltInOrCreate("TSoftObjectPtr", 0x28);
		unrealTypes.GetBuiltInOrCreate("TScriptInterface", 0x10);
		unrealTypes.GetBuiltInOrCreate("TSubClassOf", 0x8);
		
		gson = new GsonBuilder()
				.registerTypeAdapter(UnrealClass.class, new UnrealClassDeserializer())
				.registerTypeAdapter(UnrealStruct.class, new UnrealStructDeserializer())
				.registerTypeAdapter(UnrealField.class, new UnrealFieldDeserializer())
				.registerTypeAdapter(UnrealEnum.class, new UnrealEnumDeserializer())
				.registerTypeAdapter(UnrealFunction.class, new UnrealFunctionDeserializer())
				.registerTypeAdapter(UnrealExport.class, new UnrealExportDeserializer())
				.create();
	}
	
	@Override
	public void run() throws Exception {
		File json_export = askFile("Get Unreal Data JSON", "Ok");
		InitializeGlobals(json_export.getName());
		BufferedReader file_read = new BufferedReader(new FileReader(json_export));
		JsonReader json_read = gson.newJsonReader(file_read);
		UnrealExport imported = gson.fromJson(json_read, UnrealExport.class);
	}
}
