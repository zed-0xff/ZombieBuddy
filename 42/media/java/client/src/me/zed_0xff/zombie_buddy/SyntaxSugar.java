package me.zed_0xff.zombie_buddy;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class SyntaxSugar {
    public static ElementMatcher<? super TypeDescription> typeMatcher(String name) {
        return matcherImpl(name);
    }
    
    public static ElementMatcher<? super MethodDescription> methodMatcher(String name) {
        return matcherImpl(name);
    }
    
    @SuppressWarnings("unchecked")
    private static <T> ElementMatcher<? super T> matcherImpl(String name) {
        if (name.equals("*")) {
            return (ElementMatcher<? super T>) ElementMatchers.any();
        }
        if (name.startsWith("*") && name.endsWith("*")) {
            return (ElementMatcher<? super T>) ElementMatchers.nameContains(name.substring(1, name.length() - 1));
        }
        if (name.startsWith("*")) {
            return (ElementMatcher<? super T>) ElementMatchers.nameEndsWith(name.substring(1));
        }
        if (name.endsWith("*")) {
            return (ElementMatcher<? super T>) ElementMatchers.nameStartsWith(name.substring(0, name.length() - 1));
        }
        if (name.startsWith("/") && name.endsWith("/")) { // regexp
            return (ElementMatcher<? super T>) ElementMatchers.nameMatches(name.substring(1, name.length() - 1));
        }
        return (ElementMatcher<? super T>) ElementMatchers.named(name);
    }
}
