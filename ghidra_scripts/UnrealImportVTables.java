//Label all VTable functions for types exposed to Unreal's type reflection system
//@author Rirurin
//@category Unreal Engine
//@keybinding
//@menupath
//@toolbar

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ArrayDataType;
import ghidra.program.model.data.ByteDataType;
import ghidra.program.model.data.Category;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.CharDataType;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DoubleDataType;
import ghidra.program.model.data.FloatDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.LongLongDataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.ShortDataType;
import ghidra.program.model.data.StringDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.data.UnsignedIntegerDataType;
import ghidra.program.model.data.UnsignedLongLongDataType;
import ghidra.program.model.data.UnsignedShortDataType;
import ghidra.program.model.data.VoidDataType;
import ghidra.program.model.listing.CircularDependencyException;
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.SourceType;
import ghidra.program.model.symbol.Symbol;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.CancelledException;
import ghidra.util.exception.DuplicateNameException;
import ghidra.util.exception.InvalidInputException;

public class UnrealImportVTables extends GhidraScript {
	
	private static SymbolTable GSymbolTable;
	private static Listing GListing;
	private static Memory GMemory;
	private static Address CodeStart;
	private static Address CodeEnd;
	
	private static Pattern DataTypeMatcher = Pattern.compile("\\[\\d+\\]|\\*");
	
	private Gson gson;
	private Utilities utility;
	private CategoryPath UserTypes;
	private CategoryPath VTableTypes;
	private CategoryPath BuiltInTypes;
	
	public class Utilities {
		private DataType AddDataTypeModifier(DataType curr, String token) {
			DataType res;
			switch (token.charAt(0)) {
				case '*':
					res = new PointerDataType(curr);
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
				//if (res == null) res = currentProgram.getDataTypeManager().getDataType(UserTypes, dtPure);
				//if (res == null) throw new IllegalArgumentException("Custom type '" +  dtPure + "' does not exist"); break;
			}
			return res;
		}
		
		private DataType GetDataType(String dt) {
			DataType res;
			Matcher m = DataTypeMatcher.matcher(dt);
			String dtPure = DataTypeMatcher.split(dt)[0]; // base type name
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
				Data a = GListing.getDataAt(addr);
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
				out = GListing.getDataAt(listingLocation);
				if (!out.getDataType().equals(dt)) {
					clearListingForDataType(listingLocation, dt);
					out = GListing.createData(listingLocation, dt);
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
			Data out = GListing.getDataAt(derefAddr);
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
		
		private Function mustGetFunction(Address address) {
			var out = GListing.getFunctionAt(address);
			if (out == null) {
				var instruction = GListing.getInstructionAt(address);
				if (instruction == null) {
					disassemble(address);
				}
				//if (instruction == null) throw new NullPointerException("Function does not exist at " + address.toString());
				out = createFunction(address, null);
			}
			return out;
		}
		
		public void AddToStruct(StructureDataType struct, String name, int offset, DataType type) {
			struct.replaceAtOffset(offset, type, type.getLength(), name, null);
		}
		
		private boolean inExecutable(Address addr) {
			return addr.compareTo(CodeStart) >= 0 && addr.compareTo(CodeEnd) < 0;	
		}
		
	}
	
	// Workaround for Java's lack of pass-by-reference.
	public class Ref<T> {
		T val;
		public Ref(T _val) {
			val = _val;
		}
		public Ref() {
			val = null;
		}
		public T get() { return val; }
		public void set(T _val) { val = _val; }
		public String ToString() {
			return val.toString();
		}
	}
	
	public class FieldedData {
		private HashMap<String, Data> NamesToFields;
		public FieldedData(Data TargetData) {
			NamesToFields = new HashMap<>();
			for (int i = 0; i < TargetData.getNumComponents(); i++) {
				Data Field = TargetData.getComponent(i);
				NamesToFields.put(Field.getFieldName(), Field);
			}
		}
		public Set<String> FieldNames() { return NamesToFields.keySet(); }
		public Data Get(String name) { return NamesToFields.get(name); }
	}
	
	private void makeCategoryIfNeeded(CategoryPath cPath) {
		Category out = currentProgram.getDataTypeManager().getCategory(cPath);
		if (out == null) out = currentProgram.getDataTypeManager().createCategory(cPath);
	}
	
	/*
	public class ClassNonZeroVTable {
		public int StructOffset;
		public Address VTableAddress;
		
		public ClassNonZeroVTable(int StructOffset, Address VTableAddress) {
			this.StructOffset = StructOffset;
			this.VTableAddress = VTableAddress;
		}
	}
	*/
	
	//public HashMap<String, ArrayList<ClassNonZeroVTable>> classNonZeroVTables;
	public HashMap<String, HashMap<Integer, Address>> classNonZeroVTables;
	
	public class OffsetAddressFile {
		public HashMap<String, ArrayList<Address>> classToAddresses;
		
		public OffsetAddressFile(HashMap<String, ArrayList<Address>> classToAddresses) {
			this.classToAddresses = classToAddresses;
		}
	}
	
	public class OffsetAddressSerializer implements JsonDeserializer<OffsetAddressFile> {
		@Override
		public OffsetAddressFile deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var arr = json.getAsJsonArray();
			var classToOffsets = new HashMap<String, ArrayList<Address>>(arr.size());
			for (var entry : arr) {
				var entryObj = entry.getAsJsonObject();
				var name = entryObj.get("Name").getAsString();
				var offsets = entryObj.get("Addresses").getAsJsonArray();
				var offsetsOut = new ArrayList<Address>(offsets.size());
				offsets.forEach(offset -> {
					var offsetObj = offset.getAsJsonObject();
					offsetsOut.add(toAddr(Long.parseLong(offsetObj.get("Value").getAsString().substring(2), 16)));
				});
				classToOffsets.put(name, offsetsOut);
			}
			return new OffsetAddressFile(classToOffsets);
		}
		
	}
	
