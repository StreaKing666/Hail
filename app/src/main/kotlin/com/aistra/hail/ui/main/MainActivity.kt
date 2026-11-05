package com.aistra.hail.ui.main

import android.os.Bundle
import android.view.Menu
import android.widget.ProgressBar
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.aistra.hail.app.AppDataAccess
import com.aistra.hail.app.RecoveryStatus
import com.aistra.hail.app.RecoveryUiPolicy
import com.aistra.hail.app.RecoveryUiAction
import com.aistra.hail.HailApp
import kotlinx.coroutines.launch
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.MenuCompat
import androidx.core.view.WindowCompat
import androidx.core.view.isVisible
import androidx.navigation.NavController
import androidx.navigation.NavDestination
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.AppBarConfiguration
import androidx.navigation.ui.setupActionBarWithNavController
import androidx.navigation.ui.setupWithNavController
import com.aistra.hail.R
import com.aistra.hail.app.HailData
import com.aistra.hail.databinding.ActivityMainBinding
import com.aistra.hail.extensions.*
import com.aistra.hail.utils.HPolicy
import com.aistra.hail.utils.HUI
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.navigation.NavigationBarView
import com.google.android.material.navigationrail.NavigationRailView
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class MainActivity : AppCompatActivity(), NavController.OnDestinationChangedListener {
    private var recoveryDialog: androidx.appcompat.app.AlertDialog? = null
    private var recoveryDialogState: RecoveryStatus? = null
    lateinit var fab: ExtendedFloatingActionButton
    lateinit var appbar: AppBarLayout
    private var bottomNavigation: BottomNavigationView? = null
    private var navigationRail: NavigationRailView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // FragmentManager must always find restored NavHost/DialogFragment containers,
        // including process recreation while journal recovery is still running.
        val binding = initView()
        binding.root.isVisible = false
        val policy = RecoveryUiPolicy(AppDataAccess.status.value)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                AppDataAccess.status.collect { state ->
                    when (policy.onStatus(state)) {
                        RecoveryUiAction.PROGRESS -> {
                            if (recoveryDialogState != state) {
                                recoveryDialog?.dismiss()
                                recoveryDialogState = state
                                recoveryDialog = MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(R.string.recovery_checking)
                                    .setView(ProgressBar(this@MainActivity)).setCancelable(false).show()
                            }
                        }
                        RecoveryUiAction.FAILURE -> {
                            if (recoveryDialogState != state) {
                                recoveryDialog?.dismiss()
                                recoveryDialogState = state
                                recoveryDialog = MaterialAlertDialogBuilder(this@MainActivity)
                                    .setTitle(R.string.recovery_failed_title)
                                    .setMessage(R.string.recovery_failed_message)
                                    .setCancelable(false)
                                    .setPositiveButton(R.string.recovery_retry) { _, _ ->
                                        (application as HailApp).retryRecovery()
                                    }
                                    .setNegativeButton(R.string.action_exit) { _, _ -> finish() }.show()
                            }
                        }
                        RecoveryUiAction.RECREATE -> { recoveryDialog?.dismiss(); recreate() }
                        RecoveryUiAction.INITIALIZE -> { recoveryDialog?.dismiss(); initializeContent(binding) }
                        RecoveryUiAction.NONE -> Unit
                    }
                }
            }
        }
    }

    private fun initializeContent(binding: ActivityMainBinding) {
        if (!HailData.biometricLogin || BiometricManager.from(this)
                .canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL) != BiometricManager.BIOMETRIC_SUCCESS
        ) { binding.root.isVisible = true; return }
        binding.root.isVisible = false
        val biometricPrompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    HUI.showToast(errString)
                    finishAndRemoveTask()
                }

                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    binding.root.isVisible = true
                }
            })
        val promptInfo = BiometricPrompt.PromptInfo.Builder().setTitle(getString(R.string.action_biometric))
            .setSubtitle(getString(R.string.msg_biometric)).setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        biometricPrompt.authenticate(promptInfo)
    }

    override fun onDestroy() {
        recoveryDialog?.dismiss()
        super.onDestroy()
    }

    private fun initView() = ActivityMainBinding.inflate(layoutInflater).apply {
        setContentView(root)
        setSupportActionBar(appBarMain.toolbar)
        fab = appBarMain.fab
        appbar = appBarMain.appBarLayout

        val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        navController.addOnDestinationChangedListener(this@MainActivity)
        val appBarConfiguration = AppBarConfiguration.Builder(
            R.id.nav_home, R.id.nav_apps, R.id.nav_settings, R.id.nav_about
        ).build()
        setupActionBarWithNavController(navController, appBarConfiguration)
        bottomNavigation = bottomNav
        navigationRail = navRail
        bottomNav?.setupWithNavController(navController)
        navRail?.setupWithNavController(navController)
        applyNavigationLayout()

        val isRtl = isRtl
        val isLandscape = isLandscape
        appBarMain.appBarLayout.applyDefaultInsetter {
            paddingRelative(isRtl, start = !isLandscape, end = true, top = true)
        }
        bottomNav?.applyDefaultInsetter { paddingRelative(isRtl, start = true, end = true, bottom = true) }
        navRail?.applyDefaultInsetter { paddingRelative(isRtl, start = true, top = true, bottom = true) }
        fab.applyDefaultInsetter { marginRelative(isRtl, end = true, bottom = isLandscape) }
    }

    fun applyNavigationLayout(layout: String = HailData.navigationLayout) {
        val useRail = when (layout) {
            HailData.NAV_RAIL, HailData.NAV_RAIL_COMPACT -> true
            HailData.NAV_BOTTOM, HailData.NAV_BOTTOM_COMPACT -> false
            else -> isLandscape
        }
        val compact = layout == HailData.NAV_BOTTOM_COMPACT || layout == HailData.NAV_RAIL_COMPACT
        bottomNavigation?.isVisible = !useRail
        navigationRail?.isVisible = useRail
        val labelMode = if (compact) {
            NavigationBarView.LABEL_VISIBILITY_UNLABELED
        } else {
            NavigationBarView.LABEL_VISIBILITY_LABELED
        }
        bottomNavigation?.labelVisibilityMode = labelMode
        navigationRail?.labelVisibilityMode = labelMode
    }

    override fun onCreateOptionsMenu(menu: Menu?): Boolean {
        menu?.let { MenuCompat.setGroupDividerEnabled(it, true) }
        return super.onCreateOptionsMenu(menu)
    }

    fun ownerRemoveDialog() {
        MaterialAlertDialogBuilder(this).setTitle(R.string.title_remove_owner).setMessage(R.string.msg_remove_owner)
            .setPositiveButton(R.string.action_continue) { _, _ ->
                HPolicy.setOrganizationName()
                HPolicy.removeDeviceOwner()
            }.setNegativeButton(android.R.string.cancel, null).show()
    }

    /* override fun onStop() {
        super.onStop()
        if (HailData.biometricLogin) finishAndRemoveTask()
    } */

    override fun onDestinationChanged(
        controller: NavController, destination: NavDestination, arguments: Bundle?
    ) {
        fab.tag = destination.id == R.id.nav_home
        if (fab.tag == true) fab.show() else fab.hide()
    }
}
