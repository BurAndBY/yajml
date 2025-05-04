import org.luaj.vm2.*;
import org.luaj.vm2.lib.*;
import org.luaj.vm2.lib.jse.*;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import com.jpexs.decompiler.flash.SWF;
import com.jpexs.decompiler.flash.SwfOpenException;

public class Main {

    public static void main(String[] args) {
        // Create a Lua environment
        Globals globals = JsePlatform.standardGlobals();

        // Register our auto-binding library
        globals.load(new jpexsAutoLib(globals));

        // Path to your Lua script file
        String luaScriptPath = args.length > 0 ? args[0] : "C:/Users/bur/Downloads/script.lua";

        try {
            // Load and execute the Lua script from file
            globals.loadfile(luaScriptPath).call();
        } catch (Exception e) {
            System.err.println("Error loading Lua script: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // Library that automatically binds JPEXS classes to Lua
    public static class jpexsAutoLib extends TwoArgFunction {
        private Globals globals;
        private Set<Class<?>> processedClasses = new HashSet<>();
        private Map<Class<?>, Class<?>> primitiveWrappers = new HashMap<>();

        public jpexsAutoLib(Globals globals) {
            this.globals = globals;

            // Initialize primitive type mappings
            primitiveWrappers.put(boolean.class, Boolean.class);
            primitiveWrappers.put(byte.class, Byte.class);
            primitiveWrappers.put(char.class, Character.class);
            primitiveWrappers.put(double.class, Double.class);
            primitiveWrappers.put(float.class, Float.class);
            primitiveWrappers.put(int.class, Integer.class);
            primitiveWrappers.put(long.class, Long.class);
            primitiveWrappers.put(short.class, Short.class);
            primitiveWrappers.put(void.class, Void.class);

            // Pre-load important JPEXS classes
            try {
                LuaTable jpexsTable = new LuaTable();

                // Add AVM2Instructions class
                Class<?> avm2InstructionsClass = Class.forName("com.jpexs.decompiler.flash.abc.avm2.instructions.AVM2Instructions");
                jpexsTable.set("AVM2Instructions", wrapClass(avm2InstructionsClass));

                // Add AVM2Code class
                Class<?> avm2CodeClass = Class.forName("com.jpexs.decompiler.flash.abc.avm2.AVM2Code");
                jpexsTable.set("AVM2Code", wrapClass(avm2CodeClass));

                // Add ActionScript 1/2 classes
                jpexsTable.set("ActionAdd", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf4.ActionAdd")));
                jpexsTable.set("ActionSubtract", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf4.ActionSubtract")));
                jpexsTable.set("ActionMultiply", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf4.ActionMultiply")));
                jpexsTable.set("ActionDivide", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf4.ActionDivide")));
                jpexsTable.set("ActionModulo", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf5.ActionModulo")));
                jpexsTable.set("ActionAdd2", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf5.ActionAdd2")));
                jpexsTable.set("ActionPush", wrapClass(Class.forName("com.jpexs.decompiler.flash.action.swf4.ActionPush")));

                globals.set("jpexsClasses", jpexsTable);
            } catch (ClassNotFoundException e) {
                System.err.println("Error loading JPEXS classes: " + e.getMessage());
                e.printStackTrace();
            }
        }

        @Override
        public LuaValue call(LuaValue modname, LuaValue env) {
            LuaTable library = new LuaTable();

            // Add function to open SWF files
            library.set("openSWF", new OneArgFunction() {
                @Override
                public LuaValue call(LuaValue arg) {
                    String filePath = arg.checkjstring();

                    try (FileInputStream fis = new FileInputStream(filePath)) {
                        // Process the SWF file
                        SWF swf = new SWF(fis, true);
                        return wrapObject(swf);
                    } catch (SwfOpenException ex) {
                        System.out.println("ERROR: Invalid SWF file");
                        return LuaValue.NIL;
                    } catch (IOException ex) {
                        System.out.println("ERROR: Error during SWF opening");
                        return LuaValue.NIL;
                    } catch (InterruptedException ex) {
                        System.out.println("ERROR: Parsing interrupted");
                        return LuaValue.NIL;
                    }
                }
            });

            // Add function to create a new instance of a JPEXS class
            library.set("newInstance", new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    try {
                        String className = args.checkjstring(1);
                        Class<?> clazz = Class.forName(className);

                        // Find a suitable constructor
                        Constructor<?>[] constructors = clazz.getConstructors();
                        if (constructors.length == 0) {
                            throw new LuaError("No public constructors found for " + className);
                        }

                        // Try to find a constructor that matches the arguments
                        for (Constructor<?> constructor : constructors) {
                            if (constructor.getParameterCount() == args.narg() - 1) {
                                Object[] javaArgs = new Object[args.narg() - 1];
                                Class<?>[] paramTypes = constructor.getParameterTypes();

                                boolean match = true;
                                for (int i = 0; i < javaArgs.length; i++) {
                                    LuaValue arg = args.arg(i + 2);
                                    Object javaArg = convertLuaToJava(arg, paramTypes[i]);
                                    if (javaArg == null) {
                                        match = false;
                                        break;
                                    }
                                    javaArgs[i] = javaArg;
                                }

                                if (match) {
                                    Object instance = constructor.newInstance(javaArgs);
                                    return wrapObject(instance);
                                }
                            }
                        }

                        throw new LuaError("No matching constructor found for " + className);
                    } catch (ClassNotFoundException e) {
                        throw new LuaError("Class not found: " + e.getMessage());
                    } catch (Exception e) {
                        throw new LuaError("Error creating instance: " + e.getMessage());
                    }
                }
            });

            // Add instanceOf function
            library.set("instanceOf", new TwoArgFunction() {
                @Override
                public LuaValue call(LuaValue obj, LuaValue className) {
                    if (!obj.isuserdata()) {
                        return LuaValue.FALSE;
                    }

                    Object javaObj = obj.touserdata();
                    try {
                        Class<?> clazz = Class.forName(className.checkjstring());
                        return LuaValue.valueOf(clazz.isInstance(javaObj));
                    } catch (ClassNotFoundException e) {
                        return LuaValue.FALSE;
                    }
                }
            });

            // Register the library
            env.set("jpexs", library);
            return library;
        }

        // Wrap a Java class for Lua
        private LuaValue wrapClass(Class<?> clazz) {
            LuaTable classTable = new LuaTable();

            // Add static fields
            for (Field field : clazz.getFields()) {
                if (Modifier.isStatic(field.getModifiers())) {
                    try {
                        classTable.set(field.getName(), wrapObject(field.get(null)));
                    } catch (Exception e) {
                        // Ignore field access errors
                    }
                }
            }

            // Add static methods
            for (Method method : clazz.getMethods()) {
                if (Modifier.isStatic(method.getModifiers())) {
                    final Method staticMethod = method;
                    classTable.set(method.getName(), new VarArgFunction() {
                        @Override
                        public Varargs invoke(Varargs args) {
                            try {
                                Object[] javaArgs = new Object[args.narg()];
                                Class<?>[] paramTypes = staticMethod.getParameterTypes();

                                if (javaArgs.length != paramTypes.length) {
                                    throw new LuaError("Wrong number of arguments");
                                }

                                for (int i = 0; i < javaArgs.length; i++) {
                                    javaArgs[i] = convertLuaToJava(args.arg(i+1), paramTypes[i]);
                                }

                                Object result = staticMethod.invoke(null, javaArgs);
                                return wrapObject(result);
                            } catch (Exception e) {
                                throw new LuaError("Error calling static method: " + e.getMessage());
                            }
                        }
                    });
                }
            }

            return classTable;
        }

        // Wrap a Java object for Lua
        private LuaValue wrapObject(Object obj) {
            if (obj == null) {
                return LuaValue.NIL;
            }

            // Handle primitive types and strings
            if (obj instanceof Boolean) {
                return LuaValue.valueOf((Boolean) obj);
            } else if (obj instanceof Number) {
                if (obj instanceof Double || obj instanceof Float) {
                    return LuaValue.valueOf(((Number) obj).doubleValue());
                } else {
                    return LuaValue.valueOf(((Number) obj).intValue());
                }
            } else if (obj instanceof String) {
                return LuaValue.valueOf((String) obj);
            } else if (obj instanceof Character) {
                return LuaValue.valueOf(String.valueOf(obj));
            }

            // Handle collections
            if (obj instanceof Collection) {
                LuaTable table = new LuaTable();
                int index = 1;
                for (Object item : (Collection<?>) obj) {
                    table.set(index++, wrapObject(item));
                }
                return table;
            } else if (obj instanceof Map) {
                LuaTable table = new LuaTable();
                for (Map.Entry<?, ?> entry : ((Map<?, ?>) obj).entrySet()) {
                    table.set(wrapObject(entry.getKey()), wrapObject(entry.getValue()));
                }
                return table;
            } else if (obj.getClass().isArray()) {
                int length = Array.getLength(obj);
                LuaTable table = new LuaTable();
                for (int i = 0; i < length; i++) {
                    table.set(i + 1, wrapObject(Array.get(obj, i)));
                }
                return table;
            }

            // Create a userdata for the object
            UserdataWithMeta userdata = new UserdataWithMeta(obj);

            // Create a metatable for the object
            LuaTable mt = new LuaTable();
            mt.set("__index", new TwoArgFunction() {
                @Override
                public LuaValue call(LuaValue table, LuaValue key) {
                    Object javaObj = userdata.userdata();
                    String methodName = key.tojstring();

                    // Handle toString specially
                    if (methodName.equals("toString")) {
                        return new ZeroArgFunction() {
                            @Override
                            public LuaValue call() {
                                return LuaValue.valueOf(javaObj.toString());
                            }
                        };
                    }

                    // Handle getClass specially
                    if (methodName.equals("getClass")) {
                        return new ZeroArgFunction() {
                            @Override
                            public LuaValue call() {
                                return LuaValue.valueOf(javaObj.getClass().getName());
                            }
                        };
                    }

                    // Try to find a matching method
                    Method[] methods = javaObj.getClass().getMethods();
                    List<Method> matchingMethods = new ArrayList<>();

                    // First, look for exact method name matches
                    for (Method method : methods) {
                        if (method.getName().equals(methodName)) {
                            matchingMethods.add(method);
                        }
                    }

                    // If no exact matches, try getter pattern
                    if (matchingMethods.isEmpty() && methodName.startsWith("get")) {
                        String propertyName = methodName.substring(3);
                        for (Method method : methods) {
                            if (method.getName().equals("get" + propertyName) ||
                                    method.getName().equals("is" + propertyName)) {
                                matchingMethods.add(method);
                            }
                        }
                    }

                    if (!matchingMethods.isEmpty()) {
                        return createMethodWrapper(javaObj, matchingMethods);
                    }

                    // Try to find a field with the given name
                    try {
                        Field field = findField(javaObj.getClass(), methodName);
                        if (field != null) {
                            field.setAccessible(true);
                            Object value = field.get(javaObj);
                            return wrapObject(value);
                        }
                    } catch (Exception e) {
                        // Ignore field access errors
                    }

                    return LuaValue.NIL;
                }
            });

            // Add __newindex metamethod for setting fields
            mt.set("__newindex", new ThreeArgFunction() {
                @Override
                public LuaValue call(LuaValue table, LuaValue key, LuaValue value) {
                    Object javaObj = userdata.userdata();
                    String fieldName = key.tojstring();

                    // Try to find a setter method
                    String setterName = "set" + Character.toUpperCase(fieldName.charAt(0)) + fieldName.substring(1);
                    Method[] methods = javaObj.getClass().getMethods();
                    for (Method method : methods) {
                        if (method.getName().equals(setterName) && method.getParameterCount() == 1) {
                            try {
                                Object javaValue = convertLuaToJava(value, method.getParameterTypes()[0]);
                                method.invoke(javaObj, javaValue);
                                return LuaValue.NIL;
                            } catch (Exception e) {
                                throw new LuaError("Error calling setter: " + e.getMessage());
                            }
                        }
                    }

                    // Try to set a field directly
                    try {
                        Field field = findField(javaObj.getClass(), fieldName);
                        if (field != null) {
                            field.setAccessible(true);
                            Object javaValue = convertLuaToJava(value, field.getType());
                            field.set(javaObj, javaValue);
                            return LuaValue.NIL;
                        }
                    } catch (Exception e) {
                        throw new LuaError("Error setting field: " + e.getMessage());
                    }

                    throw new LuaError("No setter or field found for: " + fieldName);
                }
            });

            userdata.setmetatable(mt);
            return userdata;
        }

        // Find a field in a class or its superclasses
        private Field findField(Class<?> clazz, String fieldName) {
            try {
                return clazz.getField(fieldName);
            } catch (NoSuchFieldException e) {
                // Try to find a field with the given name in any superclass
                Class<?> superClass = clazz.getSuperclass();
                if (superClass != null) {
                    return findField(superClass, fieldName);
                }
            }
            return null;
        }

        // Create a function that wraps a set of overloaded methods
        private LuaValue createMethodWrapper(Object javaObj, List<Method> methods) {
            return new VarArgFunction() {
                @Override
                public Varargs invoke(Varargs args) {
                    // Try each method until we find one that matches the arguments
                    for (Method method : methods) {
                        if (method.getParameterCount() == args.narg()) {
                            try {
                                Object[] javaArgs = new Object[args.narg()];
                                Class<?>[] paramTypes = method.getParameterTypes();

                                boolean match = true;
                                for (int i = 0; i < javaArgs.length; i++) {
                                    LuaValue arg = args.arg(i + 1);
                                    Object javaArg = convertLuaToJava(arg, paramTypes[i]);
                                    if (javaArg == null) {
                                        match = false;
                                        break;
                                    }
                                    javaArgs[i] = javaArg;
                                }

                                if (match) {
                                    Object result = method.invoke(javaObj, javaArgs);
                                    return LuaValue.varargsOf(new LuaValue[] { wrapObject(result) });
                                }
                            } catch (Exception e) {
                                // Try the next method
                            }
                        }
                    }

                    throw new LuaError("No matching method found among " + methods.size() + " overloads");
                }
            };
        }

        // Convert a Lua value to a Java value
        private Object convertLuaToJava(LuaValue luaValue, Class<?> targetType) {
            if (luaValue.isnil() && !targetType.isPrimitive()) {
                return null;
            }

            // Handle primitive types
            if (targetType.isPrimitive()) {
                targetType = primitiveWrappers.get(targetType);
            }

            if (targetType == Boolean.class) {
                return luaValue.toboolean();
            } else if (targetType == Integer.class) {
                return luaValue.toint();
            } else if (targetType == Long.class) {
                return (long) luaValue.todouble();
            } else if (targetType == Double.class) {
                return luaValue.todouble();
            } else if (targetType == Float.class) {
                return (float) luaValue.todouble();
            } else if (targetType == String.class) {
                return luaValue.tojstring();
            } else if (targetType == Character.class) {
                String s = luaValue.tojstring();
                return s.length() > 0 ? s.charAt(0) : '\0';
            } else if (targetType == Byte.class) {
                return (byte) luaValue.toint();
            } else if (targetType == Short.class) {
                return (short) luaValue.toint();
            }

            // Handle arrays and collections
            if (targetType.isArray()) {
                if (!luaValue.istable()) {
                    return null;
                }

                LuaTable table = (LuaTable) luaValue;
                Class<?> componentType = targetType.getComponentType();
                int length = table.length();
                Object array = Array.newInstance(componentType, length);

                for (int i = 0; i < length; i++) {
                    LuaValue element = table.get(i + 1);
                    Object javaElement = convertLuaToJava(element, componentType);
                    if (javaElement == null && componentType.isPrimitive()) {
                        return null;
                    }
                    Array.set(array, i, javaElement);
                }

                return array;
            } else if (List.class.isAssignableFrom(targetType)) {
                if (!luaValue.istable()) {
                    return null;
                }

                LuaTable table = (LuaTable) luaValue;
                List<Object> list = new ArrayList<>();

                for (int i = 1; i <= table.length(); i++) {
                    LuaValue element = table.get(i);
                    list.add(convertLuaToJava(element, Object.class));
                }

                return list;
            } else if (Map.class.isAssignableFrom(targetType)) {
                if (!luaValue.istable()) {
                    return null;
                }

                LuaTable table = (LuaTable) luaValue;
                Map<Object, Object> map = new HashMap<>();

                LuaValue k = LuaValue.NIL;
                while (true) {
                    Varargs n = table.next(k);
                    if ((k = n.arg1()).isnil()) {
                        break;
                    }
                    LuaValue v = n.arg(2);
                    map.put(convertLuaToJava(k, Object.class), convertLuaToJava(v, Object.class));
                }

                return map;
            }

            // Handle userdata (Java objects)
            if (luaValue.isuserdata()) {
                Object obj = luaValue.touserdata();
                if (targetType.isInstance(obj)) {
                    return obj;
                }
            }

            // Can't convert
            return null;
        }
    }

    // Helper class to wrap Java objects for Lua
    static class UserdataWithMeta extends LuaUserdata {
        public UserdataWithMeta(Object obj) {
            super(obj);
        }
    }
}
