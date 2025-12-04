package me.zed_0xff.zombie_buddy;

import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class SyntaxSugar {
    public static ElementMatcher<? super TypeDescription> name2matcher(String name) {
        if (name.equals("*")) {
            return ElementMatchers.any();
        }
        if (name.startsWith("*") && name.endsWith("*")) {
            return ElementMatchers.nameContains(name.substring(1, name.length() - 1));
        }
        if (name.startsWith("*")) {
            return ElementMatchers.nameEndsWith(name.substring(1));
        }
        if (name.endsWith("*")) {
            return ElementMatchers.nameStartsWith(name.substring(0, name.length() - 1));
        }
        if (name.startsWith("/") && name.endsWith("/")) { // regexp
            return ElementMatchers.nameMatches(name.substring(1, name.length() - 1));
        }
        return ElementMatchers.named(name);
    }
}
