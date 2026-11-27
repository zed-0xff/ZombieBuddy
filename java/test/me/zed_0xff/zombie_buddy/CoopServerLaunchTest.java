package me.zed_0xff.zombie_buddy;

import static org.junit.jupiter.api.Assertions.*;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;

import org.junit.jupiter.api.Test;

import java.util.List;

class CoopServerLaunchTest {
    @Test
    void transformsAndVerifiesInstalledVanillaCoopMasterWithoutStartingIt() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(loader);
        var target = TypePool.Default.of(loader).describe("zombie.network.CoopMaster").resolve();
        byte[] transformed =
                new ByteBuddy()
                        .redefine(target, locator)
                        .visit(CoopServerLaunch.wrapper())
                        .make()
                        .getBytes();
        assertEquals(1, hooks(transformed));
        Class<?> type =
                new ByteBuddy()
                        .redefine(target, ClassFileLocator.Simple.of(target.getName(), transformed))
                        .visit(CoopServerLaunch.wrapper())
                        .make()
                        .load(loader, ClassLoadingStrategy.Default.CHILD_FIRST)
                        .getLoaded();
        assertNotNull(
                type.getDeclaredMethod(
                        "launchServer", String.class, String.class, int.class, boolean.class));
        assertNotNull(type.getDeclaredMethod("getGarbageCollector"));
    }

    @Test
    void secondPassDoesNotDuplicateHook() throws Exception {
        ClassLoader loader = getClass().getClassLoader();
        byte[] first =
                new ByteBuddy()
                        .redefine(Fixture.class)
                        .visit(CoopServerLaunch.wrapper())
                        .make()
                        .getBytes();
        byte[] second =
                new ByteBuddy()
                        .redefine(
                                Fixture.class,
                                ClassFileLocator.Simple.of(Fixture.class.getName(), first))
                        .visit(CoopServerLaunch.wrapper())
                        .make()
                        .getBytes();
        assertEquals(1, hooks(first));
        assertEquals(1, hooks(second));
        var type =
                new ByteBuddy()
                        .redefine(
                                Fixture.class,
                                ClassFileLocator.Simple.of(Fixture.class.getName(), second))
                        .make()
                        .load(loader, ClassLoadingStrategy.Default.CHILD_FIRST)
                        .getLoaded();
        assertNotNull(
                type.getDeclaredMethod(
                        "launchServer", String.class, String.class, int.class, boolean.class));
    }

    @Test
    void refusesChangedConstructorShape() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        new ByteBuddy()
                                .redefine(ChangedFixture.class)
                                .visit(CoopServerLaunch.wrapper())
                                .make());
    }

    static int hooks(byte[] bytes) {
        int[] count = {0};
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(Opcodes.ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    int access,
                                    String name,
                                    String descriptor,
                                    String signature,
                                    String[] exceptions) {
                                return new MethodVisitor(Opcodes.ASM9) {
                                    @Override
                                    public void visitMethodInsn(
                                            int opcode,
                                            String owner,
                                            String method,
                                            String desc,
                                            boolean isInterface) {
                                        if (owner.equals(
                                                        "me/zed_0xff/zombie_buddy/CoopServerJvmArguments")
                                                && method.equals("inherit")) count[0]++;
                                    }
                                };
                            }
                        },
                        0);
        return count[0];
    }

    static class Fixture {
        private void launchServer(String name, String username, int memory, boolean reset) {
            new ProcessBuilder(List.of("java", "ordinary.Main"));
        }

        private String getGarbageCollector() {
            return "-XX:+UseG1GC";
        }
    }

    static class ChangedFixture {
        private void launchServer(String name, String username, int memory, boolean reset) {
            new ProcessBuilder("java", "ordinary.Main");
        }
    }
}