	public class OffsetFile {
		public HashMap<String, ArrayList<Integer>> classToOffsets;
		
		public OffsetFile(HashMap<String, ArrayList<Integer>> classToOffsets) {
			this.classToOffsets = classToOffsets;
		}
	}
	
	public class OffsetFileSerializer implements JsonDeserializer<OffsetFile> {
		@Override
		public OffsetFile deserialize(JsonElement json, Type typeOf, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var arr = json.getAsJsonArray();
			var classToOffsets = new HashMap<String, ArrayList<Integer>>(arr.size());
			for (var entry : arr) {
				var entryObj = entry.getAsJsonObject();
				var name = entryObj.get("Name").getAsString();
				var offsets = entryObj.get("Offsets").getAsJsonArray();
				var offsetsOut = new ArrayList<Integer>(offsets.size());
				offsets.forEach(x -> offsetsOut.add(x.getAsInt()));
				classToOffsets.put(name, offsetsOut);
			}
			return new OffsetFile(classToOffsets);
		}
		
	}
	
	public class FunctionParameter {
		public String TypeName;
		
		public FunctionParameter(String TypeName) {
			this.TypeName = TypeName;
		}
		
		public FunctionParameter(DataType Type) {
			this.TypeName = Type.getName();
		}
		
		public FunctionParameter(Parameter Param) {
			this.TypeName = Param.getDataType().getName();
		}
	}
	
	public class FunctionPointer {
		public String Name;
		public ArrayList<FunctionParameter> Parameters;
		public FunctionParameter Return;
		
		public FunctionPointer(String Name, ArrayList<FunctionParameter> Parameters, FunctionParameter Return) {
			this.Name = Name;
			this.Parameters = Parameters;
			this.Return = Return;
		}
	}
	
	public class FunctionPointerSerializer implements JsonDeserializer<FunctionPointer> {

		@Override
		public FunctionPointer deserialize(JsonElement value, Type json, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var obj = value.getAsJsonObject();
			var name = obj.get("Name").getAsString();
			var parameters = obj.get("Parameters").getAsJsonArray();
			var parametersOut = new ArrayList<FunctionParameter>(parameters.size());
			parameters.forEach(p -> parametersOut.add(new FunctionParameter(p.getAsString())));
			var returnType = new FunctionParameter(obj.get("Return").getAsString());
			return new FunctionPointer(name, parametersOut, returnType);
		}
	}
	
	public class ClassVTable {
		public int Offset;
		public ArrayList<FunctionPointer> Functions;
		
		public ClassVTable(int Offset, ArrayList<FunctionPointer> Functions) {
			this.Offset = Offset;
			this.Functions = Functions;
		}
	}
	
