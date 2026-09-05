/*
 * Copyright (C) 2025 The Android Open Source Project
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
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.UserManager;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.collection.ArraySet;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.MediaLaunchHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaLaunchRouter;
import com.android.car.carlauncher.widgets.CarAppWidgetHost;
import com.android.car.carlauncher.widgets.CarAppWidgetHostView;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Launcher activity that shows the control bar fragment & a list of app widgets
 */
public class WidgetHostActivity extends AppCompatActivity {

    private static final String TAG = "WidgetHostActivity";
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);
    private static final int REQUEST_BIND = 100;
    private static final int REQUEST_CONFIGURE = REQUEST_BIND + 1;
    private static final int RESULT_SUCCESS = RESULT_OK;
    private static final int RESULT_ERROR = RESULT_CANCELED;
    private static final int RESULT_NEEDS_BIND = RESULT_CANCELED + 1;
    private static final int RESULT_NEEDS_CONFIGURE = RESULT_NEEDS_BIND + 1;

    private final IntentHandler mIntentHandler = intent -> {
        if (intent != null) {
            ActivityOptions options = ActivityOptions.makeBasic();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent, options.toBundle());
        }
    };
    // Used instead of IntentHandler because media apps may provide a PendingIntent instead
    private final MediaLaunchHandler mMediaMediaLaunchHandler = mediaSource -> {
        if (DEBUG) {
            Log.d(TAG, "Launching media source " + mediaSource);
        }
        mediaSource.launchActivity(WidgetHostActivity.this, ActivityOptions.makeBasic());
    };
    private final Map<Integer, AppWidgetProviderInfo> mAppWidgetInfoMap = new HashMap<>();
    private final BroadcastReceiver mOverlayChangeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CONFIGURATION_CHANGED.equals(intent.getAction())) {
                if (DEBUG) {
                    Log.d(TAG, "Configuration changed, recreating activity");
                }
                recreate();
            }
        }
    };
    private int mWidgetMediaCardSize;
    private AppWidgetManager mAppWidgetManager;
    private UserManager mUserManager;
    private boolean mUserUnlocked;
    private boolean mIsLayoutComplete;
    private boolean mWidgetsLoaded;

    private final BroadcastReceiver mUserUnlockedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !Intent.ACTION_USER_UNLOCKED.equals(intent.getAction())) {
                return;
            }
            if (DEBUG) {
                Log.d(TAG, "User unlocked, attempting to load widgets");
            }
            mUserUnlocked = true;
            tryLoadWidgets();
            unregisterReceiver(mUserUnlockedReceiver);
        }
    };

    private void tryLoadWidgets() {
        if (!mUserUnlocked) {
            if (DEBUG) {
                Log.d(TAG, "tryLoadWidgets: user still locked");
            }
            return;
        }
        if (!mIsLayoutComplete) {
            if (DEBUG) {
                Log.d(TAG, "tryLoadWidgets: layout not complete");
            }
            return;
        }
        if (mWidgetsLoaded) {
            return;
        }

        if (DEBUG) {
            Log.d(TAG, "Conditions met, loading widgets");
        }
        mWidgetsLoaded = true;
        loadAndDisplayWidgets();
    }

    private CarAppWidgetHost mAppWidgetHost;
    private LinearLayout mWidgetContainer;
    private ViewGroup mCardContainer;
    private Set<HomeCardModule> mHomeCardModules;
    private boolean mIsLandscape;
    private Drawable mScrollViewBg;
    private FrameLayout mScrollView;
    private int mWidgetDividerSize;
    private int mWidgetHorizontalMargin;
    private int mWidgetVerticalMargin;
    private int mWidgetHeight;
    private int mWidgetWidth;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);

        // TODO: b/476465227 - Use configuration rather than orientation
        int orientation = getResources().getConfiguration().orientation;
        mIsLandscape = orientation == Configuration.ORIENTATION_LANDSCAPE;

        setContentView(R.layout.widget_host_activity);
        findViewById(R.id.navigation_button).setOnClickListener(view -> openNavigation());
        findViewById(R.id.home_button).setOnClickListener(view -> openHome());

        initializeCards();

        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);

        mUserManager = getSystemService(UserManager.class);
        mUserUnlocked = mUserManager.isUserUnlocked();
        if (!mUserUnlocked) {
            if (DEBUG) {
                Log.d(TAG, "User locked, waiting for unlock");
            }
            registerReceiver(mUserUnlockedReceiver, new IntentFilter(Intent.ACTION_USER_UNLOCKED));
        }

        mAppWidgetManager = AppWidgetManager.getInstance(this);
        mAppWidgetHost = new CarAppWidgetHost(this,
                getResources().getInteger(R.integer.config_appwidget_host_id));
        mWidgetContainer = findViewById(R.id.widget_container);
        mCardContainer = findViewById(R.id.card_container);
        mScrollView = findViewById(R.id.scroll_view);

        mWidgetWidth = (int) getResources().getDimension(R.dimen.widget_width);
        mWidgetHeight = (int) getResources().getDimension(R.dimen.widget_height);
        mWidgetMediaCardSize = (int) getResources().getDimension(R.dimen.widget_media_card_size);
        mWidgetDividerSize = (int) getResources().getDimension(R.dimen.widget_divider_size);
        mWidgetHorizontalMargin = (int) getResources().getDimension(
                R.dimen.widget_horizontal_margin);
        mWidgetVerticalMargin = (int) getResources().getDimension(R.dimen.widget_vertical_margin);

        mScrollViewBg = getResources().getDrawable(R.drawable.scroll_view_background, getTheme());

        mCardContainer.getViewTreeObserver().addOnGlobalLayoutListener(
                new ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mCardContainer.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        mIsLayoutComplete = true;
                        tryLoadWidgets();
                    }
                });
    }

    @Override
    protected void onStart() {
        super.onStart();
        mAppWidgetHost.startListening();
        registerReceiver(mOverlayChangeReceiver,
                new IntentFilter(Intent.ACTION_CONFIGURATION_CHANGED));
    }

    @Override
    protected void onStop() {
        super.onStop();
        mAppWidgetHost.stopListening();
        unregisterReceiver(mOverlayChangeReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!mWidgetsLoaded && !mUserUnlocked) {
            try {
                unregisterReceiver(mUserUnlockedReceiver);
            } catch (IllegalArgumentException e) {
                // Receiver was already unregistered or not registered
            }
        }
        mAppWidgetInfoMap.forEach((id, info) -> mAppWidgetHost.deleteAppWidgetId(id));
        mAppWidgetInfoMap.clear();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (data == null) {
            Log.e(TAG, "data is null");
            return;
        }

        if (requestCode == REQUEST_BIND && resultCode == RESULT_OK) {
            int widgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (widgetId == -1) {
                Log.e(TAG, "Unable to find widget id after bind");
                return;
            }

            AppWidgetProviderInfo appWidgetInfo = mAppWidgetInfoMap.get(widgetId);
            if (appWidgetInfo == null) {
                Log.w(TAG, "REQUEST_BIND no AppWidgetProviderInfo");
                return;
            }
            if (appWidgetInfo.configure == null) {
                createHostView(widgetId, appWidgetInfo);
            } else {
                Bundle options =
                        ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                                ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                                .toBundle();
                mAppWidgetHost.startAppWidgetConfigureActivityForResult(this, widgetId,
                        /* intentFlags= */ 0, REQUEST_CONFIGURE, options);
            }
        } else if (requestCode == REQUEST_CONFIGURE && resultCode == RESULT_OK) {
            int widgetId = data.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, -1);
            if (widgetId == -1) {
                Log.e(TAG, "Unable to find widget id after configure");
                return;
            }

            AppWidgetProviderInfo appWidgetInfo = mAppWidgetInfoMap.get(widgetId);
            if (appWidgetInfo == null) {
                return;
            }

            createHostView(widgetId, appWidgetInfo);
        }
    }

    private void openNavigation() {
        CarLauncherUtils.notifyMapsVisibility(this, /* visible= */ true);
        Intent mapsIntent = CarLauncherUtils.getMapsIntent(this);
        mapsIntent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(mapsIntent);
    }

    private void openHome() {
        CarLauncherUtils.notifyMapsVisibility(this, /* visible= */ false);
        Intent homeIntent = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        startActivity(homeIntent);
    }

    private void initializeCards() {
        int homeCardRes = mIsLandscape ? R.array.config_homeCardModuleClasses_horizontal
                : R.array.config_homeCardModuleClasses_vertical;
        if (mHomeCardModules == null) {
            mHomeCardModules = new ArraySet<>();
            for (String providerClassName : getResources().getStringArray(homeCardRes)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule) Class.forName(
                            providerClassName).getDeclaredConstructor().newInstance();
                    cardModule.setViewModelProvider(new ViewModelProvider(/* owner= */this));
                    mHomeCardModules.add(cardModule);
                    if (DEBUG) {
                        long reflectionTime = System.currentTimeMillis() - reflectionStartTime;
                        Log.d(TAG, "Initialization of HomeCardModule class " + providerClassName
                                + " took " + reflectionTime + " ms");
                    }
                } catch (ReflectiveOperationException e) {
                    Log.w(TAG, "Unable to create HomeCardProvider class " + providerClassName, e);
                }
            }
        }
        boolean hasBottomCard = mHomeCardModules.stream().peek(cardModule -> {
            if (cardModule.getCardResId() == R.id.top_card) {
                Log.e(TAG, "Top card is not supported in widget host.");
            }
        }).anyMatch(cardModule -> cardModule.getCardResId() == R.id.bottom_card);
        View bottomCard = findViewById(R.id.bottom_card);
        if (bottomCard != null) {
            bottomCard.setVisibility(hasBottomCard ? View.VISIBLE : View.GONE);
        }
        if (mCardContainer != null) {
            mCardContainer.setVisibility(hasBottomCard ? View.VISIBLE : View.GONE);
        }
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        for (HomeCardModule cardModule : mHomeCardModules) {
            transaction.replace(cardModule.getCardResId(), cardModule.getCardView().getFragment());
        }
        transaction.commitNow();
    }

    private void loadAndDisplayWidgets() {
        List<AppWidgetProviderInfo> providers = mAppWidgetManager.getInstalledProviders();
        Map<ComponentName, AppWidgetProviderInfo> providerMap = new HashMap<>();
        for (AppWidgetProviderInfo provider : providers) {
            providerMap.put(provider.provider, provider);
        }

        boolean widgetAdded = false;
        for (String componentString : getResources().getStringArray(
                R.array.config_initialAppWidgets)) {
            ComponentName componentName = ComponentName.unflattenFromString(componentString);
            AppWidgetProviderInfo info = providerMap.get(componentName);
            if (info == null) {
                Log.e(TAG, "Preconfigured AppWidget not found: " + componentName);
                continue;
            }

            int appWidgetId = -1;
            for (Map.Entry<Integer, AppWidgetProviderInfo> entry : mAppWidgetInfoMap.entrySet()) {
                if (info.equals(entry.getValue())) {
                    appWidgetId = entry.getKey();
                    break;
                }
            }
            if (appWidgetId == -1) {
                appWidgetId = mAppWidgetHost.allocateAppWidgetId();
            }

            int result = addWidget(appWidgetId, info);

            switch (result) {
                case RESULT_SUCCESS -> widgetAdded = true;
                case RESULT_NEEDS_BIND -> {
                    Intent intent = new Intent(AppWidgetManager.ACTION_APPWIDGET_BIND);
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, info.provider);
                    intent.putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE,
                            info.getProfile());
                    startActivityForResult(intent, REQUEST_BIND);
                }
                case RESULT_NEEDS_CONFIGURE -> {
                    Bundle options =
                            ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
                                    .toBundle();
                    mAppWidgetHost.startAppWidgetConfigureActivityForResult(this, appWidgetId,
                            /* intentFlags= */ 0, REQUEST_CONFIGURE, options);
                }
            }
        }

        adjustVisuals(widgetAdded);
    }

    private int addWidget(int appWidgetId, AppWidgetProviderInfo info) {
        mAppWidgetInfoMap.put(appWidgetId, info);

        if (mAppWidgetManager.bindAppWidgetIdIfAllowed(appWidgetId, info.provider)) {
            if (info.configure != null) {
                return RESULT_NEEDS_CONFIGURE;
            }

            if (!createHostView(appWidgetId, info)) {
                return RESULT_ERROR;
            }

            if (DEBUG) {
                Log.d(TAG, "Added widget with ID: " + appWidgetId + " from provider: "
                        + info.provider);
            }
            return RESULT_SUCCESS;
        } else {
            if (DEBUG) {
                Log.d(TAG, "Widget bind not allowed; with ID: " + appWidgetId
                        + " from provider: " + info.provider);
            }
            return RESULT_NEEDS_BIND;
        }
    }

    private boolean createHostView(int id, AppWidgetProviderInfo info) {
        CarAppWidgetHostView hostView = (CarAppWidgetHostView) mAppWidgetHost.createView(this, id,
                info);
        if (hostView == null) {
            if (DEBUG) {
                Log.e(TAG, "AppWidgetHostView is null; with ID: " + id + " from provider: "
                        + info.provider);
            }
            return false;
        }

        FrameLayout container = new FrameLayout(this);
        container.setPadding(mWidgetHorizontalMargin, mWidgetVerticalMargin,
                mWidgetHorizontalMargin, mWidgetVerticalMargin);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.CENTER;
        if (mIsLandscape) {
            lp.width = mWidgetWidth;
        } else {
            lp.height = mWidgetHeight;
        }

        hostView.bind(info, mWidgetWidth, mWidgetHeight);

        mWidgetContainer.addView(container, lp);
        container.addView(hostView);

        return true;
    }

    /**
     * Adjusts the visual layout of the card container and scroll view based on whether widgets are
     * present.
     * <p>
     * If no widgets are added:
     * - In landscape: The card container is expanded to match parent width, scroll view background
     *   is removed, and margins are reset.
     * - In portrait: The card container height is expanded to match parent height, and margins are
     *   adjusted.
     * <p>
     * If widgets are added:
     * - In landscape: The card container width is set to a fixed size, scroll view background is
     *   applied, and margins are set.
     * - In portrait: The card container height is set to a fixed size.
     *
     * @param widgetAdded True if at least one widget has been added, false otherwise.
     */
    private void adjustVisuals(boolean widgetAdded) {
        ViewGroup.MarginLayoutParams cardContainerLp =
                (ViewGroup.MarginLayoutParams) mCardContainer.getLayoutParams();
        ViewGroup.MarginLayoutParams scrollViewLp =
                (ViewGroup.MarginLayoutParams) mScrollView.getLayoutParams();
        if (!widgetAdded) {
            if (mIsLandscape) {
                mCardContainer.getLayoutParams().width = ViewGroup.LayoutParams.MATCH_PARENT;
                mScrollView.setBackground(null);
                cardContainerLp.setMargins(0, 0, 0, 0);
            } else {
                mCardContainer.getLayoutParams().height = ViewGroup.LayoutParams.MATCH_PARENT;
                cardContainerLp.setMargins(mWidgetDividerSize, 0, mWidgetDividerSize, 0);
            }
            scrollViewLp.setMargins(0, 0, 0, 0);
        } else {
            if (mIsLandscape) {
                mCardContainer.getLayoutParams().width = mWidgetMediaCardSize;
                mScrollView.setBackground(mScrollViewBg);
                scrollViewLp.setMargins(mWidgetDividerSize, mWidgetDividerSize, mWidgetDividerSize,
                        mWidgetDividerSize);
            } else {
                mCardContainer.getLayoutParams().height = mWidgetMediaCardSize;
            }
            cardContainerLp.setMargins(mWidgetHorizontalMargin, mWidgetVerticalMargin,
                    mWidgetHorizontalMargin, mWidgetVerticalMargin);
        }
        mCardContainer.setLayoutParams(cardContainerLp);
        mScrollView.setLayoutParams(scrollViewLp);
        mScrollView.requestLayout();
    }
}
