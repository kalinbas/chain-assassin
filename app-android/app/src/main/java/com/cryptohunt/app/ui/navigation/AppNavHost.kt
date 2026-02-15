package com.cryptohunt.app.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import com.cryptohunt.app.ui.components.DebugStatusOverlay
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.cryptohunt.app.ui.screens.game.CheckInCameraScreen
import com.cryptohunt.app.ui.screens.game.CheckInScreen
import com.cryptohunt.app.ui.screens.game.PregameScreen
import com.cryptohunt.app.ui.screens.game.HuntCameraScreen
import com.cryptohunt.app.ui.screens.game.PhotoCaptureScreen
import com.cryptohunt.app.ui.screens.game.IntelScreen
import com.cryptohunt.app.ui.screens.game.MainGameScreen
import com.cryptohunt.app.ui.screens.game.MapScreen
import com.cryptohunt.app.ui.screens.lobby.DepositScreen
import com.cryptohunt.app.ui.screens.lobby.GameBrowserScreen
import com.cryptohunt.app.ui.screens.lobby.GameDetailScreen
import com.cryptohunt.app.ui.screens.lobby.GameHistoryDetailScreen
import com.cryptohunt.app.ui.screens.lobby.RegisteredDetailScreen
import com.cryptohunt.app.ui.screens.onboarding.DeviceReadinessScreen
import com.cryptohunt.app.ui.screens.onboarding.PermissionsScreen
import com.cryptohunt.app.ui.screens.onboarding.ScanDebugScreen
import com.cryptohunt.app.ui.screens.onboarding.ScanDebugResultScreen
import com.cryptohunt.app.ui.screens.onboarding.WalletSetupScreen
import com.cryptohunt.app.ui.screens.onboarding.WelcomeScreen
import com.cryptohunt.app.ui.screens.postgame.EliminatedScreen
import com.cryptohunt.app.ui.screens.postgame.ResultsScreen
import com.cryptohunt.app.ui.viewmodel.ScanDebugViewModel
import com.cryptohunt.app.ui.viewmodel.WalletViewModel

private val enterSlide: EnterTransition = slideInHorizontally(initialOffsetX = { it }) + fadeIn()
private val exitSlide: ExitTransition = slideOutHorizontally(targetOffsetX = { -it / 3 }) + fadeOut()
private val popEnter: EnterTransition = slideInHorizontally(initialOffsetX = { -it / 3 }) + fadeIn()
private val popExit: ExitTransition = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()

@Composable
fun AppNavHost(
    walletViewModel: WalletViewModel = hiltViewModel()
) {
    val navController = rememberNavController()

    // Compute start destination only once — don't react to wallet state changes
    // (creating a wallet during onboarding must not skip the setup screen)
    val startDestination = remember {
        if (walletViewModel.walletState.value.isConnected) {
            NavRoutes.GameBrowser.route
        } else {
            NavRoutes.Welcome.route
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Debug status dots — always visible on top of all screens
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 4.dp)
                .zIndex(100f)
        ) {
            DebugStatusOverlay()
        }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = { enterSlide },
        exitTransition = { exitSlide },
        popEnterTransition = { popEnter },
        popExitTransition = { popExit }
    ) {
        // Onboarding
        composable(NavRoutes.Welcome.route) {
            WelcomeScreen(
                onCreateWallet = { navController.navigate(NavRoutes.WalletSetup.route) },
                onImportWallet = { navController.navigate(NavRoutes.WalletSetup.route + "?import=true") },
                onDeviceReadiness = { navController.navigate(NavRoutes.DeviceReadiness.route) },
                onScanDebug = { navController.navigate(NavRoutes.ScanDebug.route) }
            )
        }

        composable(NavRoutes.DeviceReadiness.route) {
            DeviceReadinessScreen(
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.ScanDebug.route) {
            ScanDebugScreen(
                onBack = { navController.popBackStack() },
                onShowResult = { navController.navigate(NavRoutes.ScanDebugResult.route) }
            )
        }

        composable(NavRoutes.ScanDebugResult.route) {
            val scanDebugEntry = remember { navController.getBackStackEntry(NavRoutes.ScanDebug.route) }
            val scanDebugViewModel: ScanDebugViewModel = hiltViewModel(scanDebugEntry)
            ScanDebugResultScreen(
                onBackToScanner = { navController.popBackStack() },
                viewModel = scanDebugViewModel
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
                onBack = { navController.popBackStack() },
                onLogout = {
                    navController.navigate(NavRoutes.Welcome.route) {
                        popUpTo(0) { inclusive = true }
                    }
                }
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
                onViewRegistration = { id ->
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
                onPregameStart = { id ->
                    navController.navigate(NavRoutes.Pregame.withId(id)) {
                        popUpTo(NavRoutes.GameBrowser.route)
                    }
                },
                onGameStart = {
                    navController.navigate(NavRoutes.MainGame.route) {
                        popUpTo(NavRoutes.GameBrowser.route) { inclusive = true }
                    }
                },
                onEliminated = {
                    navController.navigate(NavRoutes.Eliminated.route) {
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
                onPregame = {
                    navController.navigate(NavRoutes.Pregame.withId(gameId)) {
                        popUpTo(NavRoutes.GameBrowser.route)
                    }
                },
                onGameStart = {
                    navController.navigate(NavRoutes.MainGame.route) {
                        popUpTo(NavRoutes.GameBrowser.route) { inclusive = true }
                    }
                },
                onEliminated = {
                    navController.navigate(NavRoutes.Eliminated.route) {
                        popUpTo(NavRoutes.GameBrowser.route)
                    }
                },
                onBack = { navController.popBackStack() }
            )
        }

        composable(
            route = NavRoutes.Pregame.route,
            arguments = listOf(navArgument("gameId") { type = NavType.StringType })
        ) { backStackEntry ->
            val gameId = backStackEntry.arguments?.getString("gameId") ?: ""
            PregameScreen(
                gameId = gameId,
                onGameStart = {
                    navController.navigate(NavRoutes.MainGame.route) {
                        popUpTo(NavRoutes.GameBrowser.route) { inclusive = true }
                    }
                },
                onEliminated = {
                    navController.navigate(NavRoutes.Eliminated.route) {
                        popUpTo(NavRoutes.GameBrowser.route)
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
                onScan = { navController.navigate(NavRoutes.HuntCamera.route) },
                onPhoto = { navController.navigate(NavRoutes.PhotoCapture.route) },
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

        composable(NavRoutes.PhotoCapture.route) {
            PhotoCaptureScreen(
                onPhotoTaken = { navController.popBackStack() },
                onBack = { navController.popBackStack() }
            )
        }

        composable(NavRoutes.Map.route) {
            MapScreen(onBack = { navController.popBackStack() })
        }

        composable(NavRoutes.Intel.route) {
            IntelScreen(
                onBack = { navController.popBackStack() },
                onNavigateToMap = { navController.navigate(NavRoutes.Map.route) }
            )
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
    } // Box
}
