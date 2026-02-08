package com.cryptohunt.app.ui.navigation

sealed class NavRoutes(val route: String) {
    // Onboarding
    data object Welcome : NavRoutes("welcome")
    data object WalletSetup : NavRoutes("wallet_setup")
    data object Permissions : NavRoutes("permissions")

    // Lobby
    data object GameBrowser : NavRoutes("game_browser")
    data object Deposit : NavRoutes("deposit")
    data object GameDetail : NavRoutes("game_detail/{gameId}") {
        fun withId(gameId: String) = "game_detail/$gameId"
    }
    data object RegisteredDetail : NavRoutes("registered_detail/{gameId}") {
        fun withId(gameId: String) = "registered_detail/$gameId"
    }
    data object CheckIn : NavRoutes("check_in/{gameId}") {
        fun withId(gameId: String) = "check_in/$gameId"
    }
    data object CheckInCamera : NavRoutes("check_in_camera")
    data object GameHistoryDetail : NavRoutes("game_history/{index}") {
        fun withIndex(index: Int) = "game_history/$index"
    }

    // Game
    data object MainGame : NavRoutes("main_game")
    data object HuntCamera : NavRoutes("hunt_camera")
    data object Map : NavRoutes("map")
    data object Intel : NavRoutes("intel")

    // Post-game
    data object Eliminated : NavRoutes("eliminated")
    data object Results : NavRoutes("results")
}
