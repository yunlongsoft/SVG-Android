package com.github.megatronking.svg.generator.svg.css;

import java.lang.reflect.Field;

/**
 * A CSS Parser.
 *
 * @author Megatron King
 * @since 2017/1/4 13:47
 */
public class CSSParser {

    // Parsing something like the following:
    // (@rule | ruleset | block)*
    //
    // @rule       (block | identifier)*; (block with {} ends @rule)
    // block       matching [] () {} (that is, [()] is a block, [(){}{[]}]
    //                                is a block, ()[] is two blocks)
    // identifier  "*" | '*' | anything but a [](){} and whitespace
    //
    // ruleset     selector decblock
    // selector    (identifier | (block, except block '{}') )*
    // declblock   declaration* block*
    // declaration (identifier* stopping when identifier ends with :)
    //             (identifier* stopping when identifier ends with ;)
    //
    // comments /* */ can appear any where, and are stripped.


    // identifier - letters, digits, dashes and escaped characters
    // block starts with { ends with matching }, () [] and {} always occur
    //   in matching pairs, '' and "" also occur in pairs, except " may be


    // Indicates the type of token being parsed.
    private static final int   IDENTIFIER = 1;
    private static final int   BRACKET_OPEN = 2;
    private static final int   BRACKET_CLOSE = 3;
    private static final int   BRACE_OPEN = 4;
    private static final int   BRACE_CLOSE = 5;
    private static final int   PAREN_OPEN = 6;
    private static final int   PAREN_CLOSE = 7;
    private static final int   END = -1;

    private static final char[] charMapping = { 0, 0, '[', ']', '{', '}', '(',
            ')', 0};

    private char[] chars;
    private CSSParserCallback callback;

    private int iterateIndex;

    /**
     * Set to true if one character has been read ahead.
     */
    private boolean didPushChar;

    /**
     * The read ahead character.
     */
    private int pushedChar;

    /**
     * Temporary place to hold identifiers.
     */
    private StringBuffer unitBuffer;

    /**
     * Used to indicate blocks.
     */
    private int[] unitStack;

    /**
     * nextToken() inserts the string here.
     */
    private char[] tokenBuffer;

    /**
     * Number of valid blocks.
     */
    private int stackCount;

    /**
     * Current number of chars in tokenBufferLength.
     */
    private int tokenBufferLength;

    /**
     * Set to true when the first non @ rule is encountered.
     */
    private boolean encounteredRuleSet;

    /**
     * Set to true if any whitespace is read.
     */
    private boolean readWS;

    public CSSParser() {
        unitStack = new int[2];
        tokenBuffer = new char[80];
        unitBuffer = new StringBuffer();
    }

    public void parse(String css, CSSParserCallback callback, boolean inRule) {
        this.callback = callback;
        this.chars = string2chars(css);
        stackCount = tokenBufferLength = 0;
        encounteredRuleSet = false;
        if (inRule) {
            parseDeclarationBlock();
        } else {
            while (getNextStatement());
        }
    }

    private char[] string2chars(String s) {
        // the fastest way to iterate over all the chars in a string
        try {
            Field field = String.class.getDeclaredField("value");
            field.setAccessible(true);
            return (char[]) field.get(s);
        } catch (Exception e) {
            // it must not be happened.
        }
        return null;
    }

    /**
     * Parses a declaration block. Which a number of declarations followed
     * by a })].
     */
    private void parseDeclarationBlock() {
        for (;;) {
            int token = parseDeclaration();
            switch (token) {
                case END: case BRACE_CLOSE:
                    return;

                case BRACKET_CLOSE: case PAREN_CLOSE:
                    // Bail
                    throw new CSSException("Unexpected close in declaration block");
                case IDENTIFIER:
                    break;
            }
        }
    }

    /**
     * Parses a single declaration, which is an identifier a : and another
     * identifier. This returns the last token seen.
     */
    // identifier+: identifier* ;|}
    private int parseDeclaration() {
        int    token;

        if ((token = parseIdentifiers(':', false)) != IDENTIFIER) {
            return token;
        }
        // Make the property name to lowercase
        for (int counter = unitBuffer.length() - 1; counter >= 0; counter--) {
            unitBuffer.setCharAt(counter, Character.toLowerCase
                    (unitBuffer.charAt(counter)));
        }
        callback.handleProperty(unitBuffer.toString());

        token = parseIdentifiers(';', true);
        callback.handleValue(unitBuffer.toString());
        return token;
    }

