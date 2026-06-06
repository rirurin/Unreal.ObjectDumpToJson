//Export VTables from Unreal Engine classes into a JSON file that can be imported using the UnrealImportVTables script.
//@author Rirurin
//@category Unreal Engine
//@keybinding
//@menupath
//@toolbar

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.nio.file.Paths;
import java.util.ArrayDeque;
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
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.data.ArrayDataType;
import ghidra.program.model.data.ByteDataType;
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
import ghidra.program.model.listing.Data;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Listing;
import ghidra.program.model.listing.Parameter;
import ghidra.program.model.mem.Memory;
import ghidra.program.model.mem.MemoryAccessException;
import ghidra.program.model.symbol.SymbolTable;
import ghidra.program.model.util.CodeUnitInsertionException;
import ghidra.util.exception.CancelledException;

public class UnrealExportVTables extends GhidraScript {
	
	private static SymbolTable GSymbolTable;
	private static Listing GListing;
	private static Memory GMemory;
	private static Address CodeStart;
	private static Address CodeEnd;
	
	private static Pattern DataTypeMatcher = Pattern.compile("\\[\\d+\\]|\\*");
	private static Pattern DeletingDestructorAdjustor = Pattern.compile("\\{\\d+");
	
	private Gson gson;
	private Utilities utility;
	private CategoryPath UserTypes;
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
				if (instruction == null) throw new NullPointerException("Function does not exist at " + address.toString());
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
	
	public class FunctionParameterSerializer implements JsonSerializer<FunctionParameter> {
		@Override
		public JsonElement serialize(FunctionParameter value, Type json, JsonSerializationContext ctx) {
			var object = new JsonObject();
			object.addProperty("TypeName", value.TypeName);
			return object;
		}
		
	}
	
	public class FunctionPointer {
		public String Name;
		public LinkedList<FunctionParameter> Parameters;
		public FunctionParameter Return;
		
		public FunctionPointer(String Name, LinkedList<FunctionParameter> Parameters, FunctionParameter Return) {
			this.Name = Name;
			this.Parameters = Parameters;
			this.Return = Return;
		}
	}
	
	public class FunctionPointerSerializer implements JsonSerializer<FunctionPointer> {
		@Override
		public JsonElement serialize(FunctionPointer value, Type json, JsonSerializationContext ctx) {
			var object = new JsonObject();
			object.addProperty("Name", value.Name);
			var parameters = new JsonArray();
			for (var parameter : value.Parameters) {
				parameters.add(parameter.TypeName);
				//parameters.add(gson.toJsonTree(parameter, FunctionParameter.class));
			}
			object.add("Parameters", parameters);
			//object.add("Return", gson.toJsonTree(value.Return, FunctionParameter.class));
			object.addProperty("Return", value.Return.TypeName);
			return object;
		}
	}
	
	public class ClassVTable {
		public int Offset;
		public LinkedList<FunctionPointer> Functions;
		
		public ClassVTable(int Offset, LinkedList<FunctionPointer> Functions) {
			this.Offset = Offset;
			this.Functions = Functions;
		}
	}
	
	public class ClassVTableSerializer implements JsonSerializer<ClassVTable> {
		@Override
		public JsonElement serialize(ClassVTable value, Type json, JsonSerializationContext ctx) {
			var object = new JsonObject();
			object.addProperty("Offset", value.Offset);
			var functions = new JsonArray();
			for (var function : value.Functions) {
				functions.add(gson.toJsonTree(function, FunctionPointer.class));
			}
			object.add("Functions", functions);
			return object;
		}	
	}
	
	public class ClassDefinition {
		public String Name;
		public LinkedList<ClassVTable> VTables;
		
		public ClassDefinition(String Name, LinkedList<ClassVTable> VTables) {
			this.Name = Name;
			this.VTables = VTables;
		}
	}
	
	public class ClassDefinitionSerializer implements JsonSerializer<ClassDefinition> {
		@Override
		public JsonElement serialize(ClassDefinition value, Type json, JsonSerializationContext ctx) {
			var object = new JsonObject();
			object.addProperty("Name", value.Name);
			var vtables = new JsonArray();
			for (var vtable : value.VTables) {
				vtables.add(gson.toJsonTree(vtable, ClassVTable.class));
			}
			object.add("VTables", vtables);
			return object;
		}	
	}
	
	public class ExportObject {
		public LinkedList<ClassDefinition> Classes;
		
		public ExportObject(LinkedList<ClassDefinition> Classes) {
			this.Classes = Classes;
		}
	}
	
	public class ExportObjectSerializer implements JsonSerializer<ExportObject> {
		@Override
		public JsonElement serialize(ExportObject value, Type json, JsonSerializationContext ctx) {
			var object = new JsonObject();
			var classes = new JsonArray();
			for (var currClass : value.Classes) {
				classes.add(gson.toJsonTree(currClass, ClassDefinition.class));
			}
			object.add("Classes", classes);
			return object;
		}	
	}
	
