package dev.kestrel.harness;

import java.io.File;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.IllegalClassFormatException;
import java.lang.instrument.Instrumentation;
import java.security.ProtectionDomain;
import java.util.jar.JarFile;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.commons.AdviceAdapter;

/**
 * Kestrel tick-compare harness: vanilla-side instrumentation agent (skeleton).
 *
 * Why a plain -javaagent instead of a Fabric mod: Fabric intermediary
 * mappings for MC 26.2 are not published yet (meta.fabricmc.net returns the
 * "0.0.0" placeholder for game version 26.2 as of 2026-09-10 -- verified
 * directly, not from a stale doc). The conflict-rate-design.md doc (section
 * "Open questions", item 3) pre-approved this exact fallback: "If Fabric
 * Loom for 26.2 is broken on day one, fallback is a plain Java agent
 * (-javaagent) with ASM intercepts at the same points. Same log format,
 * same analyzer." Because 26.x ships fully deobfuscated (real class/method
 * names, no mappings needed at all), bytecode-level instrumentation against
 * real names is not just a fallback here, it is arguably simpler than
 * standing up Loom would have been.
 *
 * Scope of this skeleton (per work order 2026-09-10): prove the pipeline
 * end-to-end -- attach, find the real tick method, inject a call, log one
 * line per tick to a file. It does NOT yet implement the full access-class
 * tracking from conflict-rate-design.md section 2.3 (per-access READ/WRITE
 * logging, cascade tracking, ring buffer). That is a separate, later,
 * larger build (the actual conflict-rate experiment). This skeleton is the
 * general-purpose "run vanilla, capture per-tick state" harness referenced
 * in the plan's differential-testing requirement -- every future subsystem
 * gets proven against this before it lands.
 *
 * Hook point: {@code MinecraftServer.tickServer(BooleanSupplier)}, the real
 * per-tick entry point in the 26.2 deobfuscated jar (confirmed via javap
 * against META-INF/versions/26.2/server-26.2.jar inside the bundler jar).
 * We instrument entry and exit so state can eventually be hashed on both
 * sides of a tick (needed to localize divergence to "this tick changed
 * something" once real state hashing lands).
 *
 * Usage: {@code java -javaagent:kestrel-harness-agent.jar=/path/to/ticks.log -jar server.jar}
 */
public final class HarnessAgent {

    private static final String TARGET_CLASS_INTERNAL = "net/minecraft/server/MinecraftServer";
    private static final String TARGET_METHOD = "tickServer";
    private static final String TARGET_DESC = "(Ljava/util/function/BooleanSupplier;)V";

    /**
     * Chunk-gen stopwatch probes (G2 de-risking): method suffixes on
     * ChunkStatusTasks to time, in pipeline order. generateSurface (not
     * "generateSurface " with a typo) etc. must match the decompiled
     * signatures exactly (all take (WorldGenContext, ChunkStep,
     * StaticCache2D, ChunkAccess) and return CompletableFuture).
     * INITIALIZE_LIGHT keeps its own label (separate engine call from
     * light; a csv row flushes on light exit). FULL and SPAWN are excluded
     * (post-processing, not terrain cost).
     */
    private static final String CHUNKGEN_CLASS_INTERNAL =
            "net/minecraft/world/level/chunk/status/ChunkStatusTasks";
    private static final String CHUNK_STEP_CLASS_INTERNAL =
            "net/minecraft/world/level/chunk/status/ChunkStep";
    private static final String THREADED_LIGHT_ENGINE_CLASS_INTERNAL =
            "net/minecraft/server/level/ThreadedLevelLightEngine";
    private static final String WORLDGEN_RANDOM_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/WorldgenRandom";
    private static final String PLACED_FEATURE_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/placement/PlacedFeature";
    private static final String BIOME_FILTER_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/placement/BiomeFilter";
    private static final String ORE_FEATURE_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/feature/OreFeature";
    private static final String WORLDGEN_REGION_CLASS_INTERNAL =
            "net/minecraft/server/level/WorldGenRegion";
    private static final String STRUCTURE_START_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/structure/StructureStart";
    private static final String SERIALIZABLE_CHUNK_DATA_CLASS_INTERNAL =
            "net/minecraft/world/level/chunk/storage/SerializableChunkData";
    private static final String[] CHUNKGEN_STAGES = {
            "generateStructureStarts", "generateStructureReferences", "generateBiomes",
            "generateNoise", "generateSurface", "generateCarvers", "generateFeatures",
            "initializeLight", "light",
    };
    // NOTE: StaticCache2D is generic in bytecode
    // (StaticCache2D<GenerationChunkHolder>); descriptors never carry type
    // arguments, so the raw L...StaticCache2D; form below is exact.
    // (Stale note removed 2026-09-13: initializeLight DOES exist as a
    // method - javap-confirmed - and has its own probe since dce8d50.)
    private static final String CHUNKGEN_DESC =
            "(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
                    + "Lnet/minecraft/world/level/chunk/status/ChunkStep;"
                    + "Lnet/minecraft/util/StaticCache2D;"
                    + "Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                    + "Ljava/util/concurrent/CompletableFuture;";

