/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.car.carlauncher.homescreen

import android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS
import android.car.settings.CarSettings.Secure.KEY_USER_TOS_ACCEPTED
import android.content.Intent
import android.provider.Settings
import android.testing.TestableContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.car.carlauncher.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MapTosActivityTest {
    private val context =
        TestableContext(InstrumentationRegistry.getInstrumentation().targetContext)
    private val resources = context.resources

    @Test
    fun onCreate_whenCarUxRestrictionsActive_shouldDisplayDistractionOptimizedText() {
        ActivityScenario.launch(MapTosActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.handleReviewButtonDistractionOptimized(requiresDistractionOptimization = true)

                assertThat(it.reviewButton.text).isEqualTo(
                    resources.getText(R.string.map_tos_review_button_distraction_optimized_text)
                )
            }
        }
    }

    @Test
    fun onCreate_whenCarUxRestrictionsInactive_shouldDisplayNonDistractionOptimizedText() {
        ActivityScenario.launch(MapTosActivity::class.java).use { scenario ->
            scenario.onActivity {
                it.handleReviewButtonDistractionOptimized(requiresDistractionOptimization = false)

                assertThat(it.reviewButton.text).isEqualTo(
                    resources.getText(R.string.map_tos_review_button_text)
                )
            }
        }
    }

    @Test
    fun onCreate_tosContentObserver_isNotNull() {
        Settings.Secure.putInt(context.contentResolver, KEY_USER_TOS_ACCEPTED, 1)
        Settings.Secure.putString(
            context.contentResolver,
            KEY_UNACCEPTED_TOS_DISABLED_APPS,
            NON_EMPTY_TOS_DISABLED_APPS
        )

        ActivityScenario.launch<MapTosActivity>(
            Intent(context, MapTosActivity::class.java)
        ).use { scenario ->
            scenario.onActivity { assertThat(it.tosContentObserver).isNotNull() }
        }
    }

    @Test
    fun afterTosIsAccepted_activityIsFinishing() {
        Settings.Secure.putInt(context.contentResolver, KEY_USER_TOS_ACCEPTED, 1)
        Settings.Secure.putString(
            context.contentResolver,
            KEY_UNACCEPTED_TOS_DISABLED_APPS,
            NON_EMPTY_TOS_DISABLED_APPS
        )

        ActivityScenario.launch<MapTosActivity>(
            Intent(context, MapTosActivity::class.java)
        ).use { scenario ->
            scenario.onActivity {
                // Accept TOS
                Settings.Secure.putInt(context.contentResolver, KEY_USER_TOS_ACCEPTED, 2)
                Settings.Secure.putString(
                    context.contentResolver,
                    KEY_UNACCEPTED_TOS_DISABLED_APPS,
                    EMPTY_TOS_DISABLED_APPS
                )
                it.tosContentObserver?.onChange(true)

                assertThat(it.isFinishing).isTrue()
            }
        }
    }

    private companion object {
        const val NON_EMPTY_TOS_DISABLED_APPS = "com.test.package1,com.test.package2"
        const val EMPTY_TOS_DISABLED_APPS = ""
    }
}
