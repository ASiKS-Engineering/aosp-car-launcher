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

package com.android.car.carlauncher

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.android.car.carlauncher.datasources.restricted.TosState
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.lastOrNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import shadows.ShadowResources

@RunWith(RobolectricTestRunner::class)
@Config(shadows = [ShadowResources::class])
class AppGridViewModelTest {
    private val appGridRepository = FakeAppGridRepository()
    private val application = ApplicationProvider.getApplicationContext<Application>()
    private val viewModel = AppGridViewModel(appGridRepository, application)

    @After
    fun tearDown() {
        ShadowResources.reset()
    }

    @Test
    fun getShouldShowTosBanner_whenFlagEnabledAndBannerDisabledByConfig_returnsFalse() = runTest {
        ShadowResources.setBoolean(R.bool.config_enable_tos_banner, false)

        val enableBanner = viewModel.getShouldShowTosBanner().lastOrNull()

        assertThat(enableBanner).isFalse()
    }

    @Test
    fun getShouldShowTosBanner_whenBlockTosAppsIsFalse_returnsFalse() = runTest {
        appGridRepository.tosState = TosState(false, emptyList())
        ShadowResources.setBoolean(R.bool.config_enable_tos_banner, true)

        val enableBanner = viewModel.getShouldShowTosBanner().lastOrNull()

        assertThat(enableBanner).isFalse()
    }

    @Test
    fun getShouldShowTosBanner_whenBlockTosAppsIsTrue_returnsTrue() = runTest {
        appGridRepository.tosState = TosState(true, emptyList())
        ShadowResources.setBoolean(R.bool.config_enable_tos_banner, true)

        val enableBanner = viewModel.getShouldShowTosBanner().lastOrNull()

        assertThat(enableBanner).isTrue()
    }
}
