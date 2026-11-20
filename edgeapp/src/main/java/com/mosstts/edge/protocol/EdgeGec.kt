/*
 * SPDX-License-Identifier: LGPL-3.0-only
 * Kotlin adaptation of src/edge_tts/drm.py, edge-tts commit b2ffbdd9f43f28f03cc939b5de0f3964aa62d60e.
 * Copyright (c) rany <rany@riseup.net> and edge-tts contributors.
 * Changes: Kotlin/JVM integer implementation, using the same v2 protocol constants.
 * See assets/licenses for license texts and THIRD_PARTY_NOTICES.md for source/build instructions.
 */
package com.mosstts.edge.protocol

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Edge v2 uses a modified SHA-256 state and a CS64 digest, NOT a standard SHA-256 hash. */
object EdgeGec {
    private val initial = words("6A09E667 BB67AE85 3C6EF372 A54FF53A 510E527F 9B05688C 1F83D9AB 5BE0CD19")
    private val resume = words("FD9FBEA2 91243CE7 BE499094 CD2E1E0A 6C006F49 04BD35AB FEB0A7E4 03EFFBD2")
    private val schedule = words("EB24BF9E 1558691F A0C09EE2 0CFA32BA A622EE8C D1DD9491 C0DD25A0 F9E70AB6 F0C31573 7058CE4E 7188E606 00E5AB33 D258B060 72766564")
    private val constants = words("428A2F98 71374491 B5C0FBCF E9B5DBA5 3956C25B 59F111F1 923F82A4 AB1C5ED5 D807AA98 12835B01 243185BE 550C7DC3 72BE5D74 80DEB1FE 9BDC06A7 C19BF174 E49B69C1 EFBE4786 0FC19DC6 240CA1CC 2DE92C6F 4A7484AA 5CB0A9DC 76F988DA 983E5152 A831C66D B00327C8 BF597FC7 C6E00BF3 D5A79147 06CA6351 14292967 27B70A85 2E1B2138 4D2C6DFC 53380D13 650A7354 766A0ABB 81C2C92E 92722C85 A2BFE8A1 A81A664B C24B8B70 C76C51A3 D192E819 D6990624 F40E3585 106AA070 19A4C116 1E376C08 2748774C 34B0BCB5 391C0CB3 4ED8AA4A 5B9CCA4F 682E6FF3 748F82EE 78A5636F 84C87814 8CC70208 90BEFFFA A4506CEB BEF9A3F7 C67178F2")
    private val coefficients = words("E82E91B1 44DE24C6 3BEB65BC C89C5933 8D1EC211 314EF5D5 E92491B1 F0A5B9D5 06064CF3 DA79FA60 BED20560 89F9C9F8 35FA38C6 CB49E3C6")
    private val offsets = words("B32700D0 88DFF51D 29514038 B328CBDF D405B4D5 26E55F3C 607801C6 82B2C1FB FA51A3DA 207CC464 3C3C74F3 D2D14B61 BB04A2C1 C5626167")

    fun generate(unixSeconds: Long): String {
        val seconds = unixSeconds + 11644473600L
        val ticks = (seconds - Math.floorMod(seconds, 300L)) * 10000000L
        val data = "$ticks${EdgeProtocol.TOKEN}".toByteArray(Charsets.US_ASCII)
        val digest = digest(data)
        val cs = cs64(data, digest)
        return hex(cs) + "-" + hex(digest)
    }

