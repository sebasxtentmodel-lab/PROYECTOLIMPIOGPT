package com.smartfk.keyboard;

import java.util.Locale;

/**
 * Keeps only newly confirmed translated words when overlapping ASR windows
 * repeat the end of the previous transcript. Never removes confirmed words.
 * Repeated 1-2 word responses are preserved as potentially intentional.
 */
final class RollingText {
    private RollingText() {}

    static String newTail(CharSequence confirmed, String translated) {
        if (translated == null) return "";
        String incoming = translated.trim().replaceAll("\\s+", " ");
        if (incoming.isEmpty()) return "";
        if (confirmed == null || confirmed.length() == 0) return incoming;
        String previous = confirmed.toString().trim();
        if (previous.isEmpty()) return incoming;

        String[] prior = previous.split("\\s+");
        String[] next = incoming.split("\\s+");
        int overlap = 0;
        for (int count = Math.min(prior.length, next.length); count >= 3; --count) {
            boolean matches = true;
            for (int i=0; i<count; ++i) {
                if (!word(prior[prior.length-count+i]).equals(word(next[i]))) {
                    matches = false; break;
                }
            }
            if (matches) { overlap=count; break; }
        }
        if (overlap == next.length) return "";
        if (overlap == 0) return incoming;
        StringBuilder result=new StringBuilder();
        for (int i=overlap;i<next.length;i++) {
            if(result.length()>0)result.append(' ');
            result.append(next[i]);
        }
        return result.toString();
    }
    private static String word(String s) {
        return s.replaceAll("^[\\p{P}\\p{S}]+|[\\p{P}\\p{S}]+$", "")
                .toLowerCase(Locale.ROOT);
    }
}