	public class ClassVTableSerializer implements JsonDeserializer<ClassVTable> {
		@Override
		public ClassVTable deserialize(JsonElement value, Type json, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var obj = value.getAsJsonObject();
			var offset = obj.get("Offset").getAsInt();
			var functions = obj.get("Functions").getAsJsonArray();
			var functionsOut = new ArrayList<FunctionPointer>(functions.size());
			functions.forEach(f -> functionsOut.add(gson.fromJson(f, FunctionPointer.class)));
			return new ClassVTable(offset, functionsOut);
		}
	}
	
	public class ClassDefinition {
		public String Name;
		public ArrayList<ClassVTable> VTables;
		
		public ClassDefinition(String Name, ArrayList<ClassVTable> VTables) {
			this.Name = Name;
			this.VTables = VTables;
		}
	}
	
	public class ClassDefinitionSerializer implements JsonDeserializer<ClassDefinition> {
		@Override
		public ClassDefinition deserialize(JsonElement value, Type json, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var obj = value.getAsJsonObject();
			var name = obj.get("Name").getAsString();
			var vtables = obj.get("VTables").getAsJsonArray();
			var vtablesOut = new ArrayList<ClassVTable>(vtables.size());
			vtables.forEach(v -> vtablesOut.add(gson.fromJson(v, ClassVTable.class)));
			return new ClassDefinition(name, vtablesOut);
		}
	}
	
	public class ExportObject {
		public ArrayList<ClassDefinition> Classes;
		
		public ExportObject(ArrayList<ClassDefinition> Classes) {
			this.Classes = Classes;
		}
	}
	
	public class ExportObjectSerializer implements JsonDeserializer<ExportObject> {
		@Override
		public ExportObject deserialize(JsonElement value, Type json, JsonDeserializationContext ctx) 
				throws JsonParseException {
			var obj = value.getAsJsonObject();
			var classes = obj.get("Classes").getAsJsonArray();
			var classesOut = new ArrayList<ClassDefinition>(classes.size());
			for (var classInfo : classes) {
				classesOut.add(gson.fromJson(classInfo, ClassDefinition.class));
			}
			return new ExportObject(classesOut);
		}
	}
	
	public void LabelVtable(ClassVTable vtable, Symbol symVtable) throws CancelledException, CodeUnitInsertionException, InvalidInputException, DuplicateNameException, CircularDependencyException {
		for (int i = 0; i < vtable.Functions.size(); i++) {
			var func = vtable.Functions.get(i);
			if (func == null) continue;
			var addr = symVtable.getAddress().add(i * 8);
			var pFunc = GListing.getDataAt(addr);
			if (!pFunc.isDefined()) {
				clearListing(addr, addr.add(7));
				pFunc = GListing.createData(addr, new PointerDataType());
			}
			var deref = toAddr(pFunc.getValue().toString());
			var lFunc = utility.mustGetFunction(deref);
			var nameParts = func.Name.split("::");
			var namespaceParts = Arrays.copyOf(nameParts, nameParts.length - 1);
			var funcName = nameParts[nameParts.length - 1];
			lFunc.setName(funcName, SourceType.USER_DEFINED);
			var namespaceName = String.join("::", namespaceParts);
			if (!namespaceName.isEmpty()) {
				var funcNamespace = GSymbolTable.getOrCreateNameSpace(currentProgram.getGlobalNamespace(), namespaceName, SourceType.USER_DEFINED);
				lFunc.setParentNamespace(funcNamespace);
			}
		}	
	}

