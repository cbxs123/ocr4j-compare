package com.compare.ocr.benchmark;

public class AccuracyEvaluator {

    public record EvalResult(
            String engineName,
            String sampleName,
            int lineCount,
            int charCount,
            String recognizedText,
            double similarityWithReference
    ) {
    }

    /**
     * Compute Levenshtein similarity ratio between two strings (0.0 to 1.0).
     */
    public static double computeSimilarity(String s1, String s2) {
        if (s1 == null || s2 == null) return 0.0;
        String clean1 = s1.replaceAll("\\s+", "");
        String clean2 = s2.replaceAll("\\s+", "");
        if (clean1.isEmpty() && clean2.isEmpty()) return 1.0;
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0;

        int distance = computeLevenshteinDistance(clean1, clean2);
        int maxLen = Math.max(clean1.length(), clean2.length());
        return 1.0 - ((double) distance / maxLen);
    }

    public static int computeLevenshteinDistance(String str1, String str2) {
        int len1 = str1.length();
        int len2 = str2.length();
        int[][] dp = new int[len1 + 1][len2 + 1];

        for (int i = 0; i <= len1; i++) dp[i][0] = i;
        for (int j = 0; j <= len2; j++) dp[0][j] = j;

        for (int i = 1; i <= len1; i++) {
            for (int j = 1; j <= len2; j++) {
                int cost = (str1.charAt(i - 1) == str2.charAt(j - 1)) ? 0 : 1;
                dp[i][j] = Math.min(
                        Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1),
                        dp[i - 1][j - 1] + cost
                );
            }
        }
        return dp[len1][len2];
    }
}
