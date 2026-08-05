package dev.triplex.ui.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.TransformOrigin
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dev.triplex.ui.screens.CreateTaskScreen
import dev.triplex.ui.screens.DashboardScreen
import dev.triplex.ui.screens.EnrollmentScreen
import dev.triplex.ui.screens.VoiceCloneScreen
import dev.triplex.ui.theme.TriplexDesign

sealed class Screen(val route: String) {
    object Enrollment : Screen("enrollment")
    object Dashboard : Screen("dashboard")
    object CreateTask : Screen("create_task")
    object VoiceClone : Screen("voice_clone")
}

@Composable
fun TriplexNavigation(
    navController: NavHostController = rememberNavController(),
    isEnrolled: Boolean
) {
    val startDestination = if (isEnrolled) Screen.Dashboard.route else Screen.Enrollment.route
    val motion = TriplexDesign.motion
    
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            fadeIn(tween(motion.standardMillis)) +
                slideInHorizontally(tween(motion.standardMillis)) { width -> width / 10 }
        },
        exitTransition = {
            fadeOut(tween(motion.quickMillis)) +
                slideOutHorizontally(tween(motion.standardMillis)) { width -> -width / 16 }
        },
        popEnterTransition = {
            fadeIn(tween(motion.standardMillis)) +
                scaleIn(
                    animationSpec = tween(motion.standardMillis),
                    initialScale = motion.navigationScale
                )
        },
        // Navigation Compose 2.8 drives this transition interactively during
        // the system back gesture, revealing the destination underneath.
        popExitTransition = {
            fadeOut(tween(motion.standardMillis)) +
                scaleOut(
                    animationSpec = tween(motion.standardMillis),
                    targetScale = motion.navigationScale,
                    transformOrigin = TransformOrigin.Center
                )
        }
    ) {
        composable(Screen.Enrollment.route) {
            EnrollmentScreen(
                onEnrollmentComplete = {
                    navController.navigate(Screen.Dashboard.route) {
                        popUpTo(Screen.Enrollment.route) { inclusive = true }
                    }
                }
            )
        }
        
        composable(Screen.Dashboard.route) {
            DashboardScreen(
                onCreateTask = { navController.navigate(Screen.CreateTask.route) },
                onTaskSelected = { taskId ->
                    // Navigate to task detail - placeholder
                },
                onVoiceClone = { navController.navigate(Screen.VoiceClone.route) }
            )
        }

        composable(Screen.VoiceClone.route) {
            VoiceCloneScreen(onBack = { navController.popBackStack() })
        }
        
        composable(Screen.CreateTask.route) {
            CreateTaskScreen(
                onTaskCreated = { taskId ->
                    navController.popBackStack()
                    // Could navigate to active call screen here
                },
                onDismiss = { navController.popBackStack() }
            )
        }
    }
}
