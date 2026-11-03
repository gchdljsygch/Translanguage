package com.pingsu.transpiler.internal.js;

/** Captured JS Reference: evaluation setup, read/write target and call receiver. */
public final class ExpressionReference {
        private final String setup;
        private final String target;
        private final String receiver;
        public ExpressionReference(String setup, String target, String receiver) {
            this.setup = setup;
            this.target = target;
            this.receiver = receiver;
        }
        public String setup() { return setup; }
        public String target() { return target; }
        public String receiver() { return receiver; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ExpressionReference)) return false;
            ExpressionReference value = (ExpressionReference) other;
            return java.util.Objects.equals(setup, value.setup) && java.util.Objects.equals(target, value.target) && java.util.Objects.equals(receiver, value.receiver);
        }
        @Override public int hashCode() {
            int hash = 0;
            hash = 31 * hash + java.util.Objects.hashCode(setup);
            hash = 31 * hash + java.util.Objects.hashCode(target);
            hash = 31 * hash + java.util.Objects.hashCode(receiver);
            return hash;
        }
        @Override public String toString() { return "ExpressionReference[setup=" + setup + ", target=" + target + ", receiver=" + receiver + "]"; }

    public String read() { return target; }
    public String write(String value) { return target + "=(" + value + ")"; }

    }