	private FunctionPointer fromData(Data pFunc, Address addr, Ref<Integer> pOffset, Ref<Boolean> halt) 
			throws MemoryAccessException, CancelledException, CodeUnitInsertionException {
		if (addr != null) {
			// Use addr to resolve address if there's no defined data
			if (pFunc == null || !pFunc.isDefined()) {
				var rawAddr = toAddr(Long.toHexString(GMemory.getLong(addr)));
				if (utility.inExecutable(rawAddr)) {
					clearListing(addr, addr.add(7));
					pFunc = GListing.createData(addr, new PointerDataType());
				} else {
					halt.set(true);
					return null;
				}
			}
			// Have we ended up inside of a parent type? (e.g FuncInfo after FWmfMediaModule)
			else if (pFunc.getNumComponents() > 0) {
				halt.set(true);
				return null;
			}
		}
		var deref = toAddr(pFunc.getValue().toString());
		if (deref == null || !utility.inExecutable(deref)) {
			return null;
		}
		var func = utility.mustGetFunction(deref);
		//println("\t" + func.getName() + " @ " + func.getEntryPoint().toString());
		var destructorName = "deleting_destructor'"; 
		var containsDestructor = func.getName().indexOf(destructorName);
		if (containsDestructor != -1) {
			if (containsDestructor + destructorName.length() == func.getName().length()) {
				pOffset.set(0);
			} else {
				var adjustor = func.getName().substring(containsDestructor + destructorName.length());
				Matcher matcher = DeletingDestructorAdjustor.matcher(adjustor);
				if (matcher.find()) {
					pOffset.set(Integer.parseInt(matcher.group().substring(1)));
				}
			}
			
		}
		var paramsOut = new LinkedList<FunctionParameter>();
		for (var parameter : func.getParameters()) {
			paramsOut.add(new FunctionParameter(parameter));
		}
		return new FunctionPointer(func.getName(true), paramsOut, new FunctionParameter(func.getReturn()));
	}

	@Override
	protected void run() throws Exception {
		GListing = currentProgram.getListing();
		GSymbolTable = currentProgram.getSymbolTable();
		GMemory = currentProgram.getMemory();
		
		utility = new Utilities();
		
		gson = new GsonBuilder()
				.registerTypeAdapter(FunctionParameter.class, new FunctionParameterSerializer())
				.registerTypeAdapter(FunctionPointer.class, new FunctionPointerSerializer())
				.registerTypeAdapter(ClassVTable.class, new ClassVTableSerializer())
				.registerTypeAdapter(ClassDefinition.class, new ClassDefinitionSerializer())
				.create();
		
		// Get actual code range
		var dosHeader = new FieldedData(GListing.getDataAt(GMemory.getMinAddress()));
		var ntHeader = new FieldedData(GListing.getDataAt(
				GMemory.getMinAddress().add(Long.parseLong(dosHeader.Get("e_lfanew").getValue().toString().substring(2), 16))));
		var optionalHeader = new FieldedData(ntHeader.Get("OptionalHeader"));
		
		CodeStart = toAddr(optionalHeader.Get("BaseOfCode").getValue().toString());
		CodeEnd = CodeStart.add(Long.parseLong(optionalHeader.Get("SizeOfCode").getValue().toString().substring(2), 16));
		
		var filePath = askDirectory("Set folder to output files to", "OK");
		UserTypes = new CategoryPath("/" + filePath.getName());
		BuiltInTypes = new CategoryPath("/Unreal");
		
		var classesOut = new LinkedList<ClassDefinition>();
		var classNamespaces = GSymbolTable.getClassNamespaces();
		while (classNamespaces.hasNext()) {
			var classNamespace = classNamespaces.next();
			// Skip certain naming conventions that we know aren't part of the type reflection system
			if (!classNamespace.getName().startsWith("U") &&
					!classNamespace.getName().startsWith("A") && 
					!classNamespace.getName().startsWith("F")) {
				continue;
			}
			var vtables = new LinkedList<ClassVTable>();
			var classSymbols = GSymbolTable.getSymbols(classNamespace);
			var hasVTableSymbol = new Ref<Boolean>(false);
			while (classSymbols.hasNext()) {
				var symbol = classSymbols.next();
				if (!symbol.getName().startsWith("`vftable'")) continue;
				if (!hasVTableSymbol.get()) {
					println(classNamespace.getName());
				}
				hasVTableSymbol.set(true);
				//println("\t" + symbol.getName());
				var startAddr = symbol.getAddress();
				var startData = GListing.getDataAt(startAddr);
				var currAddr = startAddr;
				var funcsOut = new LinkedList<FunctionPointer>();
				var tableOffset = new Ref<Integer>(-1);
				var halt = new Ref<Boolean>(false);
				if (startData.getNumComponents() > 0) {
					for (int i = 0; i < startData.getNumComponents(); i++) {
						funcsOut.add(fromData(startData.getComponent(i), null, tableOffset, halt));
					}
				} else {
					do {
						var pFunc = GListing.getDataAt(currAddr);
						funcsOut.add(fromData(pFunc, currAddr, tableOffset, halt));
						currAddr = currAddr.add(8);
					} while (GSymbolTable.getSymbols(currAddr).length == 0 && !halt.get());
				}
				if (tableOffset.get() != -1) {
					vtables.add(new ClassVTable(tableOffset.get(), funcsOut));
				}
			}
			if (!vtables.isEmpty()) {
				classesOut.add(new ClassDefinition(classNamespace.getName(), vtables));	
			}	
		}
		
		try (var fileWriter = new BufferedWriter(new FileWriter(new File(
				Paths.get(filePath.getAbsolutePath(), "VTables.json").toString())))) {
			fileWriter.write(gson.toJson(new ExportObject(classesOut), ExportObject.class));
		}
		
		try (var fileWriter = new BufferedWriter(new FileWriter(new File(
				Paths.get(filePath.getAbsolutePath(), "Offsets.json").toString())))) {
			var Root = new JsonArray();
			for (var currClass : classesOut) {
				var Offsets = new JsonArray();
				for (var vtable : currClass.VTables) {
					if (vtable.Offset != 0) {
						Offsets.add(vtable.Offset);
					}
				}
				if (Offsets.size() > 0) {
					var Element = new JsonObject();
					Element.addProperty("Name", currClass.Name);
					Element.add("Offsets", Offsets);
					Root.add(Element);
				}
			}
			fileWriter.write(gson.toJson(Root));
		}
	}
}