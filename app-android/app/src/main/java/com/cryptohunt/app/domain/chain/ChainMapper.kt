package com.cryptohunt.app.domain.chain

import com.cryptohunt.app.domain.model.GameConfig
import com.cryptohunt.app.domain.model.ZoneShrink
import org.web3j.utils.Convert
import java.math.BigDecimal

object ChainMapper {

    fun toGameConfig(
        gameId: Int,
        config: OnChainGameConfig,
        shrinks: List<OnChainZoneShrink>
    ): GameConfig {
        val entryFeeEth = Convert.fromWei(
            BigDecimal(config.entryFee),
            Convert.Unit.ETHER
        ).toDouble()

        val initialRadius = if (shrinks.isNotEmpty()) {
            shrinks[0].radiusMeters.toDouble()
        } else {
            1000.0
        }

        return GameConfig(
            id = gameId.toString(),
            name = config.title,
            entryFee = entryFeeEth,
            minPlayers = config.minPlayers,
            maxPlayers = config.maxPlayers,
            zoneCenterLat = config.centerLat / 1_000_000.0,
            zoneCenterLng = config.centerLng / 1_000_000.0,
            meetingLat = config.meetingLat / 1_000_000.0,
            meetingLng = config.meetingLng / 1_000_000.0,
            initialRadiusMeters = initialRadius,
            shrinkSchedule = shrinks.drop(1).map { s ->
                ZoneShrink(
                    atMinute = s.atSecond / 60,
                    newRadiusMeters = s.radiusMeters.toDouble()
                )
            },
            durationMinutes = (config.maxDuration / 60).toInt(),
            checkInDurationMinutes = 10,
            bps1st = config.bps1st,
            bps2nd = config.bps2nd,
            bps3rd = config.bps3rd,
            bpsKills = config.bpsKills,
            entryFeeWei = config.entryFee,
            registrationDeadline = config.registrationDeadline * 1000,
            gameDate = config.gameDate * 1000,
            pregameDurationMinutes = 3
        )
    }
}
