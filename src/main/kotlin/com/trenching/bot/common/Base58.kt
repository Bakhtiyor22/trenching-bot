package com.trenching.bot.common

import java.math.BigInteger

internal object Base58 {
    private const val ALPHABET = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"

    fun requireBytes(value: String, bytes: Int) {
        require(value.length in bytes..(bytes * 2)) { "Invalid base58 length" }
        var number = BigInteger.ZERO
        value.forEach { character ->
            val digit = ALPHABET.indexOf(character)
            require(digit >= 0) { "Invalid base58 character" }
            number = number.multiply(BigInteger.valueOf(58)).add(BigInteger.valueOf(digit.toLong()))
        }
        val leadingZeros = value.takeWhile { it == '1' }.length
        val payloadBytes = (number.bitLength() + 7) / 8
        require(leadingZeros + payloadBytes == bytes) { "Invalid decoded base58 length" }
    }
}
