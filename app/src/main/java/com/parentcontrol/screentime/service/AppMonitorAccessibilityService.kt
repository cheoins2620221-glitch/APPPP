package com.parentcontrol.screentime.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import com.parentcontrol.screentime.data.AppDatabase
import com.parentcontrol.screentime.data.AppUsageEntity
import com.parentcontrol.screentime.manager.TimeBankManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AppMonitorAccessibilityService : AccessibilityService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var dao: com.parentcontrol.screentime.data.AppDao
    private lateinit var timeBankManager: TimeBankManager

    private var currentForegroundPackage: String? = null
    private var lastTickAt: Long = 0L
    private val gracedPackagesToday = mutableSetOf<String>()

    private val excludedPackages by lazy {
        setOf(packageName, "com.android.systemui")
    }

    private val tickRunnable = object : Runnable {
        override fun run() {
            evaluateCurrentForegroundApp()
            handler.postDelayed(this, TICK_INTERVAL_MS)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        dao = AppDatabase.getInstance(applicationContext).appDao()
        timeBankManager = TimeBankManager(dao)
        scope.launch { timeBankManager.settleIfNeeded() }
        lastTickAt = System.currentTimeMillis()
        handler.post(tickRunnable)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event.packageName?.toString() ?: return
            if (pkg in excludedPackages) return
            if (pkg != currentForegroundPackage) {
                currentForegroundPackage = pkg
                evaluateCurrentForegroundApp()
            }
        }
    }

    override fun onInterrupt() {}

    private fun evaluateCurrentForegroundApp() {
        val pkg = currentForegroundPackage ?: return
        val now = System.currentTimeMillis()
        val elapsed = (now - lastTickAt).coerceAtLeast(0L)
        lastTickAt = now

        scope.launch {
            timeBankManager.settleIfNeeded(now)

            val limit = dao.getLimit(pkg) ?: return@launch
            if (!limit.isMonitored) return@launch

            val today = timeBankManager.todayString(now)
            val usage = dao.getUsage(pkg, today) ?: AppUsageEntity(pkg, today)

            val updatedUsage = usage.copy(usedMillis = usage.usedMillis + elapsed)
            dao.upsertUsage(updatedUsage)

            val limitMillis = limit.dailyLimitMinutes * 60_000L
            val overBy = updatedUsage.usedMillis - limitMillis

            if (overBy <= 0) return@launch

            val bankWindowOpen = timeBankManager.isWithinBankWindow(now)
            val bankRemaining = timeBankManager.remainingWithdrawableMillis(now)
            if (bankWindowOpen && bankRemaining > 0) {
                val consumed = timeBankManager.consumeBank(overBy.coerceAtMost(bankRemaining), now)
                if (consumed > 0) dao.addBankUsage(pkg, today, consumed)
                if (consumed >= overBy) return@launch
            }

            handleLimitExceeded(pkg, limit.appLabel, limit.graceEnabled,
                limit.gracePeriodSeconds, today)
        }
    }

    private suspend fun handleLimitExceeded(
        pkg: String,
        appLabel: String,
        graceEnabled: Boolean,
        gracePeriodSeconds: Int,
        today: String
    ) {
        val usage = dao.getUsage(pkg, today)
        val graceAlreadyUsed = usage?.graceUsedAt != null || pkg in gracedPackagesToday

        if (graceEnabled && !graceAlreadyUsed) {
            gracedPackagesToday.add(pkg)
            dao.markGraceUsed(pkg, today, System.currentTimeMillis())
            startOverlay(BlockOverlayService.MODE_GRACE, appLabel, gracePeriodSeconds)
            handler.postDelayed({
                forceExitToHome(pkg)
            }, gracePeriodSeconds * 1000L)
        } else {
            forceExitToHome(pkg)
        }
    }

    private fun forceExitToHome(pkg: String) {
        if (currentForegroundPackage != pkg) return
        performGlobalAction(GLOBAL_ACTION_HOME)
        startOverlay(BlockOverlayService.MODE_BLOCK, resolveAppLabel(pkg), 0)
    }

    private fun startOverlay(mode: String, appLabel: String, graceSeconds: Int) {
        val intent = Intent(this, BlockOverlayService::class.java).apply {
            putExtra(BlockOverlayService.EXTRA_MODE, mode)
            putExtra(BlockOverlayService.EXTRA_APP_LABEL, appLabel)
            putExtra(BlockOverlayService.EXTRA_GRACE_SECONDS, graceSeconds)
        }
        startService(intent)
    }

    private fun resolveAppLabel(pkg: String): String {
        return try {
            val pm = packageManager
            val appInfo = pm.getApplicationInfo(pkg, PackageManager.GET_META_DATA)
            pm.getApplicationLabel(appInfo).toString()
        } catch (_: PackageManager.NameNotFoundException) {
            pkg
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(tickRunnable)
    }

    companion object {
        private const val TICK_INTERVAL_MS = 1000L
    }
}
