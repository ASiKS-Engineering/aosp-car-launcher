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

import android.car.Car
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS
import android.database.ContentObserver
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.WindowInsets
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.android.car.carlauncher.AppLauncherUtils
import com.android.car.carlauncher.R
import com.android.car.ui.utils.CarUiUtils
import com.android.car.ui.uxr.DrawableStateTextView
import com.google.common.annotations.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * A placeholder map activity to display when terms of service have not been accepted.
 *
 * This activity can be used to launch an activity to help the user accept terms of service.
 */
class MapTosActivity : AppCompatActivity() {
    private val bgDispatcher: CoroutineDispatcher = Dispatchers.Default
    @VisibleForTesting lateinit var reviewButton: DrawableStateTextView
    private var car: Car? = null
    @VisibleForTesting var tosContentObserver: ContentObserver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        theme.applyStyle(R.style.MapTosActivityThemeOverlay, true)
        setContentView(R.layout.map_tos_activity)
        reviewButton = findViewById(R.id.review_button)
        reviewButton.setOnClickListener {
            val tosIntent = AppLauncherUtils.getIntentForTosAcceptanceFlow(it.context)
            log("Launching tos acceptance activity")
            AppLauncherUtils.launchApp(it.context, tosIntent)
        }

        // Enable edge-to-edge display
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.decorView.rootView.setOnApplyWindowInsetsListener { v, insets ->
            val appliedInsets = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(
                appliedInsets.left,
                0, // top
                appliedInsets.right,
                0 // bottom
            )
            insets.inset(appliedInsets)
        }

        setupCarUxRestrictionsListener()
        handleReviewButtonDistractionOptimized(requiresDistractionOptimization = false)
        setupContentObserverForTos()
    }

    override fun onDestroy() {
        car?.getCarManager(CarUxRestrictionsManager::class.java)?.unregisterListener()
        car?.disconnect()

        unregisterContentObserverForTos()

        super.onDestroy()
    }

    private fun setupCarUxRestrictionsListener() = lifecycleScope.launch {
        withContext(bgDispatcher) {
            car = Car.createCar(baseContext)
        }
        val carUxRestrictionsManager = car?.getCarManager(CarUxRestrictionsManager::class.java)
        carUxRestrictionsManager?.registerListener {
            handleReviewButtonDistractionOptimized(it.isRequiresDistractionOptimization)
        }
        val requiresDistractionOptimization = carUxRestrictionsManager
            ?.currentCarUxRestrictions
            ?.isRequiresDistractionOptimization ?: false
        handleReviewButtonDistractionOptimized(requiresDistractionOptimization)
    }

    //  TODO: b/319266967 - Remove annotation once FakeCarUxRestrictionsService allows setting
    //  requiresDistractionOptimization
    @VisibleForTesting
    fun handleReviewButtonDistractionOptimized(requiresDistractionOptimization: Boolean) {
        CarUiUtils.makeAllViewsEnabled(
            reviewButton,
            !requiresDistractionOptimization // enabled
        )
        when (requiresDistractionOptimization) {
            true -> reviewButton.setText(R.string.map_tos_review_button_distraction_optimized_text)
            false -> reviewButton.setText(R.string.map_tos_review_button_text)
        }
    }

    private fun setupContentObserverForTos() {
        tosContentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val tosAccepted = AppLauncherUtils.tosAccepted(applicationContext)
                log("TOS state updated:$tosAccepted")
                if (tosAccepted) {
                    finish()
                }
            }
        }.also {
            contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(KEY_UNACCEPTED_TOS_DISABLED_APPS),
                false, // notifyForDescendants
                it
            )
        }
    }

    private fun unregisterContentObserverForTos() {
        tosContentObserver?.let { contentResolver.unregisterContentObserver(it) }
        tosContentObserver = null
    }

    private companion object {
        const val TAG = "MapTosActivity"
        val DEBUG = Log.isLoggable(TAG, Log.DEBUG)

        fun log(msg: String) {
            if (DEBUG) {
                Log.d(TAG, msg)
            }
        }
    }
}
