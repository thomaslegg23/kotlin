// IGNORE_BACKEND: JS_IR, JS, NATIVE, JVM_IR
// WITH_REFLECT
import kotlin.test.assertEquals

interface ITest {
    val test: String
}

inline class Z(val x: Int) : ITest {
    override val test get() = "-$x-"
}

inline class L(val x: Long) : ITest {
    override val test get() = "-$x-"
}

inline class S(val x: String) : ITest {
    override val test get() = "-$x-"
}

inline class A(val x: Any) : ITest {
    override val test get() = "-$x-"
}

fun box(): String {
    assertEquals("-42-", Z::test.call(Z(42)))
    assertEquals("-42-", Z(42)::test.call())
    assertEquals("-42-", Z::test.getter.call(Z(42)))
    assertEquals("-42-", Z(42)::test.getter.call())

    assertEquals("-42-", L::test.call(L(42L)))
    assertEquals("-42-", L(42L)::test.call())
    assertEquals("-42-", L::test.getter.call(L(42L)))
    assertEquals("-42-", L(42L)::test.getter.call())

    assertEquals("-42-", S::test.call(S("42")))
    assertEquals("-42-", S("42")::test.call())
    assertEquals("-42-", S::test.getter.call(S("42")))
    assertEquals("-42-", S("42")::test.getter.call())

    assertEquals("-42-", A::test.call(A("42")))
    assertEquals("-42-", A("42")::test.call())
    assertEquals("-42-", A::test.getter.call(A("42")))
    assertEquals("-42-", A("42")::test.getter.call())

    return "OK"
}
