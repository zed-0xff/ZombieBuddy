package me.zed_0xff.zombie_buddy.patches;

import me.zed_0xff.zombie_buddy.Patch;
import local.zbselective.i18n.UiText;
import zombie.core.Translator;

/** Observe the actual language and rebuild only ZombieBuddy's own Lua-facing UI text. */
public final class Patch_Translator {
    @Patch(className="zombie.core.Translator", methodName="setLanguage")
    public static final class LanguageChanged {
        @Patch.OnExit public static void exit() {
            var current = Translator.getLanguage();
            if (current != null) UiText.languageChanged(current.name());
        }
    }

    @Patch(className="zombie.core.Translator", methodName="loadFiles")
    public static final class TranslationsLoaded {
        @Patch.OnExit public static void exit() { UiText.applyLuaUiTranslations(); }
    }
}
