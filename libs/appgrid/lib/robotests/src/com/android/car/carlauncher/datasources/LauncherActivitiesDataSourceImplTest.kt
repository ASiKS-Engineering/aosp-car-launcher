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

package com.android.car.carlauncher.datasources

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.UserHandle
import com.android.car.carlauncher.datasources.LauncherActivitiesDataSourceImpl.Companion.CAR_APP_MEDIA_CATEGORY
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.any
import org.mockito.kotlin.doReturn
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LauncherActivitiesDataSourceImplTest {

    private val scope = TestScope()

    private val bgDispatcher =
        StandardTestDispatcher(scope.testScheduler, name = "Background dispatcher")

    private val launcherActivities: List<LauncherActivityInfo> = listOf(mock(), mock())

    private val calMediaComponentName = ComponentName(CAR_APP_SERVICE_MEDIA, "Media")
    private val calNavigationComponentName = ComponentName(CAR_APP_SERVICE_NAVIGATION, "Navigation")
    private val calMediaLauncherActivityInfo: LauncherActivityInfo = mock {
        on { componentName } doReturn calMediaComponentName
    }
    private val calMediaLauncherActivities: List<LauncherActivityInfo> =
        listOf(calMediaLauncherActivityInfo)

    private var broadcastReceiverCallback: BroadcastReceiver? = null
    private val registerReceiverFun: (BroadcastReceiver, IntentFilter) -> Unit =
        { broadcastReceiver, _ ->
            broadcastReceiverCallback = broadcastReceiver
        }
    private val unregisterReceiverFun: (BroadcastReceiver) -> Unit = mock()
    private val myUserHandle: UserHandle = mock()
    private val launcherApps: LauncherApps = mock {
        on { getActivityList(null, myUserHandle) } doReturn launcherActivities
        on {
            getActivityList(CAR_APP_SERVICE_MEDIA, myUserHandle)
        } doReturn calMediaLauncherActivities
        on { getActivityList(CAR_APP_SERVICE_NAVIGATION, myUserHandle) } doReturn launcherActivities
    }

    private val listOfComponentNames = listOf(
        calMediaComponentName, // 0, CarAppService MEDIA
        calNavigationComponentName, // 1, CarAppService NAVIGATION
    )

    // List of CarAppServices returned by the PackageManager for queryIntentServices.
    private val carAppServices: List<ResolveInfo> = listOfComponentNames.map { getResolveInfo(it) }

    /**
     * Returns a mocked ResolveInfo
     * @param componentName packageName + className of the mocked [ServiceInfo]
     * with an IntentFilter for the CarAppService category
     */
    private fun getResolveInfo(componentName: ComponentName): ResolveInfo {
        return ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = componentName.packageName
                name = componentName.className
            }
            filter = IntentFilter().apply {
                if (componentName.packageName == CAR_APP_SERVICE_MEDIA) {
                    addCategory(CAR_APP_MEDIA_CATEGORY)
                } else if (componentName.packageName == CAR_APP_SERVICE_NAVIGATION) {
                    addCategory(CAR_APP_NAVIGATION_CATEGORY)
                }
            }
        }
    }

    private val packageManager: PackageManager = mock {
        on {
            queryIntentServices(
                any(), anyInt()
            )
        } doReturn carAppServices
    }
    private val dataSource: LauncherActivitiesDataSource = LauncherActivitiesDataSourceImpl(
        packageManager,
        launcherApps,
        registerReceiverFun,
        unregisterReceiverFun,
        myUserHandle,
        RuntimeEnvironment.getApplication().resources,
        bgDispatcher
    )

    @Test
    fun testGetAllLauncherActivities() = scope.runTest {
        val listOfApps = dataSource.getAllLauncherActivities()

        assertEquals(listOfApps.size, 2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testOnPackagesChanged_broadcastReceived_shouldUpdateFlow() = scope.runTest {
        // reset the broadcastReceiverCallback to null
        broadcastReceiverCallback = null
        val flows = mutableListOf<String>()

        launch(StandardTestDispatcher(testScheduler)) {
            dataSource.getOnPackagesChanged().toList(flows)
        }
        advanceUntilIdle()
        // Make a fake change in packages broadcast event.
        val uri1 =
            mock<Uri> { on { schemeSpecificPart } doReturn BROADCAST_EXPECTED_PACKAGE_NAME_1 }
        val intent1: Intent = mock {
            on { data } doReturn uri1
        }
        broadcastReceiverCallback?.onReceive(mock(), intent1)
        advanceUntilIdle()
        // Make another fake broadcast event with different package name
        val uri2 =
            mock<Uri> { on { schemeSpecificPart } doReturn BROADCAST_EXPECTED_PACKAGE_NAME_2 }
        val intent2: Intent = mock {
            on { data } doReturn uri2
        }
        broadcastReceiverCallback?.onReceive(mock(), intent2)
        advanceUntilIdle()
        coroutineContext.cancelChildren()

        // BroadcastReceiver must been set after the producer call is trigger.
        assertNotNull(broadcastReceiverCallback)
        // Producer block sends an empty package immediately to the collector.
        assertEquals(flows[0], "")
        assertEquals(flows[1], BROADCAST_EXPECTED_PACKAGE_NAME_1)
        assertEquals(flows[2], BROADCAST_EXPECTED_PACKAGE_NAME_2)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testOnPackagesChanged_scopeClosed_shouldCleanup() = scope.runTest {
        // reset the broadcastReceiverCallback to null
        broadcastReceiverCallback = null

        launch(StandardTestDispatcher(testScheduler)) {
            dataSource.getOnPackagesChanged().collect()
        }
        advanceUntilIdle()
        coroutineContext.cancelChildren()
        advanceUntilIdle()

        // close all child coroutines, this should close the scope.
        assertNotNull(broadcastReceiverCallback)
        broadcastReceiverCallback?.let {
            verify(unregisterReceiverFun).invoke(it)
        }
    }

    @Test
    fun getAllCalMediaLauncherActivities_onlyReturnsMediaCategory() = scope.runTest {
        val outputCalActivityInfoList =
            dataSource.getAllCalMediaLauncherActivities()

        assertEquals(outputCalActivityInfoList.size, 1)
        assertEquals(
            outputCalActivityInfoList[0].componentName.packageName,
            CAR_APP_SERVICE_MEDIA
        )
    }

    companion object {
        const val BROADCAST_EXPECTED_PACKAGE_NAME_1 = "com.test.example1"
        const val BROADCAST_EXPECTED_PACKAGE_NAME_2 = "com.test.example2"
        const val CAR_APP_SERVICE_MEDIA = "com.test.car.app.package.media"
        const val CAR_APP_SERVICE_NAVIGATION = "com.test.car.app.package.navigation"
        const val CAR_APP_NAVIGATION_CATEGORY = "androidx.car.app.category.NAVIGATION"
    }
}
