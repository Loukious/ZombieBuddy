# Migrating Mods from ZombieBuddy 2.x to 3.x

This guide lists the API changes between ZombieBuddy 2.x (last release: 2.3.4) and 3.x, and how to update your mod. For the full API, see the [Modding Guide](ModdingGuide.md).

## TL;DR

| What | 2.x | 3.x | Action |
|------|-----|-----|--------|
| `@Patch` package | `me.zed_0xff.zombie_buddy.Patch` | `me.zed_0xff.zombie_buddy.annotations.Patch` | Change the import (old jars are auto-converted, see below) |
| `Accessor` | Reflection helper | **Removed** | Use `Reflect`, `@Patch.Field`, or the handle annotations |
| Java version | 17 | 25 | Compile with JDK 25 |
| `mod.info` | One `javaJarFile` | Optional numbered `javaJarFile2`, `ZBVersionMin2`, ... | Optional: ship one jar per ZB major version |
| `Exposer.exposeAnnotatedClasses(...)`, `getClassesWithGlobalLuaMethod()`, etc. | public | package-private / removed | Use `@Exposer.LuaClass` or `Exposer.exposeClass(...)` |
| `Exposer.exposeClassToLua(...)` | deprecated | deprecated, `forRemoval = true` | Use `Exposer.exposeClass(...)` |
| `verbosity` | `0` / `1` / `2` | `-2` ... `2` | Re-check any levels you set |
| `batch_approval_timeout` | supported | removed | Drop it from launch options |

## Do I need to do anything?

Probably not right away. 3.x ships a `ZB2Compat` transformer that rewrites references to the old `me.zed_0xff.zombie_buddy.Patch` (and its nested annotations such as
`Patch.OnEnter`) to the new `annotations.Patch` when a mod jar is loaded. A 2.x mod that only used `@Patch` and its nested annotations keeps working unmodified on 3.x.

A 2.x mod that calls APIs that were removed (most commonly `Accessor`) fails at runtime. Rebuild against 3.x to find these at compile time.

