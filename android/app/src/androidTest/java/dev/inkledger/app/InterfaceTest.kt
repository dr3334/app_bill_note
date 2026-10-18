package dev.inkledger.app

import android.content.Context
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.hamcrest.Matchers.allOf
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.platform.app.InstrumentationRegistry
import androidx.recyclerview.widget.RecyclerView
import dev.inkledger.app.ui.InkRows
import dev.inkledger.app.ui.UsageGuideSheet

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @Before fun resetPreferences() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().putBoolean("private", false).putBoolean("reduced", false).putInt("theme", -1).commit()
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("usage-guide", Context.MODE_PRIVATE)
            .edit().putBoolean("seen_v3", true).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(-1) }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            (ApplicationProvider.getApplicationContext<Context>() as InkApplication).captureManager.setCaptureConsent(false)
        }
    }

    @Test fun dockNavigationAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("统计")).perform(click())
            onView(withText("看清每一笔")).check(matches(isDisplayed()))
            onView(withContentDescription("自动")).perform(click())
            onView(withText("选择应用，再开启读取")).check(matches(isDisplayed()))
            onView(withContentDescription("设置")).perform(click())
            onView(withText("按你的习惯")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("记一笔")).check(matches(isDisplayed()))
        }
    }

    @Test fun invalidAmountStaysInEditor() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("手动记一笔")).perform(click())
            onView(withHint("商家或收支说明")).perform(replaceText("界面测试"), closeSoftKeyboard())
            onView(withHint("金额 · 人民币")).perform(replaceText("0"), closeSoftKeyboard())
            onView(withText("保存账目")).perform(scrollTo(), click())
            onView(withText("请输入大于 0、最多两位小数的金额")).check(matches(isDisplayed()))
        }
    }

    @Test fun privacyMasksExistingNoticeAndReducedMotionKeepsNavigation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { it.model.notice.value = InAppNotice("已记一笔", 2650) }
            onView(withText("已记一笔 · ¥26.50")).check(matches(isDisplayed()))
            onView(withContentDescription("设置")).perform(click())
            onView(withText("隐藏金额")).perform(click())
            onView(withText("已记一笔 · ¥••••")).check(matches(isDisplayed()))
            onView(withText("减少动态")).perform(click())
            onView(withContentDescription("明细")).perform(click())
            onView(withText("¥ ••••")).check(matches(isDisplayed()))
            onView(withContentDescription("统计")).perform(click())
            onView(withText("看清每一笔")).check(matches(isDisplayed()))
        }
    }

    @Test fun themeSelectionClosesSheetAcrossActivityRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("设置")).perform(click())
            onView(withText("显示外观")).perform(click())
            onView(withText("深色")).perform(click())
            onView(withText("跟随系统")).check(doesNotExist())
            onView(withContentDescription("明细")).perform(click())
            onView(withText("记一笔")).check(matches(isDisplayed()))
        }
    }

    @Test fun readingPermissionDisclosureDoesNotEnableCapture() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("自动")).perform(click())
            scrollRow(scenario, 2, "source-notification")
            onView(withText("通知读取")).perform(click())
            onView(withText("付款通知读取")).check(matches(isDisplayed()))
            onView(withText("同意并开启通知处理")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withContentDescription("关闭")).perform(click())
            scenario.onActivity {
                assertFalse(it.model.capture.state.value.settings.notificationEnabled)
                assertFalse(it.model.capture.state.value.settings.captureConsentAccepted)
            }
        }
    }

    @Test fun firstGuideCanBeSkippedAndDoesNotReturnOnRelaunch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("usage-guide", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("第一次使用")).check(matches(isDisplayed()))
            onView(withText("下一步")).perform(scrollTo(), click())
            onView(withText("付款成功，停留片刻")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withText("付款成功，停留片刻")).check(matches(isDisplayed()))
            onView(withText("稍后设置")).perform(scrollTo(), click())
            scenario.onActivity {
                assertFalse(UsageGuideSheet.needsFirstRun(it))
                assertFalse(it.model.capture.state.value.settings.captureConsentAccepted)
                assertFalse(it.model.capture.state.value.settings.wechatEnabled)
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withText("第一次使用")).check(doesNotExist())
        }
    }

    @Test fun guideCanBeReopenedFromBothPermanentEntries() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("自动")).perform(click())
            onView(withText("使用指南")).perform(click())
            onView(withText("3 · 没识别到？手动触发一次")).perform(scrollTo(), click())
            onView(withText("没识别到？手动触发一次")).check(matches(isDisplayed()))
            onView(withContentDescription("关闭")).perform(click())
            onView(withContentDescription("设置")).perform(click())
            scrollRow(scenario, 3, "guide")
            onView(withText("使用指南")).perform(click())
            onView(withText("1 · 先选应用，再授权")).perform(scrollTo()).check(matches(isDisplayed()))
            scenario.onActivity { assertFalse(it.model.capture.state.value.settings.captureConsentAccepted) }
        }
    }

    @Test fun alipayExamplesStaySeparateAndLargeImageReturnsToSelectedType() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("自动")).perform(click())
            onView(withText("使用指南")).perform(click())
            onView(withText("支付宝三类页面 · 真实图解")).perform(scrollTo(), click())
            onView(withText("实付 ¥8.60 · 消费付款")).check(matches(isDisplayed()))
            onView(withContentDescription("转账页面示例")).perform(scrollTo(), click())
            onView(withText("转账 ¥298.00 · 不自动记账")).check(matches(isDisplayed()))
            scenario.recreate()
            onView(withContentDescription("转账页面示例")).check(matches(isChecked()))
            onView(withText("查看转账大图")).perform(scrollTo(), click())
            onView(withText("转账示例大图")).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription("转账页面示例")).perform(scrollTo()).check(matches(isChecked()))
            onView(withContentDescription("账单页面示例")).perform(click())
            onView(withText("支出 ¥8.50 · 按账单时间")).check(matches(isDisplayed()))
            onView(withText("查看账单大图")).perform(scrollTo(), click())
            onView(withText("账单示例大图")).check(matches(isDisplayed()))
            pressBack()
            pressBack()
            onView(withText("支付宝三类页面 · 真实图解")).check(matches(isDisplayed()))
            scenario.onActivity {
                assertFalse(it.model.capture.state.value.settings.captureConsentAccepted)
                assertFalse(it.model.capture.state.value.settings.ocrEnabled)
                assertFalse(UsageGuideSheet.needsFirstRun(it))
            }
        }
    }

    @Test fun firstGuideCanOpenRealExamplesWithoutLosingItsStep() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.getSharedPreferences("usage-guide", Context.MODE_PRIVATE).edit().clear().commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withText("下一步")).perform(scrollTo(), click())
            onView(withText("查看付款、转账与账单图解")).perform(scrollTo(), click())
            onView(withText("实付 ¥8.60 · 消费付款")).check(matches(isDisplayed()))
            pressBack()
            onView(withText("付款成功，停留片刻")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withText("稍后设置")).perform(scrollTo(), click())
            scenario.onActivity {
                assertFalse(UsageGuideSheet.needsFirstRun(it))
                assertFalse(it.model.capture.state.value.settings.captureConsentAccepted)
            }
        }
    }

    @Test fun wechatSelectionNeedsExplicitConsentAndDoesNotEnableReadChannels() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            onView(withContentDescription("自动")).perform(click())
            scrollRow(scenario, 2, "app-wechat")
            onView(withText("微信")).perform(click())
            scenario.onActivity { assertFalse(it.model.capture.state.value.settings.wechatEnabled) }
            onView(withText("同意并选择微信")).perform(scrollTo(), click())
            scenario.onActivity {
                val settings = it.model.capture.state.value.settings
                assertTrue(settings.wechatEnabled)
                assertTrue(settings.captureConsentAccepted)
                assertFalse(settings.accessibilityEnabled)
                assertFalse(settings.notificationEnabled)
                assertFalse(settings.ocrEnabled)
            }
        }
    }

    private fun scrollRow(scenario: ActivityScenario<MainActivity>, tab: Int, rowId: String) {
        scenario.onActivity { activity ->
            val list = activity.supportFragmentManager.findFragmentByTag("screen-$tab")!!.view as RecyclerView
            val adapter = list.adapter as InkRows
            val index = adapter.currentList.indexOfFirst { it.id == rowId }
            check(index >= 0)
            list.scrollToPosition(index)
        }
    }
}
