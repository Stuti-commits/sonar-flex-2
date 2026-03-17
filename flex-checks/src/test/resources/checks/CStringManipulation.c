int main() {

    /* ══ Rule 1: Invalid escape sequences ═══════════════════════════════════ */

    char a;
    char b;
    char c;

    a = '\n';               /* Compliant — valid escape */
    a = '\t';               /* Compliant — valid escape */
    a = '\\';               /* Compliant — valid escape */
    a = '\0';               /* Compliant — null character */

    a = '\p';               // Noncompliant — \p is not a valid escape sequence
    a = '\q';               // Noncompliant — \q is not a valid escape sequence

    char s1[10];
    s1[0] = 'x';            /* Compliant */

    /* Valid escape sequences in string literal */
    char valid[20];
    valid[0] = '\a';        /* Compliant — bell */
    valid[1] = '\b';        /* Compliant — backspace */
    valid[2] = '\f';        /* Compliant — form feed */
    valid[3] = '\r';        /* Compliant — carriage return */
    valid[4] = '\v';        /* Compliant — vertical tab */

    /* ══ Rule 2: Multi-character char constant ═══════════════════════════════ */

    int mc1;
    int mc2;
    mc1 = 'A';              /* Compliant — single char */
    mc2 = 'AB';             // Noncompliant — multi-char constant, portability issue
    mc2 = 'ABC';            // Noncompliant — multi-char constant, portability issue

    /* ══ Rule 3: Char array too small for string literal ═════════════════════ */

    /* Compliant — array size accounts for null terminator */
    char name1[5];          /* Compliant — size declared separately */
    char name2[5] = "ABCD"; /* Compliant — 4 chars + \0 = 5, fits exactly */
    char name3[6] = "ABCD"; /* Compliant — extra space is fine */

    /* Noncompliant — no room for null terminator */
    char bad1[4] = "ABCD";  // Noncompliant — needs [5], no room for \0
    char bad2[1] = "AB";    // Noncompliant — needs [3], no room for \0

    /* Implied size — fine, compiler figures out size */
    char auto1[] = "HELLO"; /* Compliant — compiler allocates 6 */

    /* ══ Rule 5: Wide string to plain char array ═════════════════════════════ */

    char wide_bad[10] = L"hello"; // Noncompliant — L"..." to plain char[]

    /* ══ Valid programs — no issues ══════════════════════════════════════════ */

    char msg[14] = "Hello, World!"; /* Compliant — 13 chars + \0 = 14 */
    char esc[5]  = "a\nb";          /* Compliant — 3 logical chars + \0 = 4, fits in 5 */

    return 0;
}