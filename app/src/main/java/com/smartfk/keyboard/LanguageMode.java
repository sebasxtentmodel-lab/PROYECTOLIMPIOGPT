package com.smartfk.keyboard;

/** Only Spanish and Turkish can be selected; auto is the default. */
final class LanguageMode {
    private LanguageMode() {}
    static String normalized(String configured) {
        if ("es".equals(configured) || "tr".equals(configured)) return configured;
        return "auto";
    }
    static boolean useTurkishSpecialist(String configured) {
        return "tr".equals(normalized(configured));
    }
}
