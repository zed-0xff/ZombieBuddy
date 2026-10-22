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

            Class<?> target = Class.forName(TARGET, false, ClassLoader.getSystemClassLoader());
            Object instance = target.getDeclaredConstructor().newInstance();
            target.getDeclaredMethod("ping").invoke(instance);

            assertTrue(target.getField("enterCalled").getBoolean(null), "@Patch.This typed advice should run");
        } finally {
            Loader.g_transformed_jars.remove(patchJar);
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
