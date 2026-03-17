/*
 * SonarQube Flex Plugin
 * Copyright (C) 2010-2025 SonarSource Sàrl
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the Sonar Source-Available License Version 1, as published by SonarSource SA.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the Sonar Source-Available License for more details.
 *
 * You should have received a copy of the Sonar Source-Available License
 * along with this program; if not, see https://sonarsource.com/license/ssal/
 */
package org.sonar.flex.checks;

import com.sonar.sslr.api.AstNode;
import com.sonar.sslr.api.AstNodeType;
import java.util.Arrays;
import java.util.List;
import org.sonar.check.Rule;
import org.sonar.flex.FlexCheck;
import org.sonar.flex.FlexGrammar;

/**
 * Unisys C — string and character constant checks.
 * Based on §1 (String Constants, Character Constants, Escape Sequences)
 * and §3 (Initializing Character Strings) of the C Programming Reference
 * Manual.
 *
 * Rule 1 — Invalid escape sequence in string or char constant.
 * Manual §1: "An escape sequence with a backslash and lowercase letter
 * other than a, b, f, n, r, t, or v is diagnosed as an error."
 * Valid: \' \" \? \\ \a \b \f \n \r \t \v \0 \ddd \xddd
 *
 * Rule 2 — Multi-character char constant (portability warning).
 * Manual §1: "The compiler allows character constants up to four characters
 * long. The use of this feature limits the portability of a program."
 * e.g. 'AB' or 'ABC'
 *
 * Rule 3 — Char array too small for string literal (off-by-one).
 * Manual §3: "For every string constant of n characters, the compiler
 * allocates a block of n+1 characters." — the null terminator needs space.
 * e.g. char name[4] = "WXYZ"; ← needs size 5
 *
 * Rule 4 — Adjacent string literal concatenation (accidental join warning).
 * Manual §1: "Strings that are adjacent tokens are concatenated."
 * e.g. "hello" "world" — likely a missing operator or comma.
 *
 * Rule 5 — Wide string assigned to plain char array.
 * Manual §1: "The type of a wide string constant is an array of wchar_t."
 * Assigning L"..." to a plain char[] is a type mismatch.
 */
@Rule(key = "C_StringManipulation")
public class CStringManipulationCheck extends FlexCheck {

    // Valid single-char escape letters per Unisys C §1 Table 1-3
    private static final String VALID_ESCAPE_CHARS = "'\"\\ ?abfnrtv01234567x";

    @Override
    public List<AstNodeType> subscribedTo() {
        return Arrays.asList(
                FlexGrammar.STRING_LITERAL,
                FlexGrammar.CHAR_CONSTANT,
                FlexGrammar.DECLARATION,
                FlexGrammar.PRIMARY_EXPR);
    }

    @Override
    public void visitNode(AstNode node) {
        if (node.is(FlexGrammar.STRING_LITERAL)) {
            checkStringLiteral(node);
        } else if (node.is(FlexGrammar.CHAR_CONSTANT)) {
            checkCharConstant(node);
        } else if (node.is(FlexGrammar.DECLARATION)) {
            checkCharArraySize(node);
        } else if (node.is(FlexGrammar.PRIMARY_EXPR)) {
            checkAdjacentStrings(node);
        }
    }

    // ── Rule 1: invalid escape sequences ─────────────────────────────────────
    private void checkStringLiteral(AstNode node) {
        String raw = node.getTokenValue();
        if (raw == null)
            return;

        // Strip L prefix and surrounding quotes
        String content = stripStringDelimiters(raw);

        // Rule 5: wide string (L"...") assigned to char — checked in checkCharArraySize
        // Here just validate escape sequences
        checkEscapeSequences(content, node);
    }

    // ── Rule 1 + Rule 2: char constant checks ─────────────────────────────────
    private void checkCharConstant(AstNode node) {
        String raw = node.getTokenValue();
        if (raw == null)
            return;

        // Strip L prefix and surrounding single quotes
        String content = stripCharDelimiters(raw);

        // Rule 1: check escape sequences inside char constant
        checkEscapeSequences(content, node);

        // Rule 2: multi-character constant (more than one logical char)
        int charCount = countLogicalChars(content);
        if (charCount > 1) {
            addIssue(
                    "Multi-character constant '" + raw + "' has " + charCount
                            + " characters — this limits portability (Unisys C §1).",
                    node);
        }
    }

