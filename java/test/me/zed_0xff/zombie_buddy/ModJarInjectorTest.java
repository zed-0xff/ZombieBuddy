package me.zed_0xff.zombie_buddy;

import me.zed_0xff.zombie_buddy.transformers.TransformedJar;
import net.bytebuddy.dynamic.loading.ClassInjector;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModJarInjectorTest {
    private static byte[] classBytes(String name, int access, String superName, String... interfaces) {
        ClassWriter cw = new ClassWriter(0);
        String[] internalInterfaces = new String[interfaces.length];
        for (int i = 0; i < interfaces.length; i++) {
            internalInterfaces[i] = interfaces[i].replace('.', '/');
        }
        cw.visit(Opcodes.V17, access, name.replace('.', '/'), null, superName.replace('.', '/'), internalInterfaces);
        cw.visitEnd();
        return cw.toByteArray();
    }

    /** Subtypes first, so plain map order defines them before their supertypes. */
    private static Map<String, byte[]> subtypesFirst() {
        Map<String, byte[]> classes = new LinkedHashMap<>();
        classes.put("zbtest.Impl", classBytes("zbtest.Impl", Opcodes.ACC_PUBLIC, "zbtest.Base", "zbtest.Api"));
        classes.put("zbtest.Base", classBytes("zbtest.Base", Opcodes.ACC_PUBLIC | Opcodes.ACC_ABSTRACT, "java.lang.Object"));
        classes.put("zbtest.Api", classBytes("zbtest.Api",
                Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java.lang.Object"));
        return classes;
    }

    @Test
    void ordersSupertypesFirst() {
        List<String> order = new ArrayList<>(ModJarInjector.inDefinitionOrder(subtypesFirst()).keySet());
        assertEquals(3, order.size());
        assertTrue(order.indexOf("zbtest.Base") < order.indexOf("zbtest.Impl"));
        assertTrue(order.indexOf("zbtest.Api") < order.indexOf("zbtest.Impl"));
    }

    @Test
    void orderedInjectionDefinesAll() throws Exception {
        ClassLoader loader = new URLClassLoader(new URL[0], null);
        Map<String, Class<?>> injected =
                new ClassInjector.UsingUnsafe(loader).injectRaw(ModJarInjector.inDefinitionOrder(subtypesFirst()));
        assertEquals(3, injected.size());
        Class<?> impl = loader.loadClass("zbtest.Impl");
        assertEquals("zbtest.Base", impl.getSuperclass().getName());
        assertEquals("zbtest.Api", impl.getInterfaces()[0].getName());
    }

    @Test
    void injectMergedDefinesSubtypeHashedBeforeSupertype() {
        // injectMerged collects the classes in a HashMap, so pick names whose hash order puts the
        // implementation first: unordered, it would be defined before its interface.
        String impl = null;
        String api = null;
        for (int i = 0; impl == null; i++) {
            String a = "zbtest.merged.Api" + i;
            String b = "zbtest.merged.Impl" + i;
            Map<String, Object> probe = new HashMap<>();
            probe.put(a, 0);
            probe.put(b, 0);
            if (probe.keySet().iterator().next().equals(b)) {
                impl = b;
                api = a;
            }
        }
        Map<String, byte[]> classes = new HashMap<>();
        classes.put(api, classBytes(api, Opcodes.ACC_PUBLIC | Opcodes.ACC_INTERFACE | Opcodes.ACC_ABSTRACT, "java.lang.Object"));
        classes.put(impl, classBytes(impl, Opcodes.ACC_PUBLIC, "java.lang.Object", api));
        TransformedJar jar = new TransformedJar(classes, Map.of(), List.of(), null, null);

        ClassLoader loader = ModJarInjector.injectMerged(jar);
        String implName = impl;
        assertDoesNotThrow(() -> Class.forName(implName, false, loader));
    }
}
