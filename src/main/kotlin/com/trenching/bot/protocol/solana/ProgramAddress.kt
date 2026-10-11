package com.trenching.bot.protocol.solana

import com.trenching.bot.common.Base58
import java.math.BigInteger
import java.security.MessageDigest

/** Solana program-derived addresses, computed offline: no RPC, no key material. */
object ProgramAddress {
    const val PUBLIC_KEY_BYTES = 32
    private const val MAX_SEED_BYTES = 32
    private const val MAX_SEEDS = 16
    private const val MAX_BUMP = 255
    private const val MIN_BUMP = 1
    private val MARKER = "ProgramDerivedAddress".toByteArray(Charsets.US_ASCII)
    private val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P)
    private val LEGENDRE_EXPONENT: BigInteger = P.subtract(BigInteger.ONE).shiftRight(1)

    data class Derived(val address: String, val bump: Int)

    /**
     * Solana's `find_program_address`: the first bump from 255 down to 1 whose hash is not an ed25519 point.
     * Null, like Solana's `try_find_program_address`, only if every such bump lands on the curve.
     */
    fun find(seeds: List<ByteArray>, programId: String): Derived? {
        require(seeds.size < MAX_SEEDS) { "At most ${MAX_SEEDS - 1} seeds, since the bump is one more" }
        require(seeds.all { it.size <= MAX_SEED_BYTES }) { "A seed is at most $MAX_SEED_BYTES bytes" }
        Base58.requireBytes(programId, PUBLIC_KEY_BYTES)
        val program = requireNotNull(Base58.decode(programId))
        for (bump in MAX_BUMP downTo MIN_BUMP) {
            val digest = MessageDigest.getInstance("SHA-256")
            seeds.forEach(digest::update)
            digest.update(bump.toByte())
            digest.update(program)
            digest.update(MARKER)
            val candidate = digest.digest()
            if (!isOnCurve(candidate)) return Derived(Base58.encode(candidate), bump)
        }
        return null
    }

    /**
     * Whether [publicKey] decompresses to an ed25519 point, as curve25519-dalek decides it: the sign bit is ignored,
     * y is reduced mod p, and the point exists exactly when (y² − 1) / (d·y² + 1) is a square.
     */
    fun isOnCurve(publicKey: ByteArray): Boolean {
        require(publicKey.size == PUBLIC_KEY_BYTES) { "A public key is $PUBLIC_KEY_BYTES bytes" }
        val littleEndian = publicKey.reversedArray()
        littleEndian[0] = (littleEndian[0].toInt() and 0x7f).toByte()
        val y = BigInteger(1, littleEndian).mod(P)
        val ySquared = y.multiply(y).mod(P)
        val u = ySquared.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(ySquared).add(BigInteger.ONE).mod(P)
        val xSquared = u.multiply(v.modInverse(P)).mod(P)
        return xSquared.signum() == 0 || xSquared.modPow(LEGENDRE_EXPONENT, P) == BigInteger.ONE
    }
}
