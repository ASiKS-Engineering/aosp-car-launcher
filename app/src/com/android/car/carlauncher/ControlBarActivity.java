/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.carlauncher;

import android.app.ActivityOptions;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;

import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.MediaLaunchHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaLaunchRouter;

import java.util.Set;

/**
 * Launcher activity that shows only the control bar fragment.
 */
public class ControlBarActivity extends FragmentActivity {
    private static final String TAG = "ControlBarActivity";
    private static final boolean DEBUG = false;
    private static final long NAVIGATION_WAIT_TIMEOUT_MS = 30_000;

    private Set<HomeCardModule> mHomeCardModules;
    private final Handler mNavigationHandler = new Handler(Looper.getMainLooper());
    private View mNavigationLoadingOverlay;
    private final BroadcastReceiver mPackageReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context context, Intent intent) {
            if (Intent.ACTION_PACKAGE_ADDED.equals(intent.getAction())
                    && intent.getData() != null
                    && "com.example.campernavigator".equals(intent.getData().getSchemeSpecificPart())
                    && mNavigationLoadingOverlay.getVisibility() == View.VISIBLE) {
                startCamperNavigator();
            }
        }
    };

    private final IntentHandler mIntentHandler = intent -> {
        if (intent != null) {
            ActivityOptions options = ActivityOptions.makeBasic();
            startActivity(intent, options.toBundle());
        }
    };

    // Used instead of IntentHandler because media apps may provide a PendingIntent instead
    private final MediaLaunchHandler mMediaMediaLaunchHandler = mediaSource -> {
        if (DEBUG) {
            Log.d(TAG, "Launching media source " + mediaSource);
        }
        mediaSource.launchActivity(ControlBarActivity.this, ActivityOptions.makeBasic());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);

        setContentView(R.layout.control_bar_container);
        mNavigationLoadingOverlay = findViewById(R.id.navigation_loading_overlay);
        findViewById(R.id.navigation_button).setOnClickListener(view -> openNavigation());
        findViewById(R.id.home_button).setOnClickListener(view -> openHome());
        IntentFilter packageFilter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        packageFilter.addDataScheme("package");
        registerReceiver(mPackageReceiver, packageFilter);
        initializeCards();

        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        initializeCards();
    }

    private void initializeCards() {
        if (mHomeCardModules == null) {
            mHomeCardModules = new ArraySet<>();
            for (String providerClassName : getResources().getStringArray(
                    R.array.config_homeCardModuleClasses_horizontal)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule)
                            Class.forName(providerClassName).newInstance();
                    if (cardModule.getCardResId() == R.id.top_card) {
                        View topCard = findViewById(R.id.top_card);
                        if (topCard != null) {
                            topCard.setVisibility(View.GONE);
                        }
                    }
                    cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */this));
                    mHomeCardModules.add(cardModule);
                    if (DEBUG) {
                        long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                        Log.d(TAG, "Initialization of HomeCardModule class " + providerClassName
                                + " took " + reflectionTime + " ms");
                    }
                } catch (IllegalAccessException | InstantiationException
                         | ClassNotFoundException e) {
                    Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
                }
            }
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : mHomeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView().getFragment());
        }
        transaction.commitNow();
    }

    private void openNavigation() {
        showNavigationLoading();
        if (CarLauncherUtils.isCamperNavigatorAvailable(this)) {
            startCamperNavigator();
        } else {
            mNavigationHandler.postDelayed(this::hideNavigationLoading,
                    NAVIGATION_WAIT_TIMEOUT_MS);
        }
    }

    private void openHome() {
        hideNavigationLoading();
        CarLauncherUtils.notifyMapsVisibility(this, /* visible= */ false);
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(homeIntent);
    }

    private void startCamperNavigator() {
        mNavigationHandler.removeCallbacksAndMessages(null);
        hideNavigationLoading();
        CarLauncherUtils.notifyMapsVisibility(this, /* visible= */ true);
    }

    private void showNavigationLoading() {
        mNavigationLoadingOverlay.setVisibility(View.VISIBLE);
    }

    private void hideNavigationLoading() {
        mNavigationHandler.removeCallbacksAndMessages(null);
        mNavigationLoadingOverlay.setVisibility(View.GONE);
    }

    @Override
    protected void onDestroy() {
        mNavigationHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(mPackageReceiver);
        super.onDestroy();
    }
}
