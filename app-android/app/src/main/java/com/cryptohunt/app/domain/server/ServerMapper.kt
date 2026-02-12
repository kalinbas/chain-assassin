package com.cryptohunt.app.domain.server

import com.cryptohunt.app.domain.model.GameConfig
import com.cryptohunt.app.domain.model.ZoneShrink
import org.web3j.utils.Convert
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Maps ServerGame (from REST API) to app-layer GameConfig.
 */
object ServerMapper {

    fun toGameConfig(game: ServerGame): GameConfig {
        val entryFeeWei = BigInteger(game.entryFee)
        val entryFeeEth = Convert.fromWei(
            BigDecimal(entryFeeWei),
            Convert.Unit.ETHER
        ).toDouble()

        val baseRewardEth = Convert.fromWei(
            BigDecimal(BigInteger(game.baseReward)),
            Convert.Unit.ETHER
        ).toDouble()

        val initialRadius = if (game.zoneShrinks.isNotEmpty()) {
            game.zoneShrinks[0].radiusMeters.toDouble()
        } else {
            1000.0
        }

        return GameConfig(
            id = game.gameId.toString(),
            name = game.title,
            entryFee = entryFeeEth,
            minPlayers = game.minPlayers,
            maxPlayers = game.maxPlayers,
            zoneCenterLat = game.centerLat / 1_000_000.0,
            zoneCenterLng = game.centerLng / 1_000_000.0,
            meetingLat = game.meetingLat / 1_000_000.0,
            meetingLng = game.meetingLng / 1_000_000.0,
            initialRadiusMeters = initialRadius,
            shrinkSchedule = game.zoneShrinks.drop(1).map { s ->
                ZoneShrink(
                    atMinute = s.atSecond / 60,
                    newRadiusMeters = s.radiusMeters.toDouble()
                )
            },
            durationMinutes = (game.maxDuration / 60).toInt(),
            checkInDurationMinutes = 10,
            bps1st = game.bps1st,
            bps2nd = game.bps2nd,
            bps3rd = game.bps3rd,
            bpsKills = game.bpsKills,
            entryFeeWei = entryFeeWei,
            baseReward = baseRewardEth,
            registrationDeadline = game.registrationDeadline * 1000,
            gameDate = game.gameDate * 1000,
            pregameDurationMinutes = 3
        )
    }
}