If your mod must keep supporting 2.x installs too, ship two jars (see [Supporting both 2.x and 3.x](#supporting-both-2x-and-3x)).

## Breaking changes

### 1. `@Patch` moved to the `annotations` package

```diff
-import me.zed_0xff.zombie_buddy.Patch;
+import me.zed_0xff.zombie_buddy.annotations.Patch;
```

Nested annotations (`@Patch.OnEnter`, `@Patch.OnExit`, `@Patch.Argument`, `@Patch.This`, `@Patch.Return`, and so on) keep their names. Only the outer package changed.

Other changes to `@Patch`:

- `warmUp` is now unused. It is kept so existing source still compiles. You can delete it.
- `IKnowWhatIAmDoing` is gone from the annotation.
- New `debug` element (`boolean`, default `false`).
- `@Patch.Argument` gained `optional` (bind `null` or the primitive default when the index is out of range).
- `@Patch.OnEnter` and `@Patch.OnExit` are now converted to ByteBuddy `Advice` annotations by ZombieBuddy at load time. You no longer need to think about ByteBuddy types.

### 2. `Accessor` was removed. Use `Reflect` or annotations

`me.zed_0xff.zombie_buddy.Accessor` no longer exists. The 2.x helpers map to the following:

| 2.x `Accessor` | 3.x replacement |
|----------------|-----------------|
| `Accessor.tryGet(obj, "field", def)` | `Reflect.on(obj).field("field").get(def)` |
| `Accessor.trySet(obj, "field", v)` | `Reflect.on(obj).field("field").set(v)` |
| `Accessor.findClass("a.B", "a.C")` | `Reflect.on("a.B").getType()` (one name per chain) |
| `Accessor.findField(cls, names...)` | `Reflect.on(cls).field(names...)` (several names are tried in order) |
| `Accessor.callNoArg(obj, "m")` / `callByName` / `callExact` | `Reflect.on(obj).getMethodHandle(returnType, paramTypes, "m")`, or `Reflect.fastcall(...)` |
| `Accessor.allMethods(cls)` / `publicMethods(cls)` | `Reflect.on(cls).methods(flags...)` / `declaredMethods(flags...)` |
| `Accessor.allFields(cls)` | `Reflect.on(cls).fields(flags...)` |
| `Accessor.clearCaches()` | `Reflect.clearCaches()` |

`Reflect` is a fluent chain. Each step returns a new `Reflect`. A failed step (missing class, field or method) produces an empty chain that propagates silently, so check
the result at the end with `isPresent()`, `as(Type.class)` (returns an `Optional`) or `get(default)`.

```java
// 2.x
ArrayList<LuaClosure> cbs = Accessor.tryGet(event, "callbacks", null);

// 3.x
ArrayList<LuaClosure> cbs = Reflect.on(event).field("callbacks").as(ArrayList.class).orElse(null);
```

Filter flags (`Reflect.PUBLIC`, `PRIVATE`, `STATIC`, `INSTANCE`, `DECLARED`, ...) restrict `methods(...)` and `fields(...)`. Access flags are OR-combined with each other,
and so are static flags. When both groups are given, both must match.

For hot paths, `Reflect.fastcall(() -> Reflect.on(obj).getMethodHandle(...))` caches the resolved `MethodHandle` so later calls skip the lookup.

### 3. Prefer annotations over reflection inside patches

Patches that used `Accessor` to read or write private members of the target class can now bind them directly. This is usually faster and clearer.

```java
// 2.x
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class PlayerPatch {
    @Patch.OnEnter
    public static void enter(@Patch.This Object self) {
        int stamina = Accessor.tryGet(self, "stamina", 0);
        Accessor.trySet(self, "stamina", Math.max(10, stamina));
    }
}

// 3.x
@Patch(className = "zombie.characters.IsoPlayer", methodName = "update")
public class PlayerPatch {
    @Patch.OnEnter
    public static void enter(@Patch.Field int stamina) {
        if (stamina < 10) stamina = 10; // written back when the advice returns
    }
}
```

| Annotation | Use for |
|------------|---------|
| `@Patch.Field` | Read or write a field of the target class. The field name is inferred from the parameter name. Several names are tried in order, which is useful across game versions. Use `readOnly = true` for read-only access. |
| `@Patch.VarHandle` | A `VarHandle` for a private field, declared as a `public static VarHandle` in the patch class. |
| `@Patch.MethodHandle` | A `MethodHandle` for a private method. Needs `returnType` and `paramTypes`. |
| `@Patch.NameMap` | A `Map<String, String>` field in the patch class, filled with the resolved field names. |
| `@Shadow` | A stub class that mirrors a target class. Its `@Shadow.Field` and `@Shadow.Method` members are rewritten to `VarHandle` and `MethodHandle` access at load time. `@Shadow.Cast` marks a cast helper. |

Notes:

- Parameter names are used to infer field names, so build with debug info (the standard Gradle setup includes it).
- Multi-name lookups need the target class to be already loaded, so they can't be used in preload-time patches.
- `@Patch.Field`: declare `readOnly = true` parameters `final`, and specify `value` or `name`, not both. The bundled annotation processor is meant to enforce this, but it
  still matches the old `me.zed_0xff.zombie_buddy.Patch.Field` name, so don't rely on it.
- `optional = true` on a handle annotation leaves the handle `null` if the member is missing. By default, a missing member drops the whole patch class.
- When ZombieBuddy converts annotations in a jar, it also publicizes (makes `public`) that jar's classes and members, so your patch classes don't need to be public.

### 4. Java 25

The game now runs on Java 25 and ZombieBuddy 3.x is built with it. Update your build:

```groovy
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}
```


### 5. Lua exposure changes

- `Exposer.exposeAnnotatedClasses(...)`, `Exposer.hasGlobalLuaMethod(...)`, `Exposer.addClassWithGlobalLuaMethod(...)` and `Exposer.getClassesWithGlobalLuaMethod()` are
  no longer public. Annotate your classes with `@Exposer.LuaClass` and ZombieBuddy discovers them in your `javaPkgName` package. For runtime use, call
  `Exposer.exposeClass(MyClass.class)`.
- The ClassGraph dependency was dropped. Discovery now scans your jar directly, so classes to expose must be in your mod jar and in the `javaPkgName` package.
- `@Exposer.LuaClass` accepts a `name` to choose the Lua-side name. `ZombieBuddy.Events` is exposed this way.
- `exposeClass(Class)` now forces the class to initialize, so static initializers run before the class is exposed.
- `Exposer.exposeClassToLua(...)` is deprecated for removal. Switch to `exposeClass(...)`.

```diff
-Exposer.exposeClassToLua(MyApi.class);
+Exposer.exposeClass(MyApi.class);
```

### 6. Callbacks

New callbacks were added to `Callbacks`:

| Callback | Fires |
|----------|-------|
| `onGameInitComplete` (existing) | Once, after game init |
| `onDisplayCreate` (existing) | When the display is created |
| `beforeLuaInit` | Around Lua initialization (before) |
| `afterLuaInit` | Around Lua initialization (after) |
| `afterExposeAll` | After the game's class exposure pass |
| `onEndFrameUI` | At the end of each UI frame (can fire very often, so keep listeners cheap) |

A listener that throws no longer interrupts the others. Errors are logged, and after 50 errors per callback further logs are suppressed.

### 7. Logger

`Logger` gained tagged and per-instance loggers, one-shot logging, a `TRACE2` level and `printStackTrace`. The existing static calls (`Logger.info`, `warn`, `error`,
`debug`, `trace`) still work. `Logger.MAX_ARG_STRING_LENGTH` is gone.

```java
private static final Logger.Instance log = Logger.get("MyMod");

log.info("Started");
Logger.once.warn("Shown a single time");
Logger.printStackTrace(t);
```

The `verbosity` launch option now maps to log levels:

| Value | 2.x | 3.x |
|-------|-----|-----|
| `-2` | n/a | ERROR |
| `-1` | n/a | WARN |
| `0` (default) | Errors only | INFO (plus WARN and ERROR) |
| `1` | Patch transformations | DEBUG (includes patch transformation progress) |
| `2` | All debug output | TRACE |

### 8. Launch options

- `batch_approval_timeout` was removed.
- `http_client_timeout` (seconds, default `5`) and `http_cache_ttl` (seconds, default `3600`, `0` disables) are new.

See [Command Line Options](CommandLine.md).

### 9. Known authors list format

`authors.json` is now generated from one JSON file per author in the `authors/` directory. To add yourself, submit a new `authors/your-name.json` file, not an edit to
`authors.json`. See [Mod Signing](ModSigning.md).

## New in 3.x (optional)

- **Several jars per mod** (see below).
- **`@Patch.Field`, `@Patch.VarHandle`, `@Patch.MethodHandle`, `@Patch.NameMap`** and **`@Shadow`** for access to private members.
- **`Reflect`**, the fluent replacement for `Accessor`.
- **`jardump`**, a tool that dumps jar classes after the transformer pipeline. Useful for debugging how your annotations were converted.

## Supporting both 2.x and 3.x

A single jar compiled against 3.x can't run on 2.x, because it references `annotations.Patch`, `Reflect` and other new classes. To support both, build two jars and
declare both in `mod.info` using the numbered keys:

```ini
require=\ZombieBuddy
javaPkgName=com.yourname.yourmod

javaJarFile=media/java/YourMod-zb2.jar
ZBVersionMin=1.0.0
ZBVersionMax=2.9.9

javaJarFile2=media/java/YourMod-zb3.jar
ZBVersionMin2=3.0.0
```

- The suffix-less entry is treated as suffix `0`. Suffixes need not be sequential.
- Candidates are tried in ascending suffix order. The first one whose version range covers the running ZombieBuddy and whose path matches the platform (`client/` or
  `server/`) is loaded.
- ZombieBuddy 2.x only reads the suffix-less entry, so 2.x installs always get the first jar and ignore the numbered ones.
- `javaPkgName` is still a single entry shared by all jars.

## Checklist

1. Switch your imports to `me.zed_0xff.zombie_buddy.annotations.Patch`.
2. Replace every `Accessor` call with `Reflect` or with `@Patch.Field` / handle annotations.
3. Replace `exposeClassToLua(...)` and any use of now-internal `Exposer` methods.
4. Remove `warmUp` and `IKnowWhatIAmDoing` from your `@Patch` annotations.
5. Build with JDK 25.
6. Run the game with `verbosity=1` and look for patch warnings, such as dropped patch classes or fields that were not found.
7. If you still support 2.x, split into two jars and add the numbered `mod.info` keys.
