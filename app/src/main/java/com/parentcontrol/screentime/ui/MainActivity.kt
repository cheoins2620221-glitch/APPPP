package com.parentcontrol.screentime.ui

import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.parentcontrol.screentime.R
import com.parentcontrol.screentime.data.AppDatabase
import com.parentcontrol.screentime.data.AppLimitEntity
import com.parentcontrol.screentime.manager.TimeBankManager
import kotlinx.coroutines.launch

/**
 * 부모가 직접 조작하는 화면.
 * - 권한 바로가기
 * - 시간 은행 현황 표시 (저축/인출/대출/리워드)
 * - 은행 설정(저축 상한, 인출 한도, 시간대, 대출 상환방식, 이자율, 환전세) - PIN 보호
 * - 대출 받기 / 즉시 상환
 * - 리워드 ↔ 시간 환전
 * - PIN 설정/변경
 * - 앱별 스크린타임 추가/삭제 - PIN 보호
 */
class MainActivity : AppCompatActivity() {

    private lateinit var dao: com.parentcontrol.screentime.data.AppDao
    private lateinit var timeBankManager: TimeBankManager
    private lateinit var adapter: AppLimitAdapter

    private var installedApps: List<Pair<String, String>> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        dao = AppDatabase.getInstance(applicationContext).appDao()
        timeBankManager = TimeBankManager(dao)

        setupPermissionButtons()
        setupInstalledAppsSpinner()
        setupAddLimitButton()
        setupLimitsList()
        setupBankSettings()
        setupLoanButtons()
        setupRewardButtons()
        setupPinButton()

