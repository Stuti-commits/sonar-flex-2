int main() {

    int x;
    x = 2;

    /* ══ Rule 1: case after default ══════════════════════════════════════ */

    switch (x) {
        default:
            x = 0;
            break;
        case 1:          // Noncompliant — case after default
            break;
    }

    switch (x) {
        case 1:
            break;
        default:         /* Compliant — default is last */
            break;
    }

    switch (x) {
        case 1:
            break;
        default:
            break;
        case 2:          // Noncompliant — case after default
            break;
        case 3:          // Noncompliant — case after default
            break;
    }

    /* ══ Rule 2: empty switch ════════════════════════════════════════════ */

    switch (x) {         // Noncompliant — no labels at all
    }

    /* ══ Rule 3: fall-through ════════════════════════════════════════════ */

    switch (x) {
        case 1:          // Noncompliant — falls through to case 2
            x = 10;
        case 2:
            break;
        default:
            break;
    }

    switch (x) {
        case 1:
            break;       /* Compliant — has break */
        case 2:
            return 0;    /* Compliant — has return */
        default:
            break;
    }

    /* ══ Rule 4: duplicate case values ══════════════════════════════════ */

    switch (x) {
        case 1:
            break;
        case 1:          // Noncompliant — duplicate case value '1'
            break;
        default:
            break;
    }

    switch (x) {
        case 1:
            break;
        case 2:          /* Compliant — all unique */
            break;
        default:
            break;
    }

    return 0;
}
