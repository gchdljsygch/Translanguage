package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.compat.Compat;
import com.pingsu.transpiler.internal.source.SourceText;
import com.pingsu.transpiler.syntax.SourceRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Non-overlapping edits against original UTF-16 offsets, never against already edited text. */
final class SourceEdits {
    private static final class Edit {
        private final SourceRange range;
        private final String replacement;
        private Edit(SourceRange range, String replacement) {
            this.range = range;
            this.replacement = replacement;
        }
        public SourceRange range() { return range; }
        public String replacement() { return replacement; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Edit)) return false;
            Edit value = (Edit) other;
            return java.util.Objects.equals(range, value.range) && java.util.Objects.equals(replacement, value.replacement);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(range);
            hash = 31 * hash + java.util.Objects.hashCode(replacement);
            return hash;
        }
        @Override public String toString() { return "Edit[range=" + range + ", replacement=" + replacement + "]"; }

    }
    private final SourceText source;
    private final List<Edit> edits = new ArrayList<>();
    SourceEdits(String source) { this.source = new SourceText(source); }
    void replace(SourceRange range, String replacement) {
        source.checkRange(range); Objects.requireNonNull(replacement);
        for (Edit edit : edits) {
            SourceRange existing = edit.range();
            boolean interiorInsertion = range.length() == 0 && existing.start() < range.start() && range.start() < existing.end()
                    || existing.length() == 0 && range.start() < existing.start() && existing.start() < range.end();
            if (range.overlaps(existing) || interiorInsertion)
                throw new IllegalStateException("Overlapping transformation edits: " + existing + " and " + range);
        }
        edits.add(new Edit(range, replacement));
    }
    void append(String text) { replace(SourceRange.at(source.length()), text); }
    String render() { return render(new SourceRange(0, source.length())); }
    String render(SourceRange range) {
        source.checkRange(range);
        // Stable sort preserves registration order at a shared insertion offset. An insertion at
        // a replacement's start precedes it; an insertion at its end follows it.
        java.util.ArrayList<com.pingsu.transpiler.SourceEdits.Edit> ordered = new ArrayList<>(edits.stream().filter(e -> range.contains(e.range())).collect(Compat.toList()));
        ordered.sort(Comparator.comparingInt((Edit edit) -> edit.range().start()).thenComparingInt(edit -> edit.range().end()));
        StringBuilder output = new StringBuilder();
        int cursor = range.start();
        for (Edit edit : ordered) {
            output.append(source.text(), cursor, edit.range().start()).append(edit.replacement());
            cursor = edit.range().end();
        }
        return output.append(source.text(), cursor, range.end()).toString();
    }
}
