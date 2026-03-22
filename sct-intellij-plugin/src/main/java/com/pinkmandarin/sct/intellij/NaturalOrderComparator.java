package com.pinkmandarin.sct.intellij;

import java.util.Comparator;

/**
 * Natural order string comparator: "item2" < "item10" (not lexicographic).
 * Splits strings into alphabetic and numeric chunks, comparing numeric chunks as numbers.
 */
final class NaturalOrderComparator implements Comparator<String> {

    static final NaturalOrderComparator INSTANCE = new NaturalOrderComparator();

    private NaturalOrderComparator() {}

    @Override
    public int compare(String a, String b) {
        int ai = 0, bi = 0;
        while (ai < a.length() && bi < b.length()) {
            var ca = a.charAt(ai);
            var cb = b.charAt(bi);

            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                // Compare numeric chunks
                int numA = 0, numB = 0;
                while (ai < a.length() && Character.isDigit(a.charAt(ai))) {
                    numA = numA * 10 + (a.charAt(ai) - '0');
                    ai++;
                }
                while (bi < b.length() && Character.isDigit(b.charAt(bi))) {
                    numB = numB * 10 + (b.charAt(bi) - '0');
                    bi++;
                }
                if (numA != numB) return Integer.compare(numA, numB);
            } else {
                var cmp = Character.compare(Character.toLowerCase(ca), Character.toLowerCase(cb));
                if (cmp != 0) return cmp;
                ai++;
                bi++;
            }
        }
        return Integer.compare(a.length(), b.length());
    }
}
