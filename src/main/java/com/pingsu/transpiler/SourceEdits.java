package com.pingsu.transpiler;

import com.pingsu.transpiler.internal.source.SourceText;
import com.pingsu.transpiler.syntax.SourceRange;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Non-overlapping edits against original UTF-16 offsets, never against already edited text. */
final class SourceEdits {
    private record Edit(SourceRange range, String replacement) { }
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
    String render() {
        // Stable sort preserves registration order at a shared insertion offset. An insertion at
        // a replacement's start precedes it; an insertion at its end follows it.
        var ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Edit edit) -> edit.range().start()).thenComparingInt(edit -> edit.range().end()));
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        for (Edit edit : ordered) {
            output.append(source.text(), cursor, edit.range().start()).append(edit.replacement());
            cursor = edit.range().end();
        }
        return output.append(source.text(), cursor, source.length()).toString();
    }
}
