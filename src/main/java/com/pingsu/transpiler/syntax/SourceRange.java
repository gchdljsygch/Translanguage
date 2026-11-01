package com.pingsu.transpiler.syntax;

/** A UTF-16 half-open range in BOM-normalized input: [start, end). */
public record SourceRange(int start, int end) {
    public SourceRange {
        if (start < 0 || end < start) throw new IllegalArgumentException("Invalid source range: " + start + ".." + end);
    }
    public int length() { return end - start; }
    public boolean contains(SourceRange other) { return start <= other.start && end >= other.end; }
    public boolean overlaps(SourceRange other) { return length() > 0 && other.length() > 0 && start < other.end && other.start < end; }
    public static SourceRange at(int offset) { return new SourceRange(offset, offset); }
}
