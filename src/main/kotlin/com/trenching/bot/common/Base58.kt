package com.trenching.bot.common

import java.math.BigInteger

internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
    private val BASE: BigInteger = BigInteger.valueOf(ALPHABET.length.toLong())
    private val ZERO_DIGIT = ALPHABET.first()

    fun requireBytes(value: String, bytes: Int) {
        require(value.length in bytes..(bytes * 2)) { "Invalid base58 length" }
        val decoded = requireNotNull(decode(value)) { "Invalid base58 character" }
        require(decoded.size == bytes) { "Invalid decoded base58 length" }
    }

    /** Returns null when [value] contains a character outside the Bitcoin base58 alphabet. */
    fun decode(value: String): ByteArray? {
        var number = BigInteger.ZERO
        for (character in value) {
            val digit = ALPHABET.indexOf(character)
            if (digit < 0) return null
            number = number.multiply(BASE).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = value.takeWhile { it == ZERO_DIGIT }.length
        val payload = if (number.signum() == 0) ByteArray(0) else number.toByteArray().let { bytes ->
            if (bytes.first() == 0.toByte()) bytes.copyOfRange(1, bytes.size) else bytes
        }
        return ByteArray(leadingZeros) + payload
    }

    fun encode(bytes: ByteArray): String {
        val leadingZeros = bytes.takeWhile { it == 0.toByte() }.size
        var number = BigInteger(1, bytes)
        val digits = StringBuilder()
        while (number.signum() > 0) {
            val (quotient, remainder) = number.divideAndRemainder(BASE)
            digits.append(ALPHABET[remainder.toInt()])
            number = quotient
        }
        repeat(leadingZeros) { digits.append(ZERO_DIGIT) }
        return digits.reverse().toString()
    }
}
