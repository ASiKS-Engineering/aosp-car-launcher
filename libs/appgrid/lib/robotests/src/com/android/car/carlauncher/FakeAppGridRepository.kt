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

import com.android.car.carlauncher.datasources.restricted.TosState
import com.android.car.carlauncher.repositories.AppGridRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/** Fake implementation of a [AppGridRepository] to be used in tests. */
class FakeAppGridRepository : AppGridRepository {
    var allAppsList = emptyList<AppItem>()
    var mediaAppsList = emptyList<AppItem>()
    var distractionOptimization = true

    /** Fakes the terms of service state of the system. */
    var tosState = TosState(true, emptyList())

    override fun getAllAppsList(): Flow<List<AppItem>> = flowOf(allAppsList)

    override fun requiresDistractionOptimization() = flowOf(distractionOptimization)

    override fun getTosState(): Flow<TosState> = flowOf(tosState)

    override suspend fun saveAppOrder(currentAppOrder: List<AppItem>) {}

    override fun getMediaAppsList(): Flow<List<AppItem>> = flowOf(mediaAppsList)
}
