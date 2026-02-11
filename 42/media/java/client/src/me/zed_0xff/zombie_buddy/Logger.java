package me.zed_0xff.zombie_buddy;

import java.io.PrintStream;

public class Logger {
    // capture the original System.out and System.err, so game's redirection won't affect us
    public static final PrintStream out = System.out;    
    public static final PrintStream err = System.err;
}