    private fun words(value: String) = value.split(' ').map { it.toLong(16).toInt() }.toIntArray()
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02X".format(it.toInt() and 255) }
    private fun sigma0(x: Int) = Integer.rotateRight(x, 7) xor Integer.rotateRight(x, 18) xor (x ushr 3)
    private fun sigma1(x: Int) = Integer.rotateRight(x, 17) xor Integer.rotateRight(x, 19) xor (x ushr 10)
    private fun expand(w: IntArray, start: Int) {
        for (i in start..63) w[i] = sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]
    }

    private fun rounds(state: IntArray, w: IntArray, start: Int = 0): IntArray {
        var a = state[0]; var b = state[1]; var c = state[2]; var d = state[3]
        var e = state[4]; var f = state[5]; var g = state[6]; var h = state[7]
        for (i in start..63) {
            val k = if (start == 14 && i == 14) 0xC9DC06A7.toInt() else constants[i]
            val t1 = h + (Integer.rotateRight(e, 6) xor Integer.rotateRight(e, 11) xor Integer.rotateRight(e, 25)) +
                (g xor (e and (f xor g))) + k + w[i]
            val t2 = (Integer.rotateRight(a, 2) xor Integer.rotateRight(a, 13) xor Integer.rotateRight(a, 22)) +
                ((a and b) or (c and (a or b)))
            h = g; g = f; f = e; e = d + t1; d = c; c = b; b = a; a = t1 + t2
        }
        return intArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun digest(data: ByteArray): ByteArray {
        val w = IntArray(64)
        w[14] = ByteBuffer.wrap(byteArrayOf(0, data[0], data[1], data[2])).int
        w[15] = ByteBuffer.wrap(data, 3, 4).int
        for (i in 16..29) {
            var value = schedule[i - 16]
            if (i - 2 >= 14) value += sigma1(w[i - 2]) xor (if (i == 16) 0x000B92C0 else 0)
            if (i - 7 >= 14) value += w[i - 7]
            if (i - 15 >= 14) value += sigma0(w[i - 15]) xor (if (i == 29) 0x059C0B80 else 0)
            w[i] = value
        }
        w[30] = sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + 0xC9DC06A7.toInt() - constants[14]
        expand(w, 31)
        val first = rounds(resume, w, 14)
        var state = IntArray(8) { initial[it] + first[it] }
        val length = 57 + data.size
        val padding = Math.floorMod(55 - length, 64)
        val tail = ByteBuffer.allocate(data.size - 7 + 1 + padding + 8)
            .put(data, 7, data.size - 7).put(0x80.toByte()).put(ByteArray(padding)).putLong(length * 8L).array()
        for (offset in tail.indices step 64) {
            val block = ByteBuffer.wrap(tail, offset, 64)
            for (i in 0..15) w[i] = block.int
            expand(w, 16)
            val working = rounds(state, w)
            state = IntArray(8) { state[it] + working[it] }
        }
        return ByteBuffer.allocate(32).apply { state.forEach { putInt(it) } }.array()
    }

    private fun cs64(data: ByteArray, digest: ByteArray): ByteArray {
        val keys = ByteBuffer.wrap(digest).order(ByteOrder.LITTLE_ENDIAN)
        val keyA = keys.int or 1
        val keyB = keys.int or 1
        val factors = intArrayOf(keyA * 0x82893933.toInt(), keyB * 0x82893933.toInt())
        val terms = coefficients.mapIndexed { i, coefficient -> coefficient * factors[i % 2] }.toMutableList()
        val shifts = offsets.toMutableList()
        val padded = ByteArray(((data.size + 1 + 7) / 8) * 8)
        data.copyInto(padded, 1)
        val input = ByteBuffer.wrap(padded).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until padded.size / 4) {
            val word = input.int
            var term = word * (if (i % 2 == 0) keyA else keyB)
            var shift = Integer.rotateLeft(word, 16) * (if (i % 2 == 0) 0xEF0569FB.toInt() else 0xC31713DB.toInt())
            if (i == 0) {
                term += 0xF2F90B1A.toInt() * factors[0]
                shift += 0x0B1A0000
            }
            terms.add(term); shifts.add(shift)
        }
        var swapAcc = 0; var swapLast = 0; var state = 0; var acc = 0
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var q = Integer.rotateLeft(shifts[i] + x, 16) * 0x76F50F34
            swapAcc += Integer.rotateLeft(q, 16) * 0xC3EFEA97.toInt() + q
            q = Integer.rotateLeft(shifts[i + 1] + y, 16) * 0x1881CE3C
            swapLast = Integer.rotateLeft(q, 16) * 0x35BD1EC9 + q
            swapAcc += swapLast
            state = state * keyA + x
            for (factor in intArrayOf(0xCF98B111.toInt(), 0x87085B9F.toInt(), 0x12CEB96D, 0x257E1D83)) {
                state = Integer.rotateLeft(state, 16) * factor
            }
            acc += state
            var t = state * keyB + y
            for (factor in intArrayOf(0xA27416F5.toInt(), 0xD38396FF.toInt(), 0x7C932B89, 0xBFA49F69.toInt())) {
                t = Integer.rotateLeft(t, 16) * factor
            }
            state = t
            acc += state
        }
        return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(swapLast xor state).putInt(swapAcc xor acc).array()
    }
}
