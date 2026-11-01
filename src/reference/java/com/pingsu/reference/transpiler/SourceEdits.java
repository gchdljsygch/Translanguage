package com.pingsu.reference.transpiler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.antlr.v4.runtime.Token;

/** Applies non-overlapping edits against original ANTLR code-point offsets. */
final class SourceEdits {
    private record Edit(int start, int end, String replacement) { }
    private final String source;
    private final int[] offsets;
    private final List<Edit> edits = new ArrayList<>();

    SourceEdits(String source) {
        this.source = source;
        offsets = new int[source.codePointCount(0, source.length()) + 1];
        int charOffset = 0;
        for (int i = 0; i < offsets.length - 1; i++) {
            offsets[i] = charOffset;
            charOffset += Character.charCount(source.codePointAt(charOffset));
        }
        offsets[offsets.length - 1] = source.length();
    }

    void replace(Token first, Token last, String replacement) {
        edits.add(new Edit(offsets[first.getStartIndex()], offsets[last.getStopIndex() + 1], replacement));
    }

    void insertAfter(Token token, String text) {
        int position = offsets[token.getStopIndex() + 1];
        edits.add(new Edit(position, position, text));
    }

    void append(String text) { edits.add(new Edit(source.length(), source.length(), text)); }

    String render() {
        edits.sort(Comparator.comparingInt(Edit::start).thenComparingInt(Edit::end));
        StringBuilder output = new StringBuilder();
        int cursor = 0;
        for (Edit edit : edits) {
            if (edit.start < cursor) throw new IllegalStateException("Overlapping transformation edits");
            output.append(source, cursor, edit.start).append(edit.replacement);
            cursor = edit.end;
        }
        return output.append(source, cursor, source.length()).toString();
    }
}
