package com.pingsu.transpiler.syntax;

/** A UTF-16 half-open range in BOM-normalized input: [start, end). */
public final class SourceRange {
        private final int start;
        private final int end;
        public SourceRange(int start, int end) {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source range: " + start + ".." + end);

            this.start = start;
            this.end = end;
        }
        public int start() { return start; }
        public int end() { return end; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof SourceRange)) return false;
            SourceRange value = (SourceRange) other;
            return start == value.start && end == value.end;
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + Integer.hashCode(start);
            hash = 31 * hash + Integer.hashCode(end);
            return hash;
        }
        @Override public String toString() { return "SourceRange[start=" + start + ", end=" + end + "]"; }


    public int length() { return end - start; }
    public boolean contains(SourceRange other) { return start <= other.start && end >= other.end; }
    public boolean overlaps(SourceRange other) { return length() > 0 && other.length() > 0 && start < other.end && other.start < end; }
    public static SourceRange at(int offset) { return new SourceRange(offset, offset); }

    }
