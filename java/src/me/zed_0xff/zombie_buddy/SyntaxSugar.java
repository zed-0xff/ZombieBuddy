package me.zed_0xff.zombie_buddy;

import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

public class SyntaxSugar {
    public static ElementMatcher<? super TypeDescription> typeMatcher(String name) {
        if (name.equals("*")) {
            return ElementMatchers.any();
        }
        return matcherImpl(name);
    }
    
    public static ElementMatcher.Junction<MethodDescription> methodMatcher(String name) {
        if (name.equals("*")) {
            // Use a regex that matches everything to get a Junction<MethodDescription>
            return ElementMatchers.nameMatches(".*");
        }
        return methodMatcherImpl(name);
    }
    
    @SuppressWarnings("unchecked")
    private static <T> ElementMatcher<? super T> matcherImpl(String name) {
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
    
    @SuppressWarnings("unchecked")
    private static ElementMatcher.Junction<MethodDescription> methodMatcherImpl(String name) {
        // Delegate to matcherImpl and cast to Junction
        ElementMatcher<? super MethodDescription> base = matcherImpl(name);
        return (ElementMatcher.Junction<MethodDescription>) base;
    }
}
