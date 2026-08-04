package me.zed_0xff.zombie_buddy.transformers;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.ElementType;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import me.zed_0xff.zombie_buddy.annotations.Shadow;
import me.zed_0xff.zombie_buddy.transformers.asmtree.Resolver;
import me.zed_0xff.zombie_buddy.transformers.asmtree.ShadowRewrite;
import me.zed_0xff.zombie_buddy.transformers.bytebuddy.Unshadow;
import net.bytebuddy.dynamic.loading.ByteArrayClassLoader;

class Shadow_Test extends AbstractTest {
    @Retention(RetentionPolicy.RUNTIME)
    @java.lang.annotation.Target(ElementType.TYPE)
    private @interface TestCase {
        String field() default "";
        String methodName() default "";
        int expected() default 69;
        boolean needResolve() default false;
        String fieldName() default "x";
        Class<?> patchClass() default void.class;
    }

    private static final String TARGET = "testjar.ShadowTarget";

    @TestCase(field = "privateField", fieldName = "privateField", patchClass = Patch1.class)
    @Shadow(className = TARGET)
    static class Shadow1 {
        @Shadow.Field public int privateField;
    }
    static class Patch1 {
        static int getResult() { return new Shadow1().privateField; }
    }

    @TestCase(field = "privateField", patchClass = Patch2.class)
    @Shadow(className = TARGET)
    static class Shadow2 {
        @Shadow.Field("privateField") int x;
    }
    static class Patch2 {
        static int getResult() { return new Shadow2().x; }
    }

    @TestCase(field = "privateField", patchClass = Patch3.class)
    @Shadow(className = TARGET)
    static class Shadow3 {
        @Shadow.Field({"privateField", "xx"}) int x;
    }
    static class Patch3 {
        static int getResult() { return new Shadow3().x; }
    }

    @TestCase(field = "field2", patchClass = Patch4.class)
    @Shadow(className = TARGET)
    static class Shadow4 {
        @Shadow.Field({"xx", "privateField"}) int x;
    }
    static class Patch4 {
        static int getResult() { return new Shadow4().x; }
    }

    @TestCase(field = "field2", fieldName = "privateField")
    @Shadow(className = TARGET)
    static class TrickyShadow1 {
        @Shadow.Field("field2") int privateField;
    }

    @TestCase(field = "", fieldName = "xxfield")
    @Shadow(className = TARGET)
    static class BadShadow1 {
        @Shadow.Field int xxfield;
    }

    @TestCase(field = "", fieldName = "privateField")
    @Shadow(className = TARGET)
    static class BadShadow2 {
        @Shadow.Field("xx") int privateField;
    }

    @TestCase(methodName = "privateMethod", expected = 42, patchClass = PatchMethod1.class)
    @Shadow(className = TARGET)
    static class ShadowMethod1 {
        @Shadow.Method
        int privateMethod() { return 0; }
    }
    static class PatchMethod1 {
        static int getResult() { return new ShadowMethod1().privateMethod(); }
    }

    @TestCase(methodName = "call", expected = 42, patchClass = PatchMethod2.class)
    @Shadow(className = TARGET)
    static class ShadowMethod2 {
        @Shadow.Method("privateMethod")
        int call() { return 0; }
    }
    static class PatchMethod2 {
        static int getResult() { return new ShadowMethod2().call(); }
    }

    @TestCase(field = "privateField", fieldName = "privateField", patchClass = PatchCast.class)
    @Shadow(className = TARGET)
    static class ShadowCast {
        @Shadow.Field int privateField;

        @Shadow.Cast
        static ShadowCast cast(Object o) { return (ShadowCast) o; }
    }
    static class PatchCast {
        static int getResult() {
            Object o = new ShadowCast();
            ShadowCast shadow = ShadowCast.cast(o);
            return shadow.privateField;
        }
    }

    protected static Stream<Arguments> provideClasses() {
        return Stream.of(Shadow_Test.class.getDeclaredClasses())
            .filter(c -> c.isAnnotationPresent(TestCase.class))
            .map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("provideClasses")
    void test(Class<?> cls) throws Exception {
        TestCase tc = cls.getAnnotation(TestCase.class);
        var ctx = new TestClassContext(cls);
        byte[] bytes = ctx.getBytes();

        if (!tc.methodName().isEmpty()) {
            testMethodShadow(tc, cls, ctx, bytes);
            return;
        }

        var run = runTransformer(ctx, bytes, Resolver.class);
        try {
            if (tc.needResolve()) {
                assertTransformed(run);
                assertThat(run.bytes()).isNotNull();
                bytes = run.bytes();
            } else {
                assertThat(run.modified()).isFalse();
            }

            var field = ctx.getField(tc.fieldName());
            assertThat(field.getDeclaredAnnotations()).hasSize(1);

            var shadowAnn = field.getDeclaredAnnotations().ofType(Shadow.Field.class);
            assertThat(shadowAnn).isNotNull();

            if (tc.patchClass() != void.class) {
                checkPatch(tc, ctx.jarContext(), cls);
            }
        } catch (Throwable t) {
            printDumps(run.dumps());
            throw t;
        }
    }

    private void testMethodShadow(TestCase tc, Class<?> cls, TestClassContext ctx, byte[] bytes) throws Exception {
        var method = ctx.getMethod(tc.methodName());
        assertThat(method).isNotNull();
        assertThat(method.getDeclaredAnnotations().ofType(Shadow.Method.class)).isNotNull();

        if (tc.patchClass() != void.class) {
            checkPatch(tc, ctx.jarContext(), cls);
        }
    }

    private void checkPatch(TestCase tc, JarContext jctx, Class<?> shadowCls) throws Exception {
        Unshadow.collectShadowDescriptorMappings(jctx, List.of(shadowCls.getName()));

        var ctx = new TestClassContext(tc.patchClass(), jctx);
        var bytes = ctx.getBytes();
        var run = runTransformers(ctx, bytes, List.of(Unshadow.class, ShadowRewrite.class));
        if ("".equals(tc.field())) {
            // TODO
        } else {
            assertTransformed(run);
            assertThat(run.bytes()).isNotNull();
            assertThat(invokeGetResult(tc.patchClass().getName(), run.bytes())).isEqualTo(tc.expected());
        }
    }

    private static int invokeGetResult(String patchClassName, byte[] patchBytes) throws Exception {
        byte[] targetBytes = getClassBytes(Class.forName(TARGET));
        ClassLoader parent = Shadow_Test.class.getClassLoader();
        ClassLoader isolated = new ByteArrayClassLoader.ChildFirst(
            parent,
            Map.of(TARGET, targetBytes, patchClassName, patchBytes),
            ByteArrayClassLoader.PersistenceHandler.MANIFEST
        );
        Class<?> patchClass = isolated.loadClass(patchClassName);
        var getResult = patchClass.getDeclaredMethod("getResult");
        getResult.setAccessible(true);

        return (int) getResult.invoke(null);
    }
}
