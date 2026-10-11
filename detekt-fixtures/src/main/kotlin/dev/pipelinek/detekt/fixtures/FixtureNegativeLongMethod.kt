package dev.pipelinek.detekt.fixtures

/**
 * R3.5 negative fixture — intentionally triggers the
 * `LongMethod` rule in `detekt.yml` (threshold = 60 lines
 * per the S3 E3.2 baseline). Used by `scripts/verify-detekt.sh`
 * to assert that the rule actually fires on a method over the
 * threshold and is not silently disabled by a typo in the YAML.
 *
 * The method below is 80+ lines on purpose. Do NOT refactor it
 * to be shorter — that defeats the fixture. If the rule
 * threshold or activation changes, update both the YAML and
 * this fixture together and document the change in the
 * receipt.
 */
@Suppress("unused")
class FixtureNegativeLongMethod {

    fun longMethodThatMustFailDetekt(): Int {
        val a = 1
        val b = 2
        val c = 3
        val d = 4
        val e = 5
        val f = 6
        val g = 7
        val h = 8
        val i = 9
        val j = 10
        val k = 11
        val l = 12
        val m = 13
        val n = 14
        val o = 15
        val p = 16
        val q = 17
        val r = 18
        val s = 19
        val t = 20
        val u = 21
        val v = 22
        val w = 23
        val x = 24
        val y = 25
        val z = 26
        val aa = 27
        val ab = 28
        val ac = 29
        val ad = 30
        val ae = 31
        val af = 32
        val ag = 33
        val ah = 34
        val ai = 35
        val aj = 36
        val ak = 37
        val al = 38
        val am = 39
        val an = 40
        val ao = 41
        val ap = 42
        val aq = 43
        val ar = 44
        val asum = 45
        val at = 46
        val au = 47
        val av = 48
        val aw = 49
        val ax = 50
        val ay = 51
        val az = 52
        val ba = 53
        val bb = 54
        val bc = 55
        val bd = 56
        val be = 57
        val bf = 58
        val bg = 59
        val bh = 60
        val bi = 61
        val bj = 62
        val bk = 63
        val bl = 64
        val bm = 65
        val bn = 66
        val bo = 67
        val bp = 68
        val bq = 69
        val br = 70
        val bs = 71
        val bt = 72
        return a + b + c + d + e + f + g + h + i + j + k + l + m + n + o + p + q + r + s + t + u + v + w + x + y + z +
            aa + ab + ac + ad + ae + af + ag + ah + ai + aj + ak + al + am + an + ao + ap + aq + ar + asum + at + au +
            av + aw + ax + ay + az + ba + bb + bc + bd + be + bf + bg + bh + bi + bj + bk + bl + bm + bn + bo + bp +
            bq + br + bs + bt
    }
}