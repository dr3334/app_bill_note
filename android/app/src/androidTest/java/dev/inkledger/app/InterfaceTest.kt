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
import androidx.appcompat.app.AppCompatDelegate
import androidx.test.platform.app.InstrumentationRegistry

@RunWith(AndroidJUnit4::class)
class InterfaceTest {
    @Before fun resetPreferences() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("appearance", Context.MODE_PRIVATE)
            .edit().putBoolean("private", false).putBoolean("reduced", false).putInt("theme", -1).commit()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { AppCompatDelegate.setDefaultNightMode(-1) }
    }

    @Test fun dockNavigationAndBack() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("统计")).perform(click())
            onView(withText("看清每一笔")).check(matches(isDisplayed()))
            onView(withContentDescription("自动")).perform(click())
            onView(withText("付款采集尚未接通")).check(matches(isDisplayed()))
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
}
