/*
 * Copyright © 2017-2023 WireGuard LLC. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0
 *
 * Modified for deeps kaskad.
 */

package org.amnezia.awg.model

object TunnelComparator : Comparator<String> {

    /**
     * Ranks of the user's own order; tunnels that are not ranked yet (freshly created ones) sort
     * after the ranked ones by the natural order of their names.
     */
    @Volatile
    private var ranks: Map<String, Int> = emptyMap()

    fun updateRanks(newRanks: Map<String, Int>) {
        ranks = newRanks
    }

    private class NaturalSortString(originalString: String) {
        class NaturalSortToken(val maybeString: String?, val maybeNumber: Int?) : Comparable<NaturalSortToken> {
            override fun compareTo(other: NaturalSortToken): Int {
                if (maybeString == null) {
                    if (other.maybeString != null || maybeNumber!! < other.maybeNumber!!) {
                        return -1
                    } else if (maybeNumber > other.maybeNumber) {
                        return 1
                    }
                } else if (other.maybeString == null || maybeString > other.maybeString) {
                    return 1
                } else if (maybeString < other.maybeString) {
                    return -1
                }
                return 0
            }
        }

        val tokens: MutableList<NaturalSortToken> = ArrayList()

        init {
            for (s in NATURAL_SORT_DIGIT_FINDER.findAll(originalString.split(WHITESPACE_FINDER).joinToString(" ").lowercase())) {
                try {
                    val n = s.value.toInt()
                    tokens.add(NaturalSortToken(null, n))
                } catch (_: NumberFormatException) {
                    tokens.add(NaturalSortToken(s.value, null))
                }
            }
        }

        private companion object {
            private val NATURAL_SORT_DIGIT_FINDER = Regex("""\d+|\D+""")
            private val WHITESPACE_FINDER = Regex("""\s""")
        }
    }

    override fun compare(a: String, b: String): Int {
        if (a == b)
            return 0
        val rankA = ranks[a]
        val rankB = ranks[b]
        if (rankA != null && rankB != null) return rankA.compareTo(rankB)
        if (rankA != null) return -1
        if (rankB != null) return 1
        val na = NaturalSortString(a)
        val nb = NaturalSortString(b)
        for (i in 0 until nb.tokens.size) {
            if (i == na.tokens.size) {
                return -1
            }
            val c = na.tokens[i].compareTo(nb.tokens[i])
            if (c != 0)
                return c
        }
        return 1
    }
}