    /**
     * Gets the next statement, returning false if the end is reached. A
     * statement is either an @rule, or a ruleset.
     */
    private boolean getNextStatement() {
        unitBuffer.setLength(0);

        int token = nextToken((char)0);

        switch (token) {
            case IDENTIFIER:
                if (tokenBufferLength > 0) {
                    if (tokenBuffer[0] == '@') {
                        parseAtRule();
                    }
                    else {
                        encounteredRuleSet = true;
                        parseRuleSet();
                    }
                }
                return true;
            case BRACKET_OPEN:
            case BRACE_OPEN:
            case PAREN_OPEN:
                parseTillClosed(token);
                return true;

            case BRACKET_CLOSE:
            case BRACE_CLOSE:
            case PAREN_CLOSE:
                // Shouldn't happen...
                throw new CSSException("Unexpected top level block close");

            case END:
                return false;
        }
        return true;
    }

    /**
     * Parses identifiers until <code>extraChar</code> is encountered,
     * returning the ending token, which will be IDENTIFIER if extraChar
     * is found.
     */
    private int parseIdentifiers(char extraChar,
                                 boolean wantsBlocks) {
        int   nextToken;
        int   ubl;

        unitBuffer.setLength(0);
        for (;;) {
            nextToken = nextToken(extraChar);

            switch (nextToken) {
                case IDENTIFIER:
                    if (tokenBufferLength > 0) {
                        if (tokenBuffer[tokenBufferLength - 1] == extraChar) {
                            if (--tokenBufferLength > 0) {
                                if (readWS && unitBuffer.length() > 0) {
                                    unitBuffer.append(' ');
                                }
                                unitBuffer.append(tokenBuffer, 0,
                                        tokenBufferLength);
                            }
                            return IDENTIFIER;
                        }
                        if (readWS && unitBuffer.length() > 0) {
                            unitBuffer.append(' ');
                        }
                        unitBuffer.append(tokenBuffer, 0, tokenBufferLength);
                    }
                    break;

                case BRACKET_OPEN:
                case BRACE_OPEN:
                case PAREN_OPEN:
                    ubl = unitBuffer.length();
                    if (wantsBlocks) {
                        unitBuffer.append(charMapping[nextToken]);
                    }
                    parseTillClosed(nextToken);
                    if (!wantsBlocks) {
                        unitBuffer.setLength(ubl);
                    }
                    break;

                case BRACE_CLOSE:
                    // No need to throw for these two, we return token and
                    // caller can do whatever.
                case BRACKET_CLOSE:
                case PAREN_CLOSE:
                case END:
                    // Hit the end
                    return nextToken;
            }
        }
    }

    /**
     * Reads a character from the stream.
     */
    private int readChar() {
        if (didPushChar) {
            didPushChar = false;
            return pushedChar;
        }
        if (iterateIndex > chars.length - 1) {
            return -1;
        }
        char c = chars[iterateIndex];
        iterateIndex++;
        return c;
    }

    /**
     * Fetches the next token.
     */
    private int nextToken(char idChar) {
        readWS = false;

        int     nextChar = readWS();

        switch (nextChar) {
            case '\'':
                readTill('\'');
                if (tokenBufferLength > 0) {
                    tokenBufferLength--;
                }
                return IDENTIFIER;
            case '"':
                readTill('"');
                if (tokenBufferLength > 0) {
                    tokenBufferLength--;
                }
                return IDENTIFIER;
            case '[':
                return BRACKET_OPEN;
            case ']':
                return BRACKET_CLOSE;
            case '{':
                return BRACE_OPEN;
            case '}':
                return BRACE_CLOSE;
            case '(':
                return PAREN_OPEN;
            case ')':
                return PAREN_CLOSE;
            case -1:
                return END;
            default:
                pushChar(nextChar);
                getIdentifier(idChar);
                return IDENTIFIER;
        }
    }

    /**
     * Reads till a <code>stopChar</code> is encountered, escaping characters
     * as necessary.
     */
    private void readTill(char stopChar) {
        boolean lastWasEscape = false;
        int escapeCount = 0;
        int escapeChar = 0;
        int nextChar;
        boolean done = false;
        int intStopChar = (int)stopChar;
        // 1 for '\', 2 for valid escape char [0-9a-fA-F], 0 otherwise
        short type;
        int escapeOffset = 0;

        tokenBufferLength = 0;
        while (!done) {
            nextChar = readChar();
            switch (nextChar) {
                case '\\':
                    type = 1;
                    break;

                case '0': case '1': case '2': case '3': case '4':case '5':
                case '6': case '7': case '8': case '9':
                    type = 2;
                    escapeOffset = nextChar - '0';
                    break;

                case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                    type = 2;
                    escapeOffset = nextChar - 'a' + 10;
                    break;

                case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                    type = 2;
                    escapeOffset = nextChar - 'A' + 10;
                    break;

                case -1:
                    // Prematurely reached the end!
                    throw new CSSException("Unclosed " + stopChar);

                default:
                    type = 0;
                    break;
            }
            if (lastWasEscape) {
                if (type == 2) {
                    // Continue with escape.
                    escapeChar = escapeChar * 16 + escapeOffset;
                    if (++escapeCount == 4) {
                        lastWasEscape = false;
                        append((char)escapeChar);
                    }
                }
                else {
                    // no longer escaped
                    if (escapeCount > 0) {
                        append((char)escapeChar);
                        if (type == 1) {
                            lastWasEscape = true;
                            escapeChar = escapeCount = 0;
                        }
                        else {
                            if (nextChar == intStopChar) {
                                done = true;
                            }
                            append((char)nextChar);
                            lastWasEscape = false;
                        }
                    }
                    else {
                        append((char)nextChar);
                        lastWasEscape = false;
                    }
                }
            }
            else if (type == 1) {
                lastWasEscape = true;
                escapeChar = escapeCount = 0;
            }
            else {
                if (nextChar == intStopChar) {
                    done = true;
                }
                append((char)nextChar);
            }
        }
    }

    private void append(char character) {
        if (tokenBufferLength == tokenBuffer.length) {
            char[] newBuffer = new char[tokenBuffer.length * 2];
            System.arraycopy(tokenBuffer, 0, newBuffer, 0, tokenBuffer.length);
            tokenBuffer = newBuffer;
        }
        tokenBuffer[tokenBufferLength++] = character;
    }

