package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import me.zed_0xff.zombie_buddy.transformers.Pipeline;
import me.zed_0xff.zombie_buddy.transformers.TransformedJar;
import net.bytebuddy.agent.ByteBuddyAgent;
import net.bytebuddy.dynamic.loading.ClassInjector;

class TypedThisMergedTest {
    private static final String TARGET = "testjar.TypedThisTarget";
    private static final String PATCH_PACKAGE = "typedthis";

    @Test
    void typedThisOnUnloadedTarget(@TempDir Path tempDir) throws Exception {
        assumeTrue(ClassInjector.UsingUnsafe.isAvailable(), "requires ClassInjector.UsingUnsafe");

        Loader.g_instrumentation = ByteBuddyAgent.install();
        assertFalse(isLoaded(TARGET), "target must not be loaded before patches are applied");

        Path patchJar = buildPatchJar(tempDir);
        TransformedJar transformed = Pipeline.transformPatchJar(patchJar, PATCH_PACKAGE);
        Loader.g_transformed_jars.put(patchJar, transformed);

        try {
            ClassLoader modLoader = ModJarInjector.injectMerged(transformed);
            PatchEngine.applyPatches(transformed.patches(), modLoader, transformed);

            Class<?> target = modLoader.loadClass(TARGET);
            Object instance = target.getDeclaredConstructor().newInstance();
            target.getDeclaredMethod("ping").invoke(instance);

            assertTrue(target.getField("enterCalled").getBoolean(null), "@Patch.This typed advice should run");
        } finally {
            Loader.g_transformed_jars.remove(patchJar);
        }
    }

    @Test
    void typedThisFromAnotherMergedJar(@TempDir Path tempDir) throws Exception {
        assumeTrue(ClassInjector.UsingUnsafe.isAvailable(), "requires ClassInjector.UsingUnsafe");

        Loader.g_instrumentation = ByteBuddyAgent.install();

        Path classesDir = compileCrossJarClasses(tempDir);
        Path markerJar = buildJar(tempDir.resolve("marker.jar"), classesDir, "typedthis/cross/Marker.class");
        Path targetJar = buildJar(tempDir.resolve("target.jar"), classesDir,
                "typedthis/cross/Target.class", "typedthis/cross/TypedPatch.class");

        TransformedJar transformedMarker = Pipeline.transformPatchJar(markerJar, "typedthis.cross");
        TransformedJar transformedTarget = Pipeline.transformPatchJar(targetJar, "typedthis.cross");
        Loader.g_transformed_jars.put(markerJar, transformedMarker);
        Loader.g_transformed_jars.put(targetJar, transformedTarget);

        try {
            ClassLoader modLoader = ModJarInjector.injectMerged(transformedMarker);
            ModJarInjector.injectMerged(transformedTarget);
            PatchEngine.applyPatches(transformedTarget.patches(), modLoader, transformedTarget);

            Class<?> target = modLoader.loadClass("typedthis.cross.Target");
            Object instance = target.getDeclaredConstructor().newInstance();
            target.getDeclaredMethod("ping").invoke(instance);

            assertTrue(target.getField("enterCalled").getBoolean(null),
                    "typed @Patch.This from another merged jar should run");
        } finally {
            Loader.g_transformed_jars.remove(markerJar);
            Loader.g_transformed_jars.remove(targetJar);
        }
    }

    private static boolean isLoaded(String className) {
        for (Class<?> cls : Loader.g_instrumentation.getAllLoadedClasses()) {
            if (className.equals(cls.getName())) {
                return true;
            }
        }
        return false;
    }

    private static Path compileCrossJarClasses(Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("cross-src/typedthis/cross");
        Files.createDirectories(sourceDir);

        Files.writeString(sourceDir.resolve("Marker.java"), """
                package typedthis.cross;

                public interface Marker {}
                """);

        Files.writeString(sourceDir.resolve("Target.java"), """
                package typedthis.cross;

                public class Target implements Marker {
                    public static boolean enterCalled;

                    public void ping() {}
                }
                """);

        Files.writeString(sourceDir.resolve("TypedPatch.java"), """
                package typedthis.cross;

                import me.zed_0xff.zombie_buddy.annotations.Patch;

                @Patch(className = "typedthis.cross.Target", methodName = "ping")
                public class TypedPatch {
                    @Patch.OnEnter
                    public static void enter(@Patch.This Marker self) {
                        Target.enterCalled = true;
                    }
                }
                """);

        Path classesDir = tempDir.resolve("cross-classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream compilerErrors = new ByteArrayOutputStream();
        int result = compiler.run(null, null, compilerErrors,
                "-proc:none",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                sourceDir.resolve("Marker.java").toString(),
                sourceDir.resolve("Target.java").toString(),
                sourceDir.resolve("TypedPatch.java").toString());
        assertEquals(0, result, compilerErrors.toString());

        return classesDir;
    }

    private static Path buildJar(Path jarPath, Path classesDir, String... entries) throws Exception {
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(jarPath))) {
            for (String entry : entries) {
                jar.putNextEntry(new JarEntry(entry));
                jar.write(Files.readAllBytes(classesDir.resolve(entry)));
                jar.closeEntry();
            }
        }

        return jarPath;
    }

    private static Path buildPatchJar(Path tempDir) throws Exception {
        String source = """
                package typedthis;

                import me.zed_0xff.zombie_buddy.annotations.Patch;
                import testjar.TypedThisTarget;

                @Patch(className = "%s", methodName = "ping")
                public class TypedThisPatch {
                    @Patch.OnEnter
                    public static void enter(@Patch.This TypedThisTarget self) {
                        TypedThisTarget.enterCalled = true;
                    }
                }
                """.formatted(TARGET);

        Path sourceFile = tempDir.resolve("src/typedthis/TypedThisPatch.java");
        Files.createDirectories(sourceFile.getParent());
        Files.writeString(sourceFile, source);

        Path classesDir = tempDir.resolve("classes");
        Files.createDirectories(classesDir);

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        ByteArrayOutputStream compilerErrors = new ByteArrayOutputStream();
        int result = compiler.run(null, null, compilerErrors,
                "-proc:none",
                "-classpath", System.getProperty("java.class.path"),
                "-d", classesDir.toString(),
                sourceFile.toString());
        assertEquals(0, result, compilerErrors.toString());

        Path patchJar = tempDir.resolve("typed-this-patch.jar");
        try (JarOutputStream jar = new JarOutputStream(Files.newOutputStream(patchJar))) {
            jar.putNextEntry(new JarEntry("typedthis/TypedThisPatch.class"));
            jar.write(Files.readAllBytes(classesDir.resolve("typedthis/TypedThisPatch.class")));
            jar.closeEntry();
        }

        return patchJar;
    }
}