	// Run this after UnrealGen.java
	@Override
	protected void run() throws Exception {
		GListing = currentProgram.getListing();
		GSymbolTable = currentProgram.getSymbolTable();
		GMemory = currentProgram.getMemory();
		
		utility = new Utilities();
		
		var folderPath = askDirectory("Select folder containing VTables.json, Offsets.json and OffsetAddresses.json", "OK");
		UserTypes = new CategoryPath("/" + folderPath.getName());
		VTableTypes = new CategoryPath("/" + folderPath.getName() + "_VTables");
		BuiltInTypes = new CategoryPath("/Unreal");
		
		gson = new GsonBuilder()
				.registerTypeAdapter(OffsetFile.class, new OffsetFileSerializer())
				.registerTypeAdapter(OffsetAddressFile.class, new OffsetAddressSerializer())
				.registerTypeAdapter(FunctionPointer.class, new FunctionPointerSerializer())
				.registerTypeAdapter(ClassVTable.class, new ClassVTableSerializer())
				.registerTypeAdapter(ClassDefinition.class, new ClassDefinitionSerializer())
				.create();
		
		makeCategoryIfNeeded(UserTypes);
		makeCategoryIfNeeded(VTableTypes);
		makeCategoryIfNeeded(BuiltInTypes);
		
		// Get actual code range
		var dosHeader = new FieldedData(GListing.getDataAt(GMemory.getMinAddress()));
		var ntHeader = new FieldedData(GListing.getDataAt(
				GMemory.getMinAddress().add(Long.parseLong(dosHeader.Get("e_lfanew").getValue().toString().substring(2), 16))));
		var optionalHeader = new FieldedData(ntHeader.Get("OptionalHeader"));
		
		CodeStart = toAddr(optionalHeader.Get("BaseOfCode").getValue().toString());
		CodeEnd = CodeStart.add(Long.parseLong(optionalHeader.Get("SizeOfCode").getValue().toString().substring(2), 16));
		
		var vtableFile = new BufferedReader(new FileReader(new File(Paths.get(folderPath.getAbsolutePath(), "VTables.json").toString())));
		var offsetFile = new BufferedReader(new FileReader(new File(Paths.get(folderPath.getAbsolutePath(), "Offsets.json").toString())));
		var offsetAddrFile = new BufferedReader(new FileReader(new File(Paths.get(folderPath.getAbsolutePath(), "OffsetAddresses.json").toString())));
		
		// Build Offset to Address relationships
		classNonZeroVTables = new HashMap<>();
		var offsets = gson.fromJson(offsetFile, OffsetFile.class);
		var offsetAddresses = gson.fromJson(offsetAddrFile, OffsetAddressFile.class);
		for (var offsetAddressList : offsetAddresses.classToAddresses.entrySet()) {
			var offsetList = offsets.classToOffsets.get(offsetAddressList.getKey());
			var offsetAddressValue = offsetAddressList.getValue();
			//var resolved = new ArrayList<ClassNonZeroVTable>(offsetList.size());
			var resolved = new HashMap<Integer, Address>(offsetList.size());
			for (int i = 0; i < offsetList.size(); i++) {
				//resolved.add(new ClassNonZeroVTable(offsetList.get(i), offsetAddressValue.get(i)));
				resolved.put(offsetList.get(i), offsetAddressValue.get(i));
			}
			classNonZeroVTables.put(offsetAddressList.getKey(), resolved);
		}
		println("Retrieved non-zero VTable information from " + classNonZeroVTables.size() + " classes");
		
		var vtables = gson.fromJson(vtableFile, ExportObject.class);
		var toRemove = new ArrayList<String>();
		// Only include types exposed to the type reflection system
		for (int i = 0; i < vtables.Classes.size(); i++) {
			var name = vtables.Classes.get(i).Name;
			if (GSymbolTable.getClassSymbol(name, null) == null) {
				toRemove.add(name);
			} else {
				var classNamespace = GSymbolTable.getNamespace(name, null);
				if (GSymbolTable.getSymbols("vtable", classNamespace).size() == 0) {
					toRemove.add(name);
				}
			}
		}
		for (var r : toRemove) {
			vtables.Classes.removeIf(x -> x.Name.equals(r));
		}
		println("Retrieved VTable information from " + vtables.Classes.size() + " classes");
		for (var classInfo : vtables.Classes) {			
			var classNamespace = GSymbolTable.getNamespace(classInfo.Name, null);
			//println(classInfo.Name);
			for (int v = 0; v < classInfo.VTables.size(); v++) {
				var vtable = classInfo.VTables.get(v);
				if (vtable.Offset != 0) {
					var vtableAddress = classNonZeroVTables.get(classInfo.Name).get(vtable.Offset);
					if (vtableAddress != null) {
						var offsetVtable = GSymbolTable.createLabel(vtableAddress, "vtable_" + vtable.Offset, classNamespace, SourceType.USER_DEFINED);
						LabelVtable(vtable, offsetVtable);
					}
				} else {
					var zeroVtable = GSymbolTable.getSymbols("vtable", classNamespace).get(0);
					LabelVtable(vtable, zeroVtable);
				}	
			}
		}
		vtableFile.close();
		offsetFile.close();
		offsetAddrFile.close();
	}
}
