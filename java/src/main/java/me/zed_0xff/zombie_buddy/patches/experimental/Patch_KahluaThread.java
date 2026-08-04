package me.zed_0xff.zombie_buddy.patches.experimental;

import me.zed_0xff.zombie_buddy.annotations.Patch;
import me.zed_0xff.zombie_buddy.Utils;

import java.util.Arrays;
import java.util.HashSet;

import se.krka.kahlua.vm.LuaCallFrame;
import se.krka.kahlua.vm.LuaClosure;
import se.krka.kahlua.vm.Prototype;
import zombie.Lua.LuaManager;
import zombie.core.Core;
import zombie.ui.UIManager;

public class Patch_KahluaThread {
    // see zbUtils class
    public static final HashSet<String> METHODS = new HashSet<>(Arrays.asList(
        "zbinspect",
        "zbmethod",
        "zbmethods",
        "zbfields",

        "zbget",
        "zbset",

        "zbcall",

        "zbkeys",
        "zbvalues",
        "zbgrep",
        "zbmap"
    ));

    /**
     * When key is "zbInspect" and table is a Java object (not a KahluaTable), return the global
     * zbInspect function so obj.zbInspect is the function and obj:zbInspect() works.
     */
    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "tableget")
    public static class Patch_tableget {
        @Patch.OnEnter(skipOn = true)
        public static boolean enter(Object table, Object key) {
            return METHODS.contains(key);
        }

        @Patch.OnExit
        public static void exit( Object table, Object key, @Patch.Return(readOnly = false) Object returnValue) {
            if (key instanceof String keyStr) {
                if (METHODS.contains(keyStr)) {
                    returnValue = LuaManager.getFunctionObject(keyStr);
                }
            }
        }
    }

    /**
     * Richer nil-call errors: (1) append global name for {@code Object tried to call nil in ...}; (2) vanilla TAILCALL swallows
     * {@code fail("Tried to call nil")} and calls {@code fail("")} — we recover GETGLOBAL/GETTABLE/SELF before CALL/TAILCALL when msg is blank.
     */
    @Patch(className = "se.krka.kahlua.vm.KahluaUtil", methodName = "fail", strictMatch = true)
    public static class Patch_fail_nilCalleeHint {
        public static final int OP_MOVE = 0;
        public static final int OP_GETGLOBAL = 5;
        public static final int OP_GETTABLE = 6;
        public static final int OP_SELF = 11;
        public static final int OP_CALL = 28;
        public static final int OP_TAILCALL = 29;
        public static final String NIL_CALL_PREFIX = "Object tried to call nil in ";

        @Patch.OnEnter
        public static void enter(@Patch.Argument(value=0, readOnly=false) String msg) {
            String detail = nilCalleeDetailAfterCall();
            if (detail == null) {
                return;
            }

            if (msg != null && msg.startsWith(NIL_CALL_PREFIX)) {
                msg = msg + " [" + detail + "]";
            } else if (Utils.isBlank(msg)) {
                msg = "Object tried to call nil (" + detail + ")";
            }
        }

        /**
         * {@code pc} is past CALL/TAILCALL; {@code code[pc-1]} is the call instruction. Returns e.g. {@code nil global 'Foo'},
         * {@code nil field 'foo'} ( {@code t.foo} ), or {@code nil method 'foo'} ( {@code t:foo} / SELF).
         */
        public static String nilCalleeDetailAfterCall() {
            try {
                if (LuaManager.thread == null) {
                    return null;
                }
                LuaCallFrame cf = LuaManager.thread.getCurrentCoroutine().currentCallFrame();
                LuaClosure cl = cf == null ? null : cf.closure;
                Prototype p = cl == null ? null : cl.prototype;
                int[] code = p == null ? null : p.code;
                if (code == null || cf.pc < 2) {
                    return null;
                }
                int callOp = code[cf.pc - 1];
                int op = callOp & 63;
                if (op != OP_CALL && op != OP_TAILCALL) {
                    return null;
                }
                int funReg = (callOp >>> 6) & 255;
                int loadPc = cf.pc - 2;
                int loadOp = code[loadPc];
                if ((loadOp & 63) == OP_MOVE) {
                    if (((loadOp >>> 6) & 255) != funReg) {
                        return null;
                    }
                    funReg = (loadOp >>> 23) & 511;
                    loadPc--;
                    if (loadPc < 0) {
                        return null;
                    }
                    loadOp = code[loadPc];
                }
                int opc = loadOp & 63;
                int destA = (loadOp >>> 6) & 255;
                if (destA != funReg) {
                    return null;
                }
                if (opc == OP_GETGLOBAL) {
                    int bx = loadOp >>> 14;
                    Object[] c = p.constants;
                    if (c == null || bx < 0 || bx >= c.length) {
                        return null;
                    }
                    return "nil global " + formatConst(c[bx]);
                }
                if (opc == OP_GETTABLE) {
                    Object key = rk(cf, p, (loadOp >>> 14) & 511);
                    return "nil field " + formatConst(key);
                }
                if (opc == OP_SELF) {
                    Object key = rk(cf, p, (loadOp >>> 14) & 511);
                    return "nil method " + formatConst(key);
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }

        public static Object rk(LuaCallFrame cf, Prototype p, int index) {
            int ck = index - 256;
            if (ck < 0) {
                return cf.get(index);
            }
            Object[] c = p.constants;
            if (c == null || ck < 0 || ck >= c.length) {
                return null;
            }
            return c[ck];
        }

        public static String formatConst(Object o) {
            if (o instanceof String s) {
                return "'" + s + "'";
            }
            return String.valueOf(o);
        }
    }

    /**
     * When a Kahlua stdlib function (e.g. table.insert/remove) throws NullPointerException,
     * inspects the Lua call frame to identify what the nil first argument was (global/field name)
     * and replaces the raw NPE with a message in the same style as the nil-callee hint,
     * e.g. {@code TableLib.insert: nil global 'myTable'}.
     */
    @Patch(className = "se.krka.kahlua.vm.KahluaThread", methodName = "callJava")
    public static class Patch_callJava_npeHint {
        private static final int OP_GETGLOBAL = 5;
        private static final int OP_GETTABLE  = 6;
        private static final int OP_CALL      = 28;
        private static final int OP_TAILCALL  = 29;

        @Patch.OnExit(onThrowable = NullPointerException.class)
        public static void exit(@Patch.Thrown(readOnly = false) Throwable thrown) {
            if (!(thrown instanceof NullPointerException npe)) return;
            StackTraceElement[] st = npe.getStackTrace();
            if (st == null || st.length == 0) return;
            if (!st[0].getClassName().startsWith("se.krka.kahlua.stdlib.")) return;
            String method = st[0].getClassName().replaceFirst(".*\\.", "") + "." + st[0].getMethodName();
            String argDetail = nilFirstArgDetail();
            thrown = new RuntimeException(method + ": " + (argDetail != null ? argDetail : "nil first argument"), npe);
        }

        public static String nilFirstArgDetail() {
            try {
                if (LuaManager.thread == null) return null;
                LuaCallFrame cf = LuaManager.thread.getCurrentCoroutine().currentCallFrame();
                LuaClosure cl = cf == null ? null : cf.closure;
                Prototype p = cl == null ? null : cl.prototype;
                int[] code = p == null ? null : p.code;
                if (code == null || cf.pc < 2) return null;
                int callOp = code[cf.pc - 1];
                int callOpcode = callOp & 63;
                if (callOpcode != OP_CALL && callOpcode != OP_TAILCALL) return null;
                int argReg = ((callOp >>> 6) & 255) + 1;  // funReg + 1 = first argument register
                for (int pc = cf.pc - 2; pc >= 0 && pc >= cf.pc - 8; pc--) {
                    int instr = code[pc];
                    if (((instr >>> 6) & 255) != argReg) continue;
                    int opc = instr & 63;
                    if (opc == OP_GETGLOBAL) {
                        int bx = instr >>> 14;
                        Object[] c = p.constants;
                        if (c != null && bx >= 0 && bx < c.length) return "nil global " + Patch_fail_nilCalleeHint.formatConst(c[bx]);
                    }
                    if (opc == OP_GETTABLE) {
                        Object key = Patch_fail_nilCalleeHint.rk(cf, p, (instr >>> 14) & 511);
                        return "nil field " + Patch_fail_nilCalleeHint.formatConst(key);
                    }
                    break;
                }
                return null;
            } catch (Throwable ignored) {
                return null;
            }
        }
    }

    @Patch(className = "se.krka.kahlua.vm.Coroutine", methodName = "getStackTrace")
    public static class Patch_Coroutine_getStackTrace {
        @Patch.OnExit(onThrowable = NullPointerException.class)
        public static void exit(@Patch.Argument(0) LuaCallFrame frame, @Patch.Return(readOnly = false) String returnValue, @Patch.Thrown(readOnly = false) Throwable thrown) {
            if (thrown == null || frame == null || frame.closure != null) {
                return;
            }

            if (frame.javaFunction != null) {
                returnValue = "at " + frame.javaFunction + "\n";
            } else {
                returnValue = "";
            }
            thrown = null;
        }
    }
}
