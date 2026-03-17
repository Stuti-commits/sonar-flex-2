/* ══ Rule 1: void function must not return a value ═══════════════════════ */

void correct_void() {
    return;               /* Compliant — bare return in void function */
}

void another_void() {
                          /* Compliant — no return at all in void function */
}

void bad_void() {
    return 5;             // Noncompliant — void function returns a value
}

void bad_void2() {
    int x;
    x = 10;
    return x + 1;         // Noncompliant — void function returns an expression
}

/* ══ Rule 2: non-void bare return gives undefined value ══════════════════ */

int correct_int() {
    return 42;            /* Compliant */
}

int bad_return() {
    return;               // Noncompliant — int function with bare return
}

/* ══ Rule 3: invalid storage class on function definition ════════════════ */

static int valid_static() {
    return 1;             /* Compliant — static is allowed */
}

typedef int bad_typedef_func() {
    return 1;             // Noncompliant — typedef is not valid storage class
}

auto int bad_auto_func() {
    return 1;             // Noncompliant — auto is not valid on function def
}

register int bad_register_func() {
    return 1;             // Noncompliant — register is not valid on function def
}

/* ══ Rule 4: non-void function with empty body ═══════════════════════════ */

int empty_body() {        // Noncompliant — non-void with empty body
}

void empty_void_body() {
                          /* Compliant — void with empty body is fine */
}

/* ══ Rule 5: function name conflicts with type keyword ═══════════════════ */

int main() {
    return 0;             /* Compliant — main is valid */
}

/* ══ Combinations ════════════════════════════════════════════════════════ */

int multi_return(int x) {
    if (x > 0) {
        return x;         /* Compliant */
    }
    return 0;             /* Compliant */
}

void void_with_no_return() {
    int x;
    x = 1;
                          /* Compliant — void, no return needed */
}