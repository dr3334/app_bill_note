package dev.inkledger.app

import android.os.Bundle
import android.view.View
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dev.inkledger.app.databinding.ActivityMainBinding
import dev.inkledger.app.ui.*
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    val model: LedgerViewModel by viewModels()
    private lateinit var binding: ActivityMainBinding
    private var selected = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        selected = savedInstanceState?.getInt("tab") ?: 0
        val dark = resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK == android.content.res.Configuration.UI_MODE_NIGHT_YES
        WindowCompat.getInsetsController(window, binding.root).apply {
            isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
        }
        binding.notice.background = shape(color(R.color.ink_panel), 24, true)
        binding.noticeClose.setOnClickListener { model.notice.value = null }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val safeWidth = resources.displayMetrics.widthPixels - bars.left - bars.right
            val gutter = maxOf(dp(20), (safeWidth - dp(480)) / 2)
            binding.pageContainer.setPadding(bars.left, bars.top, bars.right, 0)
            binding.dock.updateLayoutParams<FrameLayout.LayoutParams> {
                leftMargin = bars.left + gutter; rightMargin = bars.right + gutter; bottomMargin = bars.bottom + dp(12)
            }
            binding.notice.updateLayoutParams<FrameLayout.LayoutParams> {
                topMargin = bars.top + dp(8); leftMargin = bars.left + dp(24); rightMargin = bars.right + dp(24)
            }
            binding.root.post { applyListClearance() }
            insets
        }
        binding.dock.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> applyListClearance() }
        binding.dock.onSelect = { select(it) }
        select(selected, false)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (selected != 0) select(0) else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(model.state, model.notice) { state, notice -> state to notice }.collect { (state, notice) ->
                    binding.dock.reducedMotion = state.reducedMotion
                    if (!InkMotion.enabled(state.reducedMotion)) binding.dock.select(selected, false)
                    if (notice == null) { binding.notice.animate().cancel(); binding.notice.visibility = View.GONE }
                    else {
                        val wasHidden = binding.notice.visibility != View.VISIBLE
                        binding.noticeText.text = notice.text + (notice.amountMinor?.let { " · ¥${money(it, state.hideAmounts)}" } ?: "")
                        binding.noticeText.setTextColor(color(if (notice.error) R.color.ink_error else R.color.ink_text))
                        binding.notice.visibility = View.VISIBLE
                        if (wasHidden) InkMotion.enter(binding.notice, state.reducedMotion, -6)
                        else if (!InkMotion.enabled(state.reducedMotion)) { binding.notice.animate().cancel(); binding.notice.alpha = 1f; binding.notice.translationY = 0f }
                    }
                }
            }
        }
    }

    private fun applyListClearance() {
        val clearance = (binding.root.height - binding.dock.top).coerceAtLeast(dp(100)) + dp(20)
        supportFragmentManager.fragments.filterIsInstance<InkScreenFragment>().forEach { it.setBottomClearance(clearance) }
    }

    fun select(index: Int, animate: Boolean = true) {
        val tag = "screen-$index"
        val current = supportFragmentManager.findFragmentByTag(tag)
        val target: Fragment = current ?: when (index) { 1 -> StatsFragment(); 2 -> CaptureFragment(); 3 -> SettingsFragment(); else -> LedgerFragment() }
        val transaction = supportFragmentManager.beginTransaction().setReorderingAllowed(true)
        supportFragmentManager.fragments.filterIsInstance<InkScreenFragment>().forEach {
            if (it != target) { transaction.hide(it); transaction.setMaxLifecycle(it, Lifecycle.State.STARTED) }
        }
        if (current == null) transaction.add(R.id.page_container, target, tag) else transaction.show(target)
        transaction.setMaxLifecycle(target, Lifecycle.State.RESUMED).commitNow()
        selected = index
        binding.dock.select(index, animate)
        applyListClearance()
        target.view?.let { if (animate) InkMotion.enter(it, model.state.value.reducedMotion) }
    }

    override fun onResume() {
        super.onResume()
        model.refreshCapture()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (UsageGuideSheet.needsFirstRun(this) && !supportFragmentManager.isStateSaved &&
            supportFragmentManager.fragments.none { it is InkSheet && it.isAdded }) {
            UsageGuideSheet.firstRun().show(supportFragmentManager, "usage-guide")
        }
    }

    override fun onSaveInstanceState(outState: Bundle) { outState.putInt("tab", selected); super.onSaveInstanceState(outState) }
}