    // ── Rule 3: char array too small for string initializer ───────────────────
    private void checkCharArraySize(AstNode decl) {
        // C_DECLARATION:
        // C_DECLARATION_SPECIFIERS C_INIT_DECLARATOR_LIST ;
        //
        // We look for: char name[N] = "string";
        // where N < len("string") + 1 (no room for null terminator)

        AstNode specifiers = decl.getFirstChild(FlexGrammar.DECLARATION_SPECIFIERS);
        if (specifiers == null)
            return;

        // Must be a char type
        if (!isCharType(specifiers))
            return;

        AstNode initDeclList = decl.getFirstChild(FlexGrammar.INIT_DECLARATOR_LIST);
        if (initDeclList == null)
            return;

        for (AstNode initDecl : initDeclList.getChildren(FlexGrammar.INIT_DECLARATOR)) {
            // C_INIT_DECLARATOR: C_DECLARATOR = C_INITIALIZER
            AstNode declarator = initDecl.getFirstChild(FlexGrammar.DECLARATOR);
            AstNode initializer = initDecl.getFirstChild(FlexGrammar.INITIALIZER);
            if (declarator == null || initializer == null)
                continue;

            // Get array size from declarator: name[N]
            int arraySize = extractArraySize(declarator);
            if (arraySize < 0)
                continue; // not an array declarator

            // Get string literal from initializer
            AstNode strLit = initializer.getFirstDescendant(FlexGrammar.STRING_LITERAL);
            if (strLit == null)
                continue;

            String raw = strLit.getTokenValue();
            if (raw == null)
                continue;

            // Rule 5: wide string (L"...") to plain char[]
            if (raw.startsWith("L\"") || raw.startsWith("l\"")) {
                addIssue(
                        "Wide string literal L\"...\" cannot be assigned to a plain char[] array"
                                + " — use wchar_t[] instead (Unisys C §1).",
                        strLit);
                continue;
            }

            // Count actual string length (logical chars, not raw bytes)
            String content = stripStringDelimiters(raw);
            int strLen = countLogicalChars(content);

            // Rule 3: array must be at least strLen + 1 for the null terminator
            if (arraySize <= strLen) {
                addIssue(
                        "Array size [" + arraySize + "] is too small for string \"" + content
                                + "\" (" + strLen + " chars) — needs at least [" + (strLen + 1)
                                + "] to hold the null terminator '\\0' (Unisys C §1, §3).",
                        declarator);
            }
        }
    }

    // ── Rule 4: adjacent string literal concatenation ─────────────────────────
    private void checkAdjacentStrings(AstNode primaryExpr) {
        // C_PRIMARY_EXPR can contain a C_STRING_LITERAL
        // Adjacent string tokens means two C_STRING_LITERAL children in sequence
        // at expression level — look for "a" "b" patterns
        List<AstNode> children = primaryExpr.getChildren();
        AstNode prevStr = null;

        for (AstNode child : children) {
            if (child.is(FlexGrammar.STRING_LITERAL)) {
                if (prevStr != null) {
                    addIssue(
                            "Adjacent string literals \"...\" \"...\" are concatenated — "
                                    + "verify this is intentional and not a missing comma or operator"
                                    + " (Unisys C §1).",
                            child);
                }
                prevStr = child;
            } else {
                prevStr = null;
            }
        }
    }