        lifecycleScope.launch {
            timeBankManager.settleIfNeeded()
            refreshStatusLabels()
        }
    }

    // ---------- 권한 바로가기 ----------
    private fun setupPermissionButtons() {
        findViewById<Button>(R.id.btnOpenAccessibilitySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnOpenOverlaySettings).setOnClickListener {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
    }

    // ---------- 상태 표시 새로고침 ----------
    private suspend fun refreshStatusLabels() {
        val bank = dao.getBank() ?: return
        val today = timeBankManager.todayString()
        val withdrawnToday = if (bank.withdrawnDate == today) bank.withdrawnTodayMillis else 0L

        findViewById<TextView>(R.id.txtBankRemaining).text =
            "저축 잔액: ${bank.bankedMillis / 60_000L}분"
        findViewById<TextView>(R.id.txtWithdrawStatus).text =
            "오늘 인출: ${withdrawnToday / 60_000L}분 / 한도 ${bank.dailyWithdrawLimitMillis / 60_000L}분"
        findViewById<TextView>(R.id.txtLoanStatus).text =
            "대출 잔액: ${bank.loanBalanceMillis / 60_000L}분" +
                if (bank.loanBalanceMillis > 0) " (기한/차감일: ${bank.loanDueDate}, 방식: ${modeLabel(bank.loanRepayMode)})" else ""
        findViewById<TextView>(R.id.txtRewardStatus).text =
            "리워드 포인트: ${bank.rewardPoints}"
    }

    private fun modeLabel(mode: String) = if (mode == "DEADLINE") "기한제" else "미리쓰기제"

    // ---------- PIN 확인 후 실행 ----------
    private fun runWithPinCheck(action: () -> Unit) {
        lifecycleScope.launch {
            if (!timeBankManager.hasPin()) {
                action()
                return@launch
            }
            val input = EditText(this@MainActivity)
            input.hint = "PIN 입력"
            AlertDialog.Builder(this@MainActivity)
                .setTitle("부모 PIN 확인")
                .setView(input)
                .setPositiveButton("확인") { _, _ ->
                    lifecycleScope.launch {
                        if (timeBankManager.verifyPin(input.text.toString())) {
                            action()
                        } else {
                            Toast.makeText(this@MainActivity, "PIN이 틀렸어요", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    // ---------- 은행 설정 ----------
    private fun setupBankSettings() {
        lifecycleScope.launch {
            val bank = dao.getBank()
            if (bank != null) {
                findViewById<EditText>(R.id.inputMaxBankMinutes).setText((bank.maxBankMillis / 60_000L).toString())
                findViewById<EditText>(R.id.inputDailyWithdrawMinutes).setText((bank.dailyWithdrawLimitMillis / 60_000L).toString())
                findViewById<EditText>(R.id.inputWindowStartHour).setText((bank.bankWindowStartMinuteOfDay / 60).toString())
                findViewById<EditText>(R.id.inputWindowStartMinute).setText((bank.bankWindowStartMinuteOfDay % 60).toString())
                findViewById<EditText>(R.id.inputWindowEndHour).setText((bank.bankWindowEndMinuteOfDay / 60).toString())
                findViewById<EditText>(R.id.inputWindowEndMinute).setText((bank.bankWindowEndMinuteOfDay % 60).toString())
                findViewById<EditText>(R.id.inputInterestRate).setText(bank.loanInterestRatePercent.toString())
                findViewById<EditText>(R.id.inputExchangeTax).setText(bank.exchangeTaxPercent.toString())
                if (bank.loanRepayMode == "DEADLINE") {
                    findViewById<RadioGroup>(R.id.radioLoanMode).check(R.id.radioDeadline)
                }
            }
        }

        findViewById<Button>(R.id.btnSaveBankSettings).setOnClickListener {
            runWithPinCheck { saveBankSettings() }
        }
    }

    private fun saveBankSettings() {
        val maxMinutes = findViewById<EditText>(R.id.inputMaxBankMinutes).text.toString().toIntOrNull()
        val dailyWithdraw = findViewById<EditText>(R.id.inputDailyWithdrawMinutes).text.toString().toIntOrNull()
        val startHour = findViewById<EditText>(R.id.inputWindowStartHour).text.toString().toIntOrNull()
        val startMinute = findViewById<EditText>(R.id.inputWindowStartMinute).text.toString().toIntOrNull() ?: 0
        val endHour = findViewById<EditText>(R.id.inputWindowEndHour).text.toString().toIntOrNull()
        val endMinute = findViewById<EditText>(R.id.inputWindowEndMinute).text.toString().toIntOrNull() ?: 0
        val interestRate = findViewById<EditText>(R.id.inputInterestRate).text.toString().toIntOrNull() ?: 20
        val exchangeTax = findViewById<EditText>(R.id.inputExchangeTax).text.toString().toIntOrNull() ?: 10
        val loanMode = if (findViewById<RadioGroup>(R.id.radioLoanMode).checkedRadioButtonId == R.id.radioDeadline) "DEADLINE" else "PREPAY"

        if (maxMinutes == null || dailyWithdraw == null || startHour == null || endHour == null) {
            Toast.makeText(this, "값을 모두 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val startTotal = startHour * 60 + startMinute
        val endTotal = endHour * 60 + endMinute
        if (startTotal >= endTotal) {
            Toast.makeText(this, "종료 시간이 시작 시간보다 늦어야 해요", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            timeBankManager.updateParentSettings(
                maxBankMinutes = maxMinutes,
                windowStartMinuteOfDay = startTotal,
                windowEndMinuteOfDay = endTotal,
                dailyWithdrawLimitMinutes = dailyWithdraw,
                loanRepayMode = loanMode,
                loanInterestRatePercent = interestRate,
                exchangeTaxPercent = exchangeTax
            )
            Toast.makeText(this@MainActivity, "설정을 저장했어요", Toast.LENGTH_SHORT).show()
            refreshStatusLabels()
        }
    }

    // ---------- 대출 ----------
    private fun setupLoanButtons() {
        findViewById<Button>(R.id.btnTakeLoan).setOnClickListener {
            lifecycleScope.launch {
                val ok = timeBankManager.takeLoan(60 * 60_000L)
                if (ok) {
                    Toast.makeText(this@MainActivity, "1시간 대출을 받았어요. 저축 잔액에 바로 반영됩니다.", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "오늘 대출 가능 한도(1시간)를 이미 다 쓰셨어요", Toast.LENGTH_SHORT).show()
                }
                refreshStatusLabels()
            }
        }
        findViewById<Button>(R.id.btnRepayLoan).setOnClickListener {
            lifecycleScope.launch {
                val bank = dao.getBank()
                val repaid = timeBankManager.repayLoanNow(bank?.loanBalanceMillis ?: 0L)
                if (repaid > 0) {
                    Toast.makeText(this@MainActivity, "${repaid / 60_000L}분만큼 상환했어요", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(this@MainActivity, "상환할 대출이 없거나 저축 잔액이 부족해요", Toast.LENGTH_SHORT).show()
                }
                refreshStatusLabels()
            }
        }
    }

    // ---------- 리워드 환전 ----------
    private fun setupRewardButtons() {
        findViewById<Button>(R.id.btnTimeToReward).setOnClickListener {
            val amount = findViewById<EditText>(R.id.inputExchangeAmount).text.toString().toLongOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "수량을 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = timeBankManager.exchangeTimeToReward(amount)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "리워드로 전환했어요" else "저축 잔액이 부족해요",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatusLabels()
            }
        }
        findViewById<Button>(R.id.btnRewardToTime).setOnClickListener {
            val amount = findViewById<EditText>(R.id.inputExchangeAmount).text.toString().toLongOrNull()
            if (amount == null || amount <= 0) {
                Toast.makeText(this, "수량을 입력해 주세요", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            lifecycleScope.launch {
                val ok = timeBankManager.exchangeRewardToTime(amount)
                Toast.makeText(
                    this@MainActivity,
                    if (ok) "저축 시간으로 전환했어요" else "리워드 포인트가 부족해요",
                    Toast.LENGTH_SHORT
                ).show()
                refreshStatusLabels()
            }
        }
    }

    // ---------- PIN 설정 ----------
    private fun setupPinButton() {
        findViewById<Button>(R.id.btnSetPin).setOnClickListener {
            runWithPinCheck {
                val newPin = findViewById<EditText>(R.id.inputNewPin).text.toString()
                lifecycleScope.launch {
                    timeBankManager.setPin(newPin.ifBlank { null })
                    Toast.makeText(
                        this@MainActivity,
                        if (newPin.isBlank()) "PIN 잠금을 해제했어요" else "PIN을 설정했어요",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ---------- 설치된 앱 목록 ----------
    private fun setupInstalledAppsSpinner() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN, null).apply { addCategory(Intent.CATEGORY_LAUNCHER) }
        val resolveInfos = pm.queryIntentActivities(intent, 0)
        installedApps = resolveInfos
            .map { it.activityInfo.packageName to pm.getApplicationLabel(it.activityInfo.applicationInfo).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second }
            .map { it.second to it.first }

        val labels = installedApps.map { it.first }
        val spinner = findViewById<Spinner>(R.id.spinnerInstalledApps)
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    // ---------- 앱별 스크린타임 추가 ----------
    private fun setupAddLimitButton() {
        findViewById<Button>(R.id.btnAddLimit).setOnClickListener {
            runWithPinCheck { addLimit() }
        }
    }

    private fun addLimit() {
        val spinner = findViewById<Spinner>(R.id.spinnerInstalledApps)
        val selectedIndex = spinner.selectedItemPosition
        if (selectedIndex < 0 || installedApps.isEmpty()) {
            Toast.makeText(this, "앱을 선택해 주세요", Toast.LENGTH_SHORT).show()
            return
        }
        val (label, pkg) = installedApps[selectedIndex]
        val minutes = findViewById<EditText>(R.id.inputDailyLimitMinutes).text.toString().toIntOrNull()
        val graceSeconds = findViewById<EditText>(R.id.inputGraceSeconds).text.toString().toIntOrNull() ?: 120

        if (minutes == null || minutes <= 0) {
            Toast.makeText(this, "하루 한도(분)를 올바르게 입력해 주세요", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            dao.upsertLimit(
                AppLimitEntity(
                    packageName = pkg,
                    appLabel = label,
                    dailyLimitMinutes = minutes,
                    graceEnabled = true,
                    gracePeriodSeconds = graceSeconds
                )
            )
            Toast.makeText(this@MainActivity, "$label 설정을 추가했어요", Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- 설정된 앱 목록 표시 ----------
    private fun setupLimitsList() {
        val recyclerView = findViewById<RecyclerView>(R.id.recyclerLimits)
        recyclerView.layoutManager = LinearLayoutManager(this)
        adapter = AppLimitAdapter { limit ->
            runWithPinCheck {
                AlertDialog.Builder(this)
                    .setTitle("삭제")
                    .setMessage("${limit.appLabel} 설정을 삭제할까요?")
                    .setPositiveButton("삭제") { _, _ ->
                        lifecycleScope.launch { dao.deleteLimit(limit.packageName) }
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
        recyclerView.adapter = adapter

        lifecycleScope.launch {
            dao.observeAllLimits().collect { list -> adapter.submitList(list) }
        }
    }
}
