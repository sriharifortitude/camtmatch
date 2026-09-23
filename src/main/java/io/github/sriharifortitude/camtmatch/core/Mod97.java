package io.github.sriharifortitude.camtmatch.core;

/**
 * ISO 7064 MOD 97-10, the checksum shared by IBAN (ISO 13616) and the
 * creditor reference (ISO 11649). Letters count as two digits (A=10 .. Z=35).
 * Computed piecewise so arbitrarily long inputs never need a BigInteger.
 */
final class Mod97 {
    private Mod97() {}

    static int remainder(CharSequence s) {
        int r = 0;
        for (int i = 0; i < s.length(); i++) {
            int v = Character.digit(s.charAt(i), 36);
            if (v < 0) throw new IllegalArgumentException("not alphanumeric: " + s.charAt(i));
            r = v < 10 ? (r * 10 + v) % 97 : (r * 100 + v) % 97;
        }
        return r;
    }

    /** True when {@code value} (check digits at positions 2-3) satisfies MOD 97-10. */
    static boolean valid(String value) {
        return remainder(value.substring(4) + value.substring(0, 4)) == 1;
    }
}