    // ── Escape sequence validator (Rules 1) ───────────────────────────────────
    private void checkEscapeSequences(String content, AstNode node) {
        int i = 0;
        while (i < content.length()) {
            char c = content.charAt(i);
            if (c == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);

                if (VALID_ESCAPE_CHARS.indexOf(next) < 0) {
                    // Not a valid escape character
                    addIssue(
                            "Invalid escape sequence '\\" + next
                                    + "' — valid sequences are \\' \\\" \\\\ \\? "
                                    + "\\a \\b \\f \\n \\r \\t \\v \\0 \\ddd \\xddd (Unisys C §1 Table 1-3).",
                            node);
                }

                // Skip past the escape sequence
                if (next == 'x') {
                    // \xddd — skip hex digits
                    i += 2;
                    while (i < content.length() && isHexDigit(content.charAt(i)))
                        i++;
                } else if (isOctalDigit(next)) {
                    // \ddd — skip up to 3 octal digits
                    i += 2;
                    int count = 1;
                    while (i < content.length() && isOctalDigit(content.charAt(i)) && count < 3) {
                        i++;
                        count++;
                    }
                } else {
                    i += 2; // skip backslash + one char
                }
            } else {
                i++;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Strip surrounding double quotes and optional L prefix from string token. */
    private static String stripStringDelimiters(String raw) {
        String s = raw;
        if (s.startsWith("L\"") || s.startsWith("l\""))
            s = s.substring(2);
        else if (s.startsWith("\""))
            s = s.substring(1);
        if (s.endsWith("\""))
            s = s.substring(0, s.length() - 1);
        return s;
    }

    /** Strip surrounding single quotes and optional L prefix from char token. */
    private static String stripCharDelimiters(String raw) {
        String s = raw;
        if (s.startsWith("L'") || s.startsWith("l'"))
            s = s.substring(2);
        else if (s.startsWith("'"))
            s = s.substring(1);
        if (s.endsWith("'"))
            s = s.substring(0, s.length() - 1);
        return s;
    }

    /**
     * Count logical characters (each escape sequence = 1 char).
     * e.g. "hi\n" = 3 logical chars: 'h', 'i', '\n'
     */
    private static int countLogicalChars(String content) {
        int count = 0;
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '\\' && i + 1 < content.length()) {
                char next = content.charAt(i + 1);
                if (next == 'x') {
                    i += 2;
                    while (i < content.length() && isHexDigit(content.charAt(i)))
                        i++;
                } else if (isOctalDigit(next)) {
                    i += 2;
                    int d = 1;
                    while (i < content.length() && isOctalDigit(content.charAt(i)) && d < 3) {
                        i++;
                        d++;
                    }
                } else {
                    i += 2;
                }
            } else {
                i++;
            }
            count++;
        }
        return count;
    }

    /**
     * Returns true if the declaration specifiers indicate a char type.
     * Covers: char, signed char, unsigned char
     */
    private static boolean isCharType(AstNode specifiers) {
        String text = specifiers.getTokenValue();
        if (text == null) {
            // Walk children to find CHAR keyword
            for (AstNode child : specifiers.getDescendants(FlexGrammar.TYPE_SPECIFIER)) {
                String tv = child.getTokenValue();
                if (tv != null && tv.toLowerCase().contains("char"))
                    return true;
                if (child.getFirstChild() != null) {
                    String fv = child.getFirstChild().getTokenValue();
                    if (fv != null && fv.equalsIgnoreCase("char"))
                        return true;
                }
            }
            return false;
        }
        return text.toLowerCase().contains("char");
    }

    /**
     * Extract the integer array dimension from C_DECLARATOR → C_DIRECT_DECLARATOR.
     * Returns the size as int, or -1 if not an array declarator.
     * e.g. name[5] → 5
     */
    private static int extractArraySize(AstNode declarator) {
        AstNode directDecl = declarator.getFirstChild(FlexGrammar.DIRECT_DECLARATOR);
        if (directDecl == null)
            return -1;

        // C_DIRECT_DECLARATOR: C_IDENTIFIER [ C_EXPRESSION? ]
        AstNode bracket = directDecl.getFirstChild(FlexGrammar.EXPRESSION);
        if (bracket == null)
            return -1;

        // Try to parse the expression token as a plain integer
        String val = bracket.getTokenValue();
        if (val == null)
            return -1;
        try {
            return Integer.parseInt(val.trim());
        } catch (NumberFormatException e) {
            return -1; // dynamic size — can't check statically
        }
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static boolean isOctalDigit(char c) {
        return c >= '0' && c <= '7';
    }
}
