package com.pingsu.antlr;

import org.antlr.v4.runtime.*;

/**
 * All parser methods that used in grammar (p, prev, notLineTerminator, etc.)
 * should start with lower case char similar to parser rules.
 */
public abstract class TypeScriptParserBase extends Parser
{
    public TypeScriptParserBase(TokenStream input) {
        super(input);
    }

    /**
     * Short form for prev(String str)
     */
    protected boolean p(String str) {
        return prev(str);
    }

    /**
     * Whether the previous token value equals to @param str
     */
    protected boolean prev(String str) {
        return _input.LT(-1).getText().equals(str);
    }

    /**
     * Short form for next(String str)
     */
    protected boolean n(String str) {
        return next(str);
    }

    /**
     * Whether the next token value equals to @param str
     */
    protected boolean next(String str) {
        return _input.LT(1).getText().equals(str);
    }

    /** TypeScript resolves f<T>(x) as a generic call, not two comparisons. */
    protected boolean isTypeArgumentCall() {
        if (_input.LA(1) != TypeScriptParser.LessThan) return false;
        int position = _input.index();
        TypeScriptParser probe = new TypeScriptParser(_input);
        probe.removeErrorListeners();
        probe.setErrorHandler(new BailErrorStrategy());
        try {
            probe.typeArguments();
            return _input.LA(1) == TypeScriptParser.OpenParen;
        } catch (org.antlr.v4.runtime.misc.ParseCancellationException ignored) {
            return false;
        } finally {
            _input.seek(position);
        }
    }

    protected boolean notLineTerminator() {
        return !lineTerminatorAhead();
    }

    protected boolean notOpenBraceAndNotFunctionAndNotInterface() {
        int nextTokenType = _input.LT(1).getType();
        return nextTokenType != TypeScriptParser.OpenBrace && nextTokenType != TypeScriptParser.Function_
                && nextTokenType != TypeScriptParser.Interface;
    }

    protected boolean closeBrace() {
        return _input.LT(1).getType() == TypeScriptParser.CloseBrace;
    }
    
    /** Checks all hidden trivia before the current token for automatic semicolon insertion. */
    protected boolean lineTerminatorAhead() {
        for (int index = getCurrentToken().getTokenIndex() - 1; index >= 0; index--) {
            Token token = _input.get(index);
            if (token.getChannel() == Token.DEFAULT_CHANNEL) return false;
            String text = token.getText();
            if (text.indexOf('\r') >= 0 || text.indexOf('\n') >= 0
                    || text.indexOf('\u2028') >= 0 || text.indexOf('\u2029') >= 0) return true;
        }
        return false;
    }
}
