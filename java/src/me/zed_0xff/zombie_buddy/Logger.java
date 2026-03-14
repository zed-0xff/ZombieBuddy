package me.zed_0xff.zombie_buddy;

import java.io.PrintStream;
import java.util.Arrays;

public class Logger {
    private static final String PREFIX = "[ZB] ";
    public static final int MAX_ARG_STRING_LENGTH = 150;

    // save before PZ overrides them, so we can still log to console even if PZ's logger is messed up
    private static PrintStream out = System.out;
    private static PrintStream err = System.err;

    public static void info(String message) {
        message = PREFIX + message;
        try {
            System.out.println(message);
        } catch (Exception e) { // might fail on game boot
            out.println(message);
        }
    }

    public static void error(String message) {
        message = PREFIX + message;
        try {
            System.err.println(message);
        } catch (Exception e) { // see above
            err.println(message);
        }
    }

    /** Format an object for logging: strings quoted, arrays expanded, length capped. */
    public static String formatArg(Object o) {
        if (o == null) return "null";
        if (o instanceof Object[] arr) return Arrays.toString(arr);
        String s = o.toString();
        if (s.length() > MAX_ARG_STRING_LENGTH) s = s.substring(0, MAX_ARG_STRING_LENGTH - 3) + "...";
        if (o instanceof String) return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return s;
    }

    /** Format an array of arguments for logging (each via {@link #formatArg}, joined by ", "). */
    public static String formatArgs(Object[] args) {
        return formatArgs(args, 0);
    }

    /** Format arguments from {@code fromIndex} to end (no array copy). */
    public static String formatArgs(Object[] args, int fromIndex) {
        if (args == null || fromIndex >= args.length) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = fromIndex; i < args.length; i++) {
            if (i > fromIndex) sb.append(", ");
            sb.append(formatArg(args[i]));
        }
        return sb.toString();
    }
}
