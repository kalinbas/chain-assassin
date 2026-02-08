package com.cryptohunt.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cryptohunt.app.ui.screens.game.CheckInCameraScreen
import com.cryptohunt.app.ui.screens.game.CheckInScreen
import com.cryptohunt.app.ui.screens.game.HuntCameraScreen
import com.cryptohunt.app.ui.screens.game.IntelScreen
import com.cryptohunt.app.ui.screens.game.MainGameScreen
import com.cryptohunt.app.ui.screens.game.MapScreen
import com.cryptohunt.app.ui.screens.lobby.DepositScreen
import com.cryptohunt.app.ui.screens.lobby.GameBrowserScreen
import com.cryptohunt.app.ui.screens.lobby.GameDetailScreen
import com.cryptohunt.app.ui.screens.lobby.GameHistoryDetailScreen
import com.cryptohunt.app.ui.screens.lobby.RegisteredDetailScreen
import com.cryptohunt.app.ui.screens.onboarding.PermissionsScreen
import com.cryptohunt.app.ui.screens.onboarding.WalletSetupScreen
import com.cryptohunt.app.ui.screens.onboarding.WelcomeScreen
import com.cryptohunt.app.ui.screens.postgame.EliminatedScreen
import com.cryptohunt.app.ui.screens.postgame.ResultsScreen

private val enterSlide: EnterTransition = slideInHorizontally(initialOffsetX = { it }) + fadeIn()
private val exitSlide: ExitTransition = slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut()
private val popEnter: EnterTransition = slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn()
private val popExit: ExitTransition = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()

@Composable
fun AppNavHost() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = NavRoutes.Welcome.route,
        enterTransition = { enterSlide },
        exitTransition = { exitSlide },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit }
    ) {
        // Onboarding
        composable(NavRoutes.Welcome.route) {
            WelcomeScreen(
                onCreateWallet = { navController.navigate(NavRoutes.WalletSetup.route) },
                onImportWallet = { navController.navigate(NavRoutes.WalletSetup.route + "?import=true") }
            )
        }

        composable(
            route = NavRoutes.WalletSetup.route + "?import={import}",
            arguments = listOf(navArgument("import") { type = NavType.BoolType; defaultValue = false })
        ) { backStackEntry ->
            val isImport = backStackEntry.arguments?.getBoolean("import") ?: false
            WalletSetupScreen(
                isImport = isImport,
                onComplete = {
                    navController.navigate(NavRoutes.Permissions.route) {
                        popUpTo(NavRoutes.Welcome.route) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.Permissions.route) {
            PermissionsScreen(
                onAllGranted = {
                    navController.navigate(NavRoutes.GameBrowser.route) {
                        popUpTo(NavRoutes.Permissions.route) { inclusive = true }
                    }
                }
            )
        }

        // Lobby
        composable(NavRoutes.GameBrowser.route) {
            GameBrowserScreen(
                onGameClick = { gameId ->
                    navController.navigate(NavRoutes.GameDetail.withId(gameId))
                },
                onRegisteredGameClick = { gameId ->
                    navController.navigate(NavRoutes.RegisteredDetail.withId(gameId))
                },
                onActiveGameClick = {
                    navController.navigate(NavRoutes.MainGame.route)
                },
                onEliminatedGameClick = {
                    navController.navigate(NavRoutes.Eliminated.route)
                },
                onHistoryGameClick = { index ->
                    navController.navigate(NavRoutes.GameHistoryDetail.withIndex(index))
                },
                onDeposit = {
                    navController.navigate(NavRoutes.Deposit.route)
                }
            )
        }

        composable(NavRoutes.Deposit.route) {
            DepositScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.GameDetail.route,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId") ?: ""
            GameDetailScreen(
                gameId = gameId,
                onJoinGame = { id ->
                    navController.navigate(NavRoutes.RegisteredDetail.withId(id)) {
                        popUpTo(NavRoutes.GameBrowser.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.RegisteredDetail.route,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId") ?: ""
            RegisteredDetailScreen(
                gameId = gameId,
                onCheckInStart = { id ->
                    navController.navigate(NavRoutes.CheckIn.withId(id)) {
                        popUpTo(NavRoutes.GameBrowser.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.CheckIn.route,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId") ?: ""
            CheckInScreen(
                gameId = gameId,
                onScanPlayer = {
                    navController.navigate(NavRoutes.CheckInCamera.route)
                },
                onGameStart = {
                    navController.navigate(NavRoutes.MainGame.route) {
                        popUpTo(NavRoutes.GameBrowser.route) { inclusive = true }
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.CheckInCamera.route) {
            CheckInCameraScreen(
                onVerified = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.GameHistoryDetail.route,
            arguments = listOf(navArgument("index") { type = NavType.IntType })
        ) { backStackEntry ->
            val index = backStackEntry.arguments?.getInt("index") ?: 0
            GameHistoryDetailScreen(
                historyIndex = index,
                onBack = { navController.popBackStack() }
            )
        }

        // Game
        composable(NavRoutes.MainGame.route) {
            MainGameScreen(
                onHunt = { navController.navigate(NavRoutes.HuntCamera.route) },
                onMap = { navController.navigate(NavRoutes.Map.route) },
                onIntel = { navController.navigate(NavRoutes.Intel.route) },
                onEliminated = {
                    navController.navigate(NavRoutes.Eliminated.route) {
                        popUpTo(NavRoutes.MainGame.route) { inclusive = true }
                    }
                },
                onGameEnd = {
                    navController.navigate(NavRoutes.Results.route) {
                        popUpTo(NavRoutes.MainGame.route) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.HuntCamera.route) {
            HuntCameraScreen(
                onKillConfirmed = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.Map.route) {
            MapScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.Intel.route) {
            IntelScreen(onBack = { navController.popBackStack() })
        }

        // Post-game
        composable(NavRoutes.Eliminated.route) {
            EliminatedScreen(
                onSpectate = {
                    navController.navigate(NavRoutes.MainGame.route)
                },
                onExit = {
                    navController.navigate(NavRoutes.Results.route) {
                        popUpTo(NavRoutes.Eliminated.route) { inclusive = true }
                    }
                }
            )
        }

        composable(NavRoutes.Results.route) {
            ResultsScreen(
                onPlayAgain = {
                    navController.navigate(NavRoutes.GameBrowser.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
            )
        }
    }
}
