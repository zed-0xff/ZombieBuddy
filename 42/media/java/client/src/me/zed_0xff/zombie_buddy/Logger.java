package me.zed_0xff.zombie_buddy;

import java.io.PrintStream;

public class Logger {
    // capture the original System.out and System.err, so game's redirection won't affect us
    // private static final PrintStream _out = System.out;    
    // private static final PrintStream _err = System.err;

    private static final String PREFIX = "[ZB] ";

    public static void info(String message) {
        System.out.println(PREFIX + message);
    }

    public static void error(String message) {
        System.err.println(PREFIX + message);
    }
}
