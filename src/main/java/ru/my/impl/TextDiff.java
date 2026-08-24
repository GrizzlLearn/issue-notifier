package ru.my.impl;

import java.util.ArrayList;
import java.util.List;

/**
 * Построчный diff двух строк на основе LCS.
 * Статический утилитный класс, не является Spring-бином.
 */
public final class TextDiff {

    private TextDiff() {}

    public record Line(char marker, String text) {}

    /**
     * Возвращает список строк с маркерами: ' ' — контекст, '+' — добавлено, '-' — удалено.
     * null трактуется как пустая строка.
     */
    public static List<Line> diff(String from, String to) {
        String[] a = splitLines(from);
        String[] b = splitLines(to);
        int m = a.length, n = b.length;

        // LCS[i][j] = длина LCS для a[i..m-1] и b[j..n-1]
        int[][] lcs = new int[m + 1][n + 1];
        for (int i = m - 1; i >= 0; i--) {
            for (int j = n - 1; j >= 0; j--) {
                lcs[i][j] = a[i].equals(b[j])
                        ? 1 + lcs[i + 1][j + 1]
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Line> result = new ArrayList<>();
        int i = 0, j = 0;
        while (i < m || j < n) {
            if (i < m && j < n && a[i].equals(b[j])) {
                result.add(new Line(' ', a[i]));
                i++; j++;
            } else if (i < m && (j >= n || lcs[i + 1][j] >= lcs[i][j + 1])) {
                result.add(new Line('-', a[i++]));
            } else {
                result.add(new Line('+', b[j++]));
            }
        }
        return result;
    }

    private static String[] splitLines(String s) {
        if (s == null || s.isEmpty()) return new String[0];
        return s.split("\n", -1);
    }
}
