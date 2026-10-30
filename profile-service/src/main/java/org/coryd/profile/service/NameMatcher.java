package org.coryd.profile.service;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Compares a name a driver typed against the one on a government record. Exact equality is the
 * wrong test: FRSC returns names uppercased, sometimes with the middle name attached to the first
 * name, and Nigerian names are routinely spelt more than one way ("Oluwatimilehin"/"Oluwatimileyin",
 * "Chidi"/"Chidy"). This scores the two halves separately and takes the weaker of them, so a
 * matching surname cannot carry a first name that is nothing like the record.
 */
public final class NameMatcher {

    private NameMatcher() {
    }

    /** Government records sometimes hold the names the other way round - the better of the two orders. */
    public static double bestOrder(String enteredFirst, String enteredLast, String recordFirst, String recordLast) {
        return Math.max(score(enteredFirst, enteredLast, recordFirst, recordLast), score(enteredFirst, enteredLast, recordLast, recordFirst));
    }

    /** 1.0 is identical after normalisation, 0.0 is nothing in common. */
    public static double score(String enteredFirst, String enteredLast, String recordFirst, String recordLast) {
        double first = similarity(firstToken(enteredFirst), firstToken(recordFirst));
        double last = similarity(firstToken(enteredLast), firstToken(recordLast));
        return Math.min(first, last);
    }

    /**
     * At least one name in common - any of the entered names against any on the record, middle names included,
     * each spelt within threshold. Lets a married or reordered name through that score() rightly calls different.
     * Initials don't count.
     */
    public static boolean sharesAName(List<String> entered, List<String> record, double threshold) {
        List<String> ours = tokens(entered);
        List<String> theirs = tokens(record);
        for (String a : ours) {
            for (String b : theirs) {
                if (similarity(a, b) >= threshold) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> tokens(List<String> names) {
        return names.stream()
                .filter(Objects::nonNull)
                .flatMap(n -> Arrays.stream(normalize(n).split(" ")))
                .filter(t -> t.length() > 1)
                .distinct()
                .toList();
    }

    /** FRSC often puts "JOHN OLUWASEUN" in the first-name field; only the given name is comparable. */
    private static String firstToken(String value) {
        String normalized = normalize(value);
        int space = normalized.indexOf(' ');
        return space < 0 ? normalized : normalized.substring(0, space);
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String decomposed = Normalizer.normalize(value.trim(), Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return decomposed.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").replaceAll(" +", " ").trim();
    }

    /**
     * Jaro-Winkler. Chosen over edit distance because it weights a shared prefix, which is exactly
     * how Nigerian spelling variants differ - they agree at the start and drift at the end.
     */
    private static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return a.equals(b) ? 1.0 : 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }

        int matchWindow = Math.max(a.length(), b.length()) / 2 - 1;
        if (matchWindow < 0) {
            matchWindow = 0;
        }

        boolean[] aMatched = new boolean[a.length()];
        boolean[] bMatched = new boolean[b.length()];
        int matches = 0;
        for (int i = 0; i < a.length(); i++) {
            int from = Math.max(0, i - matchWindow);
            int to = Math.min(b.length(), i + matchWindow + 1);
            for (int j = from; j < to; j++) {
                if (bMatched[j] || a.charAt(i) != b.charAt(j)) {
                    continue;
                }
                aMatched[i] = true;
                bMatched[j] = true;
                matches++;
                break;
            }
        }
        if (matches == 0) {
            return 0.0;
        }

        int transpositions = 0;
        int k = 0;
        for (int i = 0; i < a.length(); i++) {
            if (!aMatched[i]) {
                continue;
            }
            while (!bMatched[k]) {
                k++;
            }
            if (a.charAt(i) != b.charAt(k)) {
                transpositions++;
            }
            k++;
        }

        double m = matches;
        double jaro = (m / a.length() + m / b.length() + (m - transpositions / 2.0) / m) / 3.0;

        int prefix = 0;
        while (prefix < Math.min(4, Math.min(a.length(), b.length())) && a.charAt(prefix) == b.charAt(prefix)) {
            prefix++;
        }
        return jaro + prefix * 0.1 * (1 - jaro);
    }
}