    /**
     * G2 step 1: inside-supplier work timers. doFill (private, returns
     * ChunkAccess) is the synchronous CPU inside fillFromNoise's future;
     * doCreateBiomes (private, void) the same for createBiomes. Timing
     * these splits NOISE work from pool queue-wait (jfr-v1: 208ms
     * completion vs ~0.02ms submit). Both are instance methods (slot 0 =
     * this): doFill(Blender, StructureManager, RandomState, ChunkAccess,
     * int, int) -> chunk slot 4; doCreateBiomes(Blender, RandomState,
     * StructureManager, ChunkAccess) -> chunk slot 4. Hook by name only
     * (no overloads per javap 2026-09-13); report NOISE_WORK/BIOMES_WORK.
     */
    private static final String NOISE_WORK_CLASS_INTERNAL =
            "net/minecraft/world/level/levelgen/NoiseBasedChunkGenerator";

    private HarnessAgent() {
    }

    public static void premain(String agentArgs, Instrumentation inst) {
        String logPath = (agentArgs == null || agentArgs.isBlank())
                ? "kestrel-harness-ticks.log"
                : agentArgs;

        // The vanilla 26.2 server.jar is a bundler: it self-extracts
        // libraries + the real server jar and launches MinecraftServer in
        // its own isolated URLClassLoader, NOT the system/app classloader
        // that -javaagent appends this jar to. Injected bytecode inside
        // MinecraftServer calling TickLogger therefore throws
        // NoClassDefFoundError unless TickLogger is visible from every
        // classloader - which means bootstrap. appendToBootstrapClassLoaderSearch
        // makes a jar's classes resolvable via the bootstrap loader, which
        // every well-behaved classloader (including the bundler's) delegates
        // to before its own findClass. Confirmed necessary empirically
        // (2026-09-10 smoke test crashed with NoClassDefFoundError without this).
        //
        // IMPORTANT: the jar appended to bootstrap search must contain ONLY
        // TickLogger, never HarnessAgent itself. Appending this whole agent
        // jar (HarnessAgent + its inner classes + ASM) reproducibly crashed
        // premain with IllegalAccessError: HarnessAgent (app loader) could
        // no longer access its own anonymous inner class HarnessAgent$1
        // (which had started resolving via bootstrap once the same jar was
        // on bootstrap's search path too) - confirmed empirically
        // 2026-09-10. build.gradle packages TickLogger.class into a
        // separate sibling jar (kestrel-harness-boot.jar, same directory as
        // this jar) for exactly this reason; find and append THAT, nothing
        // else.
        try {
            inst.appendToBootstrapClassLoaderSearch(new JarFile(locateBootJar()));
        } catch (Exception e) {
            throw new RuntimeException(
                    "kestrel-harness: could not append boot jar to bootstrap classloader search", e);
        }

        String featuresTrace = System.getProperty("kestrel.featuresTrace");
        if (featuresTrace != null) {
            FeaturesTrace.init(featuresTrace);
            File traceDir = new File(featuresTrace).getAbsoluteFile();
            File traceParent = traceDir.getParentFile();
            if (traceParent == null) {
                throw new IllegalArgumentException(
                        "kestrel.featuresTrace must have a parent directory: " + featuresTrace);
            }
            // Tier-P oracle alongside the exact FEATURES/lifecycle trace.
            // HashProbe's blocks_hash is bit-for-bit compatible with core BlockHash.
            HashProbe.init(new File(traceParent, "prefix-hash-probe.csv").getPath());
            inst.addTransformer(new ClassFileTransformer() {
                @Override
                public byte[] transform(ClassLoader loader, String name, Class<?> redef,
                        ProtectionDomain domain, byte[] bytes) {
                    if (TARGET_CLASS_INTERNAL.equals(name)) {
                        return instrumentTraceServerStop(bytes);
                    }
                    if (CHUNKGEN_CLASS_INTERNAL.equals(name)) {
                        return instrumentFeaturesTrace(bytes);
                    }
                    if (NOISE_WORK_CLASS_INTERNAL.equals(name)) {
                        return instrumentTraceP1(bytes);
                    }
                    if (CHUNK_STEP_CLASS_INTERNAL.equals(name)) {
                        return instrumentChunkStepLighting(bytes);
                    }
                    if (THREADED_LIGHT_ENGINE_CLASS_INTERNAL.equals(name)) {
                        return instrumentThreadedLightEngine(bytes);
                    }
                    if (WORLDGEN_RANDOM_CLASS_INTERNAL.equals(name)) {
                        return instrumentFeatureSeeds(bytes);
                    }
                    if (PLACED_FEATURE_CLASS_INTERNAL.equals(name)) {
                        return instrumentPlacedFeatures(bytes);
                    }
                    if (BIOME_FILTER_CLASS_INTERNAL.equals(name)) {
                        return instrumentBiomeFilter(bytes);
                    }
                    if (ORE_FEATURE_CLASS_INTERNAL.equals(name)) {
                        return instrumentOreFeature(bytes);
                    }
                    if (WORLDGEN_REGION_CLASS_INTERNAL.equals(name)) {
                        return instrumentWorldgenHeight(bytes);
                    }
                    if (STRUCTURE_START_CLASS_INTERNAL.equals(name)) {
                        return instrumentStructureStart(bytes);
                    }
                    if (SERIALIZABLE_CHUNK_DATA_CLASS_INTERNAL.equals(name)) {
                        return instrumentSerializableChunkRead(bytes);
                    }
                    return null;
                }
            });
            return; // Dedicated trace mode leaves unrelated timing/hash probes disabled.
        }

        TickLogger.init(logPath);
        BlockShapeAudit.setOutputPath(siblingPath(logPath, "block-shape-audit.txt"));
        ChunkgenWatch.init(siblingPath(logPath, "chunkgen-watch.csv"));
        HashProbe.init(siblingPath(logPath, "hash-probe.csv"));
        System.out.println("[kestrel-harness] agent attached, logging ticks to " + logPath);

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(ClassLoader loader, String className,
                                     Class<?> classBeingRedefined,
                                     ProtectionDomain protectionDomain,
                                     byte[] classfileBuffer) throws IllegalClassFormatException {
                try {
                    if (TARGET_CLASS_INTERNAL.equals(className)) {
                        return instrumentTickServer(classfileBuffer, loader);
                    }
                    if (CHUNKGEN_CLASS_INTERNAL.equals(className)) {
                        return instrumentChunkgen(classfileBuffer, loader);
                    }
                    if (NOISE_WORK_CLASS_INTERNAL.equals(className)) {
                        return instrumentNoiseWork(classfileBuffer, loader);
                    }
                    return null; // no change
                } catch (Throwable t) {
                    // Never let instrumentation failure silently swallow the
                    // real class - fail loudly so a broken hook can't masquerade
                    // as a passing differential run.
                    System.err.println("[kestrel-harness] FAILED to instrument " + className);
                    t.printStackTrace();
                    throw new IllegalClassFormatException(t.toString());
                }
            }
        }, false);
    }

    private static String siblingPath(String logPath, String siblingFileName) {
        File logFile = new File(logPath).getAbsoluteFile();
        return new File(logFile.getParentFile(), siblingFileName).getPath();
    }

    private static File locateBootJar() throws Exception {
        File selfJar = new File(HarnessAgent.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        File bootJar = new File(selfJar.getParentFile(), "kestrel-harness-boot.jar");
        if (!bootJar.isFile()) {
            throw new IllegalStateException(
                    "kestrel-harness-boot.jar not found next to " + selfJar
                            + " - build.gradle's bootJar task must run alongside shadowJar (both are on `build`).");
        }
        return bootJar;
    }

    private static byte[] instrumentTickServer(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return targetLoader != null ? targetLoader : ClassLoader.getSystemClassLoader();
            }
        };

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (TARGET_METHOD.equals(name) && TARGET_DESC.equals(descriptor)) {
                    System.out.println("[kestrel-harness] instrumenting " + TARGET_CLASS_INTERNAL
                            + "." + name + descriptor);
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        @Override
                        protected void onMethodEnter() {
                            emitLogCall("enter");
                        }

                        @Override
                        protected void onMethodExit(int opcode) {
                            emitLogCall("exit");
                        }

                        private void emitLogCall(String phase) {
                            // Probe-hit report every 100 tick enters, as
                            // bytecode (transform time vs runtime: this code
                            // emits instructions, it does not run them).
                            // Counter lives on ChunkgenWatch (bootstrap).
                            if ("enter".equals(phase)) {
                                visitFieldInsn(Opcodes.GETSTATIC,
                                        "dev/kestrel/harness/ChunkgenWatch",
                                        "PROBE_HITS", "Ljava/util/concurrent/atomic/AtomicLong;");
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/util/concurrent/atomic/AtomicLong",
                                        "get", "()J", false);
                                visitLdcInsn(new Long(100L));
                                visitInsn(Opcodes.LREM);
                                visitInsn(Opcodes.LCONST_0);
                                visitInsn(Opcodes.LCMP);
                                Label skip = new Label();
                                visitJumpInsn(Opcodes.IFNE, skip);
                                visitFieldInsn(Opcodes.GETSTATIC,
                                        "java/lang/System", "out", "Ljava/io/PrintStream;");
                                visitTypeInsn(Opcodes.NEW, "java/lang/StringBuilder");
                                visitInsn(Opcodes.DUP);
                                visitMethodInsn(Opcodes.INVOKESPECIAL,
                                        "java/lang/StringBuilder", "<init>", "()V", false);
                                visitLdcInsn("[kestrel-harness] probe-hits=");
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/lang/StringBuilder", "append",
                                        "(Ljava/lang/String;)Ljava/lang/StringBuilder;", false);
                                visitFieldInsn(Opcodes.GETSTATIC,
                                        "dev/kestrel/harness/ChunkgenWatch",
                                        "PROBE_HITS", "Ljava/util/concurrent/atomic/AtomicLong;");
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/util/concurrent/atomic/AtomicLong",
                                        "get", "()J", false);
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/lang/StringBuilder", "append",
                                        "(J)Ljava/lang/StringBuilder;", false);
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false);
                                visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                        "java/io/PrintStream", "println", "(Ljava/lang/String;)V", false);
                                visitLabel(skip);
                            }
                            loadThis();
                            visitLdcInsn(phase);
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/TickLogger",
                                    "onTick",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        }
                    };
                }
                return mv;
            }
        };

        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * Time each ChunkStatusTasks.generate* stage per chunk. The methods all
     * take ChunkAccess as arg 4 (index 3); its getPos() gives ChunkPos whose
     * pack() is the row key. Stage label = method name minus "generate",
     * upper-cased. There is no initializeLight method (light covers both
     * engine calls); INITIALIZE_LIGHT survives only as a csv column for a
     * future inside-engine split. A csv row flushes on light exit.
     */
    private static byte[] instrumentPlacedFeatures(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("placeWithBiomeCheck")) return mv;
                System.out.println("[kestrel-harness] PlacedFeature placement trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodEnter() {
                        visitLdcInsn("ENTER");
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "featurePlaceTimeline",
                                "(Ljava/lang/String;)V", false);
                        loadThis();
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "featurePlace",
                                "(Ljava/lang/Object;)V", false);
                    }

                    @Override protected void onMethodExit(int opcode) {
                        if (opcode == Opcodes.ATHROW) return;
                        visitLdcInsn("EXIT");
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "featurePlaceTimeline",
                                "(Ljava/lang/String;)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentThreadedLightEngine(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals("runUpdate") && desc.equals("()V")) {
                    System.out.println("[kestrel-harness] light executor batch trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            loadThis();
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace", "lightBatchEnter",
                                    "(Ljava/lang/Object;)V", false);
                        }

                        @Override protected void onMethodExit(int opcode) {
                            loadThis();
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace", "lightBatchExit",
                                    "(Ljava/lang/Object;)V", false);
                        }
                    };
                }
                if (name.equals("lambda$lightChunk$0")
                        && desc.equals("(ZLnet/minecraft/world/level/ChunkPos;)V")) {
                    System.out.println("[kestrel-harness] LIGHT PRE_UPDATE operation trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            visitLdcInsn("LIGHT");
                            loadArg(1);
                            loadArg(0);
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "lightExecutorChunkOp",
                                    "(Ljava/lang/String;Ljava/lang/Object;Z)V", false);
                        }
                    };
                }
                if (name.equals("lambda$checkBlock$0")
                        && desc.equals("(Lnet/minecraft/core/BlockPos;)V")) {
                    System.out.println("[kestrel-harness] block-light PRE_UPDATE operation trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            visitLdcInsn("CHECK_BLOCK");
                            loadArg(0);
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "lightExecutorBlockOp",
                                    "(Ljava/lang/String;Ljava/lang/Object;)V", false);
                        }
                    };
                }
                if (name.equals("lambda$updateSectionStatus$1")
                        && desc.equals("(Lnet/minecraft/core/SectionPos;Z)V")) {
                    System.out.println("[kestrel-harness] section-status PRE_UPDATE operation trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            visitLdcInsn("SECTION_STATUS");
                            loadArg(0);
                            loadArg(1);
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "lightExecutorSectionOp",
                                    "(Ljava/lang/String;Ljava/lang/Object;Z)V", false);
                        }
                    };
                }
                if (name.equals("lambda$updateChunkStatus$1")
                        && desc.equals("(Lnet/minecraft/world/level/ChunkPos;)V")) {
                    System.out.println("[kestrel-harness] chunk-light eviction PRE_UPDATE trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            loadThis();
                            loadArg(0);
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "lightExecutorUnloadOp",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V", false);
                        }
                    };
                }
                if (!name.equals("lambda$initializeLight$0")
                        || !desc.equals(
                                "(Lnet/minecraft/world/level/chunk/ChunkAccess;"
                                        + "Lnet/minecraft/world/level/ChunkPos;)V")) {
                    return mv;
                }
                System.out.println(
                        "[kestrel-harness] INITIALIZE_LIGHT PRE_UPDATE timeline attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodEnter() {
                        loadThis();
                        loadArg(0);
                        loadArg(1);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "lightExecutorInitializeOp",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                false);
                        loadArg(1);
                        visitLdcInsn("ENTER");
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "initializeLightPreTimeline",
                                "(Ljava/lang/Object;Ljava/lang/String;)V",
                                false);
                    }

                    @Override protected void onMethodExit(int opcode) {
                        if (opcode == Opcodes.ATHROW) return;
                        loadArg(1);
                        visitLdcInsn("EXIT");
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "initializeLightPreTimeline",
                                "(Ljava/lang/Object;Ljava/lang/String;)V",
                                false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentTraceServerStop(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("stopServer") || !desc.equals("()V")) return mv;
                System.out.println("[kestrel-harness] final light snapshot attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodEnter() {
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "flushFinalLightData",
                                "()V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentWorldgenHeight(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (name.equals("getHeight")
                        && desc.equals("(Lnet/minecraft/world/level/levelgen/Heightmap$Types;II)I")) {
                    System.out.println("[kestrel-harness] WorldGenRegion height trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.IRETURN) return;
                            // Preserve the original return value on the operand stack.
                            // Avoid new locals so existing StackMapTable frames remain valid.
                            dup();
                            loadThis();
                            swap();
                            loadArg(0);
                            swap();
                            loadArg(1);
                            swap();
                            loadArg(2);
                            swap();
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace", "heightQuery",
                                    "(Ljava/lang/Object;Ljava/lang/Object;III)V", false);
                        }
                    };
                }
                if (name.equals("addFreshEntity")
                        && desc.equals("(Lnet/minecraft/world/entity/Entity;)Z")) {
                    System.out.println(
                            "[kestrel-harness] WorldGenRegion spawn entity-add trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.IRETURN) return;
                            // Stack: [returnBoolean]. Keep one copy for IRETURN
                            // and pass the other with (region, entity).
                            dup();
                            loadThis();
                            loadArg(0);
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "spawnEntityAdded",
                                    "(ZLjava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }
                if (name.equals("getBlockEntity")
                        && desc.equals("(Lnet/minecraft/core/BlockPos;)"
                                + "Lnet/minecraft/world/level/block/entity/BlockEntity;")) {
                    System.out.println(
                            "[kestrel-harness] WorldGenRegion block-entity trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.ARETURN) return;
                            // Stack on entry: [returnedBlockEntity]. Keep one copy
                            // for the original ARETURN and pass the duplicate to
                            // the observer together with the queried BlockPos.
                            dup();
                            loadArg(0);
                            swap();
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "blockEntityQuery",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }
                return mv;
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentSerializableChunkRead(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        // Do not COMPUTE_FRAMES here. SerializableChunkData is loaded during
        // early server boot and frame recomputation can recursively resolve a
        // large part of the chunk hierarchy while the defining loader is
        // active. This hook needs no new local at all: duplicate the ARETURN
        // value, put (ChunkPos, returned ProtoChunk) under the hook call, and
        // leave the original return value on the operand stack.
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("read")
                        || !desc.endsWith(")Lnet/minecraft/world/level/chunk/ProtoChunk;")
                        || !desc.contains(
                                "Lnet/minecraft/world/level/chunk/storage/RegionStorageInfo;")
                        || !desc.contains("Lnet/minecraft/world/level/ChunkPos;")) {
                    return mv;
                }
                System.out.println(
                        "[kestrel-harness] SerializableChunkData read trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.ARETURN) return;
                        // Stack on entry: [returnedChunk]
                        dup();
                        // [returnedChunk, returnedChunk]
                        loadArg(3);
                        // [returnedChunk, returnedChunk, chunkPos]
                        swap();
                        // [returnedChunk, chunkPos, returnedChunk]
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "chunkRead",
                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                false);
                        // Stack for original ARETURN: [returnedChunk]
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentStructureStart(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("placeInChunk")
                        || !desc.equals(
                                "(Lnet/minecraft/world/level/WorldGenLevel;"
                                + "Lnet/minecraft/world/level/StructureManager;"
                                + "Lnet/minecraft/world/level/chunk/ChunkGenerator;"
                                + "Lnet/minecraft/util/RandomSource;"
                                + "Lnet/minecraft/world/level/levelgen/structure/BoundingBox;"
                                + "Lnet/minecraft/world/level/ChunkPos;)V")) {
                    return mv;
                }
                System.out.println(
                        "[kestrel-harness] StructureStart placement trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override
                    protected void onMethodEnter() {
                        loadThis();
                        loadArg(0);
                        loadArg(5);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "structurePlaceEnter",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                false);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.RETURN) return;
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "structurePlaceExit",
                                "()V",
                                false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentOreFeature(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(
                reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("canPlaceOre")
                        || !desc.equals("(Lnet/minecraft/world/level/block/state/BlockState;"
                                + "Ljava/util/function/Function;"
                                + "Lnet/minecraft/util/RandomSource;"
                                + "Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration;"
                                + "Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration$TargetBlockState;"
                                + "Lnet/minecraft/core/BlockPos$MutableBlockPos;)Z")) {
                    return mv;
                }
                System.out.println("[kestrel-harness] OreFeature canPlace trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.IRETURN) return;
                        dup();
                        loadArg(0);
                        swap();
                        loadArg(5);
                        swap();
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "oreCanPlace",
                                "(Ljava/lang/Object;Ljava/lang/Object;Z)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentBiomeFilter(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("shouldPlace")
                        || !desc.equals("(Lnet/minecraft/world/level/levelgen/placement/PlacementContext;"
                                + "Lnet/minecraft/util/RandomSource;"
                                + "Lnet/minecraft/core/BlockPos;)Z")) return mv;
                System.out.println("[kestrel-harness] BiomeFilter decision trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.IRETURN) return;
                        dup();
                        loadArg(2);
                        swap();
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "biomeFilter",
                                "(Ljava/lang/Object;Z)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentFeatureSeeds(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("setFeatureSeed") || !desc.equals("(JII)V")) return mv;
                System.out.println("[kestrel-harness] WorldgenRandom feature-seed trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodEnter() {
                        loadArg(0);
                        loadArg(1);
                        loadArg(2);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace", "featureSeed",
                                "(JII)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentChunkStepLighting(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("apply")
                        || !desc.equals(
                                "(Lnet/minecraft/world/level/chunk/status/WorldGenContext;"
                                        + "Lnet/minecraft/util/StaticCache2D;"
                                        + "Lnet/minecraft/world/level/chunk/ChunkAccess;)"
                                        + "Ljava/util/concurrent/CompletableFuture;")) {
                    return mv;
                }
                System.out.println(
                        "[kestrel-harness] ChunkStep lighting-completion trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.ARETURN) return;
                        // Stack: [applyFuture]. Keep the original for ARETURN.
                        // The returned future includes completeChunkGeneration,
                        // so its completion is after persisted-status publication.
                        dup();
                        loadThis();
                        loadArg(2);
                        loadArg(0);
                        visitMethodInsn(
                                Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/FeaturesTrace",
                                "observeChunkStepCompletion",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentFeaturesTrace(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!desc.equals(CHUNKGEN_DESC)) return mv;

                if (name.equals("generateCarvers")) {
                    System.out.println("[kestrel-harness] CARVERS P2 hash trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.ARETURN) return;
                            // Hash only after Vanilla's CARVERS work has completed.
                            // The returned future itself remains untouched.
                            loadArg(3);
                            visitLdcInsn("P2");
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/HashProbe",
                                    "recordChunk",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        }
                    };
                }

                if (name.equals("generateSpawn")) {
                    System.out.println("[kestrel-harness] SPAWN entity-delta trace attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            for (int i = 0; i < 4; i++) loadArg(i);
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "spawnEnter",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                        }

                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.ARETURN) return;
                            dup();
                            for (int i = 0; i < 4; i++) loadArg(i);
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "spawnExit",
                                    "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                        }
                    };
                }

                if (name.equals("initializeLight") && desc.equals(CHUNKGEN_DESC)) {
                    System.out.println(
                            "[kestrel-harness] INITIALIZE_LIGHT task timeline attached");
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                        @Override protected void onMethodEnter() {
                            loadArg(0);
                            loadArg(3);
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "initializeLightScheduled",
                                    "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                    false);
                            loadArg(3);
                            visitLdcInsn("ENTER");
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "initializeLightTaskTimeline",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        }

                        @Override protected void onMethodExit(int opcode) {
                            if (opcode != Opcodes.ARETURN) return;
                            loadArg(3);
                            visitLdcInsn("EXIT");
                            visitMethodInsn(
                                    Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/FeaturesTrace",
                                    "initializeLightTaskTimeline",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        }
                    };
                }

                if (!name.equals("generateFeatures")) return mv;
                System.out.println("[kestrel-harness] FEATURES entry/completion trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodEnter() {
                        for (int i = 0; i < 4; i++) loadArg(i);
                        visitMethodInsn(Opcodes.INVOKESTATIC, "dev/kestrel/harness/FeaturesTrace", "enter",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                    }
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.ARETURN) return; // Unmatched ENTER invalidates an aborted capture.
                        dup();
                        for (int i = 0; i < 4; i++) loadArg(i);
                        visitMethodInsn(Opcodes.INVOKESTATIC, "dev/kestrel/harness/FeaturesTrace", "exit",
                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V", false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentTraceP1(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String desc,
                    String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                if (!name.equals("fillFromNoise")) return mv;
                System.out.println("[kestrel-harness] NOISE P1 hash trace attached");
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, desc) {
                    @Override protected void onMethodExit(int opcode) {
                        if (opcode != Opcodes.ARETURN) return;
                        // fillFromNoise returns before its background work finishes.
                        // Replace the returned future with HashProbe's pass-through
                        // completion hook so P1 is captured at the real NOISE cut.
                        loadArg(3);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/HashProbe",
                                "hookP1Future",
                                "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;)"
                                        + "Ljava/util/concurrent/CompletableFuture;",
                                false);
                    }
                };
            }
        }, ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] instrumentChunkgen(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return targetLoader != null ? targetLoader : ClassLoader.getSystemClassLoader();
            }
        };

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                String stage = null;
                for (String m : CHUNKGEN_STAGES) {
                    if (m.equals(name) && CHUNKGEN_DESC.equals(descriptor)) {
                        // generateStructureStarts -> STRUCTURE_STARTS ...,
                        // light -> LIGHT. initializeLight is a REAL method
                        // (javap-confirmed 2026-09-13) and must map to
                        // INITIALIZE_LIGHT, not LIGHT - the old
                        // startsWith("generate") ternary sent it to LIGHT,
                        // collapsing both light stages into one slot and
                        // flushing the row on initializeLight exit with the
                        // light column still zero (plus19: the light exit
                        // then found no open entry... actually it overwrote
                        // the same slot, but the row was already gone).
                        if ("light".equals(m)) {
                            stage = "LIGHT";
                        } else if ("initializeLight".equals(m)) {
                            stage = "INITIALIZE_LIGHT";
                        } else {
                            stage = m.substring("generate".length())
                                    .toUpperCase(java.util.Locale.ROOT);
                        }
                        break;
                    }
                }
                if (stage == null) {
                    return mv;
                }
                System.out.println("[kestrel-harness] instrumenting chunkgen " + name);
                final String label = stage;
                final String stageName = name;
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        emitChunkgenCall("enter", label);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        emitChunkgenCall("exit", label);
                        // G2 hash probe cut points:
                        // P2 = CARVERS exit (inline applyCarvers finished on chunk)
                        // P3 = LIGHT exit (features and lighting finished, matching chunkgen-watch row flush)
                        if ("generateCarvers".equals(stageName)) {
                            loadArg(3);
                            visitLdcInsn("P2");
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/HashProbe",
                                    "recordChunk",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        } else if ("light".equals(stageName)) {
                            loadArg(3);
                            visitLdcInsn("P3");
                            visitMethodInsn(Opcodes.INVOKESTATIC,
                                    "dev/kestrel/harness/HashProbe",
                                    "recordChunk",
                                    "(Ljava/lang/Object;Ljava/lang/String;)V",
                                    false);
                        }
                    }

                    private void emitChunkgenCall(String phase, String lbl) {
                        // Static methods: arg 0..3 = (context, step, cache,
                        // chunk). Instance would shift by one - these
                        // generate* methods are static (javap 2026-09-13),
                        // so index 3 is the ChunkAccess. 26.2 packs via
                        // instance pack() (no toLong in this version).
                        //
                        // Runtime hit counter FIRST, as bytecode (not Java:
                        // this method runs at transform time). Counter lives
                        // on ChunkgenWatch (bootstrap loader, visible from
                        // the bundler child loader where this code runs).
                        if ("enter".equals(phase)) {
                            visitFieldInsn(Opcodes.GETSTATIC,
                                    "dev/kestrel/harness/ChunkgenWatch",
                                    "PROBE_HITS", "Ljava/util/concurrent/atomic/AtomicLong;");
                            visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                    "java/util/concurrent/atomic/AtomicLong",
                                    "getAndIncrement", "()J", false);
                            visitInsn(Opcodes.POP2);
                        }
                        loadArg(3);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/chunk/ChunkAccess",
                                "getPos", "()Lnet/minecraft/world/level/ChunkPos;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/ChunkPos",
                                "pack", "()J", false);
                        visitLdcInsn(lbl);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/ChunkgenWatch", phase, "(JLjava/lang/String;)V",
                                false);
                    }
                };
            }
        };

        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }

    /**
     * G2 step 1: time the synchronous CPU inside the async NOISE/BIOMES
     * futures. doFill and doCreateBiomes are private instance methods
     * (javap 2026-09-13, no overloads), so match by name alone; the
     * ChunkAccess is ASM-method-arg index 3 in BOTH (AdviceAdapter.loadArg
     * counts method args only, NOT the this slot). Reported as
     * NOISE_WORK/BIOMES_WORK into the same ChunkgenWatch sink (new columns,
     * no row flush - rows still flush on LIGHT).
     *
     * In addition, fillFromNoise (public instance method, chunk arg 3) is hooked
     * at method exit to wrap the returned CompletableFuture via HashProbe.hookP1Future:
     * noise generation is asynchronous on Util.backgroundExecutor(), so capturing
     * the P1 hash at method return would see empty terrain before the worker runs.
     * Chaining off the future completion captures the P1 hash when noise is actually done.
     */
    private static byte[] instrumentNoiseWork(byte[] classBytes, ClassLoader targetLoader) {
        ClassReader cr = new ClassReader(classBytes);
        ClassWriter cw = new ClassWriter(cr, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected ClassLoader getClassLoader() {
                return targetLoader != null ? targetLoader : ClassLoader.getSystemClassLoader();
            }
        };

        ClassVisitor cv = new ClassVisitor(Opcodes.ASM9, cw) {
            @Override
            public MethodVisitor visitMethod(int access, String name, String descriptor,
                                              String signature, String[] exceptions) {
                MethodVisitor mv = super.visitMethod(access, name, descriptor, signature, exceptions);
                String label;
                int chunkArg;
                // AdviceAdapter.loadArg counts METHOD ARGS, not raw slots
                // (slot 0 = this is skipped). doFill(Blender, SM, RS,
                // ChunkAccess, int, int): chunk = method arg 3 (raw slot 4).
                // doCreateBiomes(Blender, RS, SM, ChunkAccess): chunk =
                // method arg 3 (raw slot 4) as well.
                // (NOT loadArg(4): that loads an int for doFill /
                // off-the-end for doCreateBiomes - VerifyError or silent
                // wrong-object probes. The raw-slot-3 fix of 2026-09-13 was
                // right about the OBJECT but loadArg counts differently from
                // raw slots; the tripwire stays: NOISE_WORK medians vs JFR
                // completion times.)
                if ("doFill".equals(name)) {
                    label = "NOISE_WORK";
                    chunkArg = 3;
                } else if ("doCreateBiomes".equals(name)) {
                    label = "BIOMES_WORK";
                    chunkArg = 3;
                } else if ("fillFromNoise".equals(name)) {
                    // G2 step 1 P1 hash probe:
                    // Hook the CompletableFuture returned by fillFromNoise.
                    // Method exit is NOT enough because fillFromNoise returns before the
                    // backgroundExecutor worker has run the noise supplier. We wrap the
                    // returned CompletableFuture via HashProbe.hookP1Future so the P1 hash
                    // captures when the future actually completes.
                    System.out.println("[kestrel-harness] instrumenting noise-fill (P1 probe) " + name);
                    return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                        @Override
                        protected void onMethodExit(int opcode) {
                            if (opcode == Opcodes.ARETURN) {
                                loadArg(3);
                                visitMethodInsn(Opcodes.INVOKESTATIC,
                                        "dev/kestrel/harness/HashProbe",
                                        "hookP1Future",
                                        "(Ljava/util/concurrent/CompletableFuture;Ljava/lang/Object;)Ljava/util/concurrent/CompletableFuture;",
                                        false);
                            }
                        }
                    };
                } else {
                    return mv;
                }
                System.out.println("[kestrel-harness] instrumenting noise-work " + name);
                final String lbl = label;
                final int arg = chunkArg;
                return new AdviceAdapter(Opcodes.ASM9, mv, access, name, descriptor) {
                    @Override
                    protected void onMethodEnter() {
                        loadArg(arg);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/chunk/ChunkAccess",
                                "getPos", "()Lnet/minecraft/world/level/ChunkPos;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/ChunkPos",
                                "pack", "()J", false);
                        visitLdcInsn(lbl);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/ChunkgenWatch", "enter", "(JLjava/lang/String;)V",
                                false);
                    }

                    @Override
                    protected void onMethodExit(int opcode) {
                        loadArg(arg);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/chunk/ChunkAccess",
                                "getPos", "()Lnet/minecraft/world/level/ChunkPos;", false);
                        visitMethodInsn(Opcodes.INVOKEVIRTUAL,
                                "net/minecraft/world/level/ChunkPos",
                                "pack", "()J", false);
                        visitLdcInsn(lbl);
                        visitMethodInsn(Opcodes.INVOKESTATIC,
                                "dev/kestrel/harness/ChunkgenWatch", "exit", "(JLjava/lang/String;)V",
                                false);
                    }
                };
            }
        };

        cr.accept(cv, ClassReader.EXPAND_FRAMES);
        return cw.toByteArray();
    }
}
