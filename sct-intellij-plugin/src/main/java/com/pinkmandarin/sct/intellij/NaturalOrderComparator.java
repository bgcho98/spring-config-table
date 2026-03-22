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
                // Compare numeric chunks as long to avoid overflow
                long numA = 0, numB = 0;
                int lenA = 0, lenB = 0;
                while (ai < a.length() && Character.isDigit(a.charAt(ai))) {
                    numA = numA * 10 + (a.charAt(ai) - '0');
                    ai++; lenA++;
                    if (lenA > 18) { // overflow guard: fall back to length comparison
                        while (ai < a.length() && Character.isDigit(a.charAt(ai))) { ai++; lenA++; }
                        break;
                    }
                }
                while (bi < b.length() && Character.isDigit(b.charAt(bi))) {
                    numB = numB * 10 + (b.charAt(bi) - '0');
                    bi++; lenB++;
                    if (lenB > 18) {
                        while (bi < b.length() && Character.isDigit(b.charAt(bi))) { bi++; lenB++; }
                        break;
                    }
                }
                if (lenA > 18 || lenB > 18) {
                    if (lenA != lenB) return Integer.compare(lenA, lenB);
                    // Same length: compare digit chars lexicographically
                    for (int j = 0; j < lenA; j++) {
                        int cmp = Character.compare(a.charAt(ai - lenA + j), b.charAt(bi - lenB + j));
                        if (cmp != 0) return cmp;
                    }
                } else {
                    if (numA != numB) return Long.compare(numA, numB);
                }
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
