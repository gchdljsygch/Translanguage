package com.pingsu.transpiler.internal.source;

import com.pingsu.transpiler.syntax.SourceRange;
import java.util.Arrays;
import java.util.Objects;

/** Immutable source and line index. Public offsets are UTF-16; columns count code points. */
public final class SourceText {
    public static final class Location {
        private final int line;
        private final int column;
        public Location(int line, int column) {
            this.line = line;
            this.column = column;
        }
        public int line() { return line; }
        public int column() { return column; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Location)) return false;
            Location value = (Location) other;
            return line == value.line && column == value.column;
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + Integer.hashCode(line);
            hash = 31 * hash + Integer.hashCode(column);
            return hash;
        }
        @Override public String toString() { return "Location[line=" + line + ", column=" + column + "]"; }

    }
    private final String text;
    private final int[] lines;
    private final int[] surrogateEnds;
    public SourceText(String text) {
        this.text = Objects.requireNonNull(text);
        int[] starts = new int[32];
        int[] pairs = new int[16];
        int count = 1, pairCount = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                if (pairCount == pairs.length) pairs = Arrays.copyOf(pairs, pairs.length * 2);
                pairs[pairCount++] = i + 2;
            }
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
            if (c == '\r' || c == '\n' || c == '\u2028' || c == '\u2029') {
                if (count == starts.length) starts = Arrays.copyOf(starts, starts.length * 2);
                starts[count++] = i + 1;
            }
        }
        lines = Arrays.copyOf(starts, count);
        surrogateEnds = Arrays.copyOf(pairs, pairCount);
    }
    public String text() { return text; }
    public int length() { return text.length(); }
    public void checkOffset(int offset) {
        if (offset < 0 || offset > text.length()) throw new IllegalArgumentException("Offset outside source: " + offset);
        if (offset > 0 && offset < text.length() && Character.isHighSurrogate(text.charAt(offset - 1))
                && Character.isLowSurrogate(text.charAt(offset)))
            throw new IllegalArgumentException("Offset splits a surrogate pair: " + offset);
    }
    public void checkRange(SourceRange range) { checkOffset(range.start()); checkOffset(range.end()); }
    public String slice(SourceRange range) { checkRange(range); return text.substring(range.start(), range.end()); }
    public Location location(int offset) {
        checkOffset(offset);
        int line = Arrays.binarySearch(lines, offset);
        if (line < 0) line = -line - 2;
        int pairs = pairsBefore(offset) - pairsBefore(lines[line]);
        return new Location(line + 1, offset - lines[line] - pairs + 1);
    }
    private int pairsBefore(int offset) {
        int index = Arrays.binarySearch(surrogateEnds, offset);
        return index < 0 ? -index - 1 : index + 1;
    }
}
