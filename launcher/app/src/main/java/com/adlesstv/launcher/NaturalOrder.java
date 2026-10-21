package com.adlesstv.launcher;

import java.text.Collator;

/** Label order that keeps numbered inputs in physical order: "HDMI 2" before "HDMI 10". */
final class NaturalOrder {
    private NaturalOrder() { }

    static int compare(String a, String b, Collator collator) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            boolean leftDigit = Character.isDigit(a.charAt(i)), rightDigit = Character.isDigit(b.charAt(j));
            int iEnd = run(a, i, leftDigit), jEnd = run(b, j, rightDigit);
            int compared;
            if (leftDigit && rightDigit) compared = compareNumbers(a.substring(i, iEnd), b.substring(j, jEnd));
            else compared = collator.compare(a.substring(i, iEnd), b.substring(j, jEnd));
            if (compared != 0) return compared;
            i = iEnd;
            j = jEnd;
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static int run(String text, int from, boolean digits) {
        int end = from;
        while (end < text.length() && Character.isDigit(text.charAt(end)) == digits) end++;
        return end;
    }

    /** Arbitrary length without overflow: strip leading zeros, then compare length and digits. */
    private static int compareNumbers(String a, String b) {
        String left = stripZeros(a), right = stripZeros(b);
        if (left.length() != right.length()) return Integer.compare(left.length(), right.length());
        int compared = left.compareTo(right);
        return compared != 0 ? compared : Integer.compare(a.length(), b.length());
    }

    private static String stripZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') start++;
        return digits.substring(start);
    }
}