    /**
     * Parses a comment block.
     */
    private void readComment() {
        int nextChar;

        for(;;) {
            nextChar = readChar();
            switch (nextChar) {
                case -1:
                    throw new CSSException("Unclosed comment");
                case '*':
                    nextChar = readChar();
                    if (nextChar == '/') {
                        return;
                    }
                    else if (nextChar == -1) {
                        throw new CSSException("Unclosed comment");
                    }
                    else {
                        pushChar(nextChar);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Called when a block start is encountered ({[.
     */
    private void startBlock(int startToken) {
        if (stackCount == unitStack.length) {
            int[]     newUS = new int[stackCount * 2];

            System.arraycopy(unitStack, 0, newUS, 0, stackCount);
            unitStack = newUS;
        }
        unitStack[stackCount++] = startToken;
    }

    /**
     * Called when an end block is encountered )]}
     */
    private void endBlock(int endToken) {
        int    startToken;

        switch (endToken) {
            case BRACKET_CLOSE:
                startToken = BRACKET_OPEN;
                break;
            case BRACE_CLOSE:
                startToken = BRACE_OPEN;
                break;
            case PAREN_CLOSE:
                startToken = PAREN_OPEN;
                break;
            default:
                // Will never happen.
                startToken = -1;
                break;
        }
        if (stackCount > 0 && unitStack[stackCount - 1] == startToken) {
            stackCount--;
        }
        else {
            // Invalid state, should do something.
            throw new CSSException("Unmatched block");
        }
    }

    /**
     * @return true if currently in a block.
     */
    private boolean inBlock() {
        return (stackCount > 0);
    }

    /**
     * Skips any white space, returning the character after the white space.
     */
    private int readWS() {
        int nextChar;
        while ((nextChar = readChar()) != -1 &&
                Character.isWhitespace((char)nextChar)) {
            readWS = true;
        }
        return nextChar;
    }

    /**
     * Gets an identifier, returning true if the length of the string is greater than 0,
     * stopping when <code>stopChar</code>, whitespace, or one of {}()[] is
     * hit.
     */
    // NOTE: this could be combined with readTill, as they contain somewhat
    // similar functionality.
    private boolean getIdentifier(char stopChar) {
        boolean lastWasEscape = false;
        boolean done = false;
        int escapeCount = 0;
        int escapeChar = 0;
        int nextChar;
        int intStopChar = (int)stopChar;
        // 1 for '\', 2 for valid escape char [0-9a-fA-F], 3 for
        // stop character (white space, ()[]{}) 0 otherwise
        short type;
        int escapeOffset = 0;

        tokenBufferLength = 0;
        while (!done) {
            nextChar = readChar();
            switch (nextChar) {
                case '\\':
                    type = 1;
                    break;

                case '0': case '1': case '2': case '3': case '4': case '5':
                case '6': case '7': case '8': case '9':
                    type = 2;
                    escapeOffset = nextChar - '0';
                    break;

                case 'a': case 'b': case 'c': case 'd': case 'e': case 'f':
                    type = 2;
                    escapeOffset = nextChar - 'a' + 10;
                    break;

                case 'A': case 'B': case 'C': case 'D': case 'E': case 'F':
                    type = 2;
                    escapeOffset = nextChar - 'A' + 10;
                    break;

                case '\'': case '"': case '[': case ']': case '{': case '}':
                case '(': case ')':
                case ' ': case '\n': case '\t': case '\r':
                    type = 3;
                    break;

                case '/':
                    type = 4;
                    break;

                case -1:
                    // Reached the end
                    done = true;
                    type = 0;
                    break;

                default:
                    type = 0;
                    break;
            }
            if (lastWasEscape) {
                if (type == 2) {
                    // Continue with escape.
                    escapeChar = escapeChar * 16 + escapeOffset;
                    if (++escapeCount == 4) {
                        lastWasEscape = false;
                        append((char)escapeChar);
                    }
                }
                else {
                    // no longer escaped
                    lastWasEscape = false;
                    if (escapeCount > 0) {
                        append((char)escapeChar);
                        // Make this simpler, reprocess the character.
                        pushChar(nextChar);
                    }
                    else if (!done) {
                        append((char)nextChar);
                    }
                }
            }
            else if (!done) {
                if (type == 1) {
                    lastWasEscape = true;
                    escapeChar = escapeCount = 0;
                }
                else if (type == 3) {
                    done = true;
                    pushChar(nextChar);
                }
                else if (type == 4) {
                    // Potential comment
                    nextChar = readChar();
                    if (nextChar == '*') {
                        done = true;
                        readComment();
                        readWS = true;
                    }
                    else {
                        append('/');
                        if (nextChar == -1) {
                            done = true;
                        }
                        else {
                            pushChar(nextChar);
                        }
                    }
                }
                else {
                    append((char)nextChar);
                    if (nextChar == intStopChar) {
                        done = true;
                    }
                }
            }
        }
        return (tokenBufferLength > 0);
    }


    /**
     * Parses till a matching block close is encountered. This is only
     * appropriate to be called at the top level (no nesting).
     */
    private void parseTillClosed(int openToken) {
        int       nextToken;
        boolean   done = false;

        startBlock(openToken);
        while (!done) {
            nextToken = nextToken((char)0);
            switch (nextToken) {
                case IDENTIFIER:
                    if (unitBuffer.length() > 0 && readWS) {
                        unitBuffer.append(' ');
                    }
                    if (tokenBufferLength > 0) {
                        unitBuffer.append(tokenBuffer, 0, tokenBufferLength);
                    }
                    break;

                case BRACKET_OPEN: case BRACE_OPEN: case PAREN_OPEN:
                    if (unitBuffer.length() > 0 && readWS) {
                        unitBuffer.append(' ');
                    }
                    unitBuffer.append(charMapping[nextToken]);
                    startBlock(nextToken);
                    break;

                case BRACKET_CLOSE: case BRACE_CLOSE: case PAREN_CLOSE:
                    if (unitBuffer.length() > 0 && readWS) {
                        unitBuffer.append(' ');
                    }
                    unitBuffer.append(charMapping[nextToken]);
                    endBlock(nextToken);
                    if (!inBlock()) {
                        done = true;
                    }
                    break;

                case END:
                    // Prematurely hit end.
                    throw new CSSException("Unclosed block");
            }
        }
    }

    /**
     * Parses an @ rule, stopping at a matching brace pair, or ;.
     */
    private void parseAtRule() {
        // PENDING: make this more effecient.
        boolean        done = false;
        boolean isImport = (tokenBufferLength == 7 &&
                tokenBuffer[0] == '@' && tokenBuffer[1] == 'i' &&
                tokenBuffer[2] == 'm' && tokenBuffer[3] == 'p' &&
                tokenBuffer[4] == 'o' && tokenBuffer[5] == 'r' &&
                tokenBuffer[6] == 't');

        unitBuffer.setLength(0);
        while (!done) {
            int       nextToken = nextToken(';');

            switch (nextToken) {
                case IDENTIFIER:
                    if (tokenBufferLength > 0 &&
                            tokenBuffer[tokenBufferLength - 1] == ';') {
                        --tokenBufferLength;
                        done = true;
                    }
                    if (tokenBufferLength > 0) {
                        if (unitBuffer.length() > 0 && readWS) {
                            unitBuffer.append(' ');
                        }
                        unitBuffer.append(tokenBuffer, 0, tokenBufferLength);
                    }
                    break;

                case BRACE_OPEN:
                    if (unitBuffer.length() > 0 && readWS) {
                        unitBuffer.append(' ');
                    }
                    unitBuffer.append(charMapping[nextToken]);
                    parseTillClosed(nextToken);
                    done = true;
                    // Skip a tailing ';', not really to spec.
                {
                    int nextChar = readWS();
                    if (nextChar != -1 && nextChar != ';') {
                        pushChar(nextChar);
                    }
                }
                break;

                case BRACKET_OPEN:
                case PAREN_OPEN:
                    unitBuffer.append(charMapping[nextToken]);
                    parseTillClosed(nextToken);
                    break;

                case BRACKET_CLOSE:
                case BRACE_CLOSE:
                case PAREN_CLOSE:
                    throw new CSSException("Unexpected close in @ rule");

                case END:
                    done = true;
                    break;
            }
        }
        if (isImport && !encounteredRuleSet) {
            callback.handleImport(unitBuffer.toString());
        }
    }

    /**
     * Parses the next rule set, which is a selector followed by a
     * declaration block.
     */
    private void parseRuleSet() {
        if (parseSelectors()) {
            callback.startRule();
            parseDeclarationBlock();
            callback.endRule();
        }
    }

    /**
     * Parses a set of selectors, returning false if the end of the stream
     * is reached.
     */
    private boolean parseSelectors() {
        // Parse the selectors
        int nextToken;

        if (tokenBufferLength > 0) {
            callback.handleSelector(new String(tokenBuffer, 0,
                    tokenBufferLength));
        }

        unitBuffer.setLength(0);
        for (;;) {
            while ((nextToken = nextToken((char)0)) == IDENTIFIER) {
                if (tokenBufferLength > 0) {
                    callback.handleSelector(new String(tokenBuffer, 0,
                            tokenBufferLength));
                }
            }
            switch (nextToken) {
                case BRACE_OPEN:
                    return true;

                case BRACKET_OPEN: case PAREN_OPEN:
                    parseTillClosed(nextToken);
                    // Not too sure about this, how we handle this isn't very
                    // well spec'd.
                    unitBuffer.setLength(0);
                    break;

                case BRACKET_CLOSE: case BRACE_CLOSE: case PAREN_CLOSE:
                    throw new CSSException("Unexpected block close in selector");

                case END:
                    // Prematurely hit end.
                    return false;
            }
        }
    }

    /**
     * Supports one character look ahead, this will throw if called twice
     * in a row.
     */
    private void pushChar(int tempChar) {
        if (didPushChar) {
            // Should never happen.
            throw new CSSException("Can not handle look ahead of more than one character");
        }
        didPushChar = true;
        pushedChar = tempChar;
    }
}