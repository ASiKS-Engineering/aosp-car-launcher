/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.app.ActivityTaskManager.INVALID_TASK_ID;
import static android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS;
import static android.view.WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY;

import static com.android.car.carlauncher.AppGridFragment.Mode.ALL_APPS;
import static com.android.car.carlauncher.CarLauncherViewModel.CarLauncherViewModelFactory;
import static com.android.systemui.car.Flags.scalableUi;

import android.app.ActivityManager;
import android.app.ActivityOptions;
import android.app.TaskStackListener;
import android.car.Car;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.database.ContentObserver;
import android.os.Bundle;
import android.os.Handler;
import android.os.UserManager;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.collection.ArraySet;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentTransaction;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProvider;

import com.android.car.carlauncher.homescreen.HomeCardModule;
import com.android.car.carlauncher.homescreen.audio.IntentHandler;
import com.android.car.carlauncher.homescreen.audio.MediaLaunchHandler;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.audio.media.MediaLaunchRouter;
import com.android.car.carlauncher.taskstack.TaskStackChangeListeners;
import com.android.car.internal.common.UserHelperLite;
import com.android.wm.shell.taskview.TaskView;

import com.google.common.annotations.VisibleForTesting;

import java.util.Set;

/**
 * Basic Launcher for Android Automotive which demonstrates the use of {@link TaskView} to host
 * maps content and uses a Model-View-Presenter structure to display content in cards.
 *
 * <p>Implementations of the Launcher that use the given layout of the main activity
 * (car_launcher.xml) can customize the home screen cards by providing their own
 * {@link HomeCardModule} for R.id.top_card or R.id.bottom_card. Otherwise, implementations that
 * use their own layout should define their own activity rather than using this one.
 *
 * <p>Note: On some devices, the TaskView may render with a width, height, and/or aspect
 * ratio that does not meet Android compatibility definitions. Developers should work with content
 * owners to ensure content renders correctly when extending or emulating this class.
 */
public class CarLauncher extends FragmentActivity {
    public static final String TAG = "CarLauncher";
    /** Temporary debug marker; bump on every debug build to identify the running launcher. */
    public static final String BUILD_MARKER = "NAVDBG-20261001-A";
    public static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private ActivityManager mActivityManager;
    private Car mCar;
    private int mCarLauncherTaskId = INVALID_TASK_ID;
    private Set<HomeCardModule> mHomeCardModules;

    /** Set to {@code true} once we've logged that the Activity is fully drawn. */
    private boolean mIsReadyLogged;
    private boolean mUseSmallCanvasOptimizedMap;
    private ViewGroup mMapsCard;
    private View mMapsPlaceholder;
    private View mEmbeddedTaskView;
    private LiveData<android.car.app.RemoteCarTaskView> mObservedTaskViewSource;
    /** True once the embedded task view has been requested; guards against double creation. */
    private boolean mEmbeddedNavigationRequested;
    private String mNavUiMode = CarLauncherUtils.NAVIGATION_UI_MODE_HOME;
    private boolean mNavUiModeReceiverRegistered;
    private boolean mShutdownReceiverRegistered;

    @VisibleForTesting
    CarLauncherViewModel mCarLauncherViewModel;
    @VisibleForTesting
    ContentObserver mTosContentObserver;

    private final TaskStackListener mTaskStackListener = new TaskStackListener() {
        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
        }

        @Override
        public void onActivityRestartAttempt(ActivityManager.RunningTaskInfo task,
                boolean homeTaskVisible, boolean clearedTask, boolean wasVisible) {
            if (DEBUG) {
                Log.d(TAG, "onActivityRestartAttempt: taskId=" + task.taskId
                        + ", homeTaskVisible=" + homeTaskVisible + ", wasVisible=" + wasVisible);
            }
            if (!mUseSmallCanvasOptimizedMap
                    && !homeTaskVisible
                    && getTaskViewTaskId() == task.taskId) {
                // The embedded map task view lives for the whole launcher lifetime, so keep
                // restart attempts inside the launcher-hosted task view.
                bringToForeground();
            }
        }
    };

    /**
     * Keeps CarLauncher's own UI in sync with the global navigation display mode that is shared
     * with SystemUI and the navigator app.
     */
    private final BroadcastReceiver mNavUiModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String mode = intent.getStringExtra(CarLauncherUtils.EXTRA_NAVIGATION_UI_MODE);
            if (mode != null) {
                applyNavUiMode(mode, false);
            }
        }
    };

    private final BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            persistCurrentNavUiMode();
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
        mediaSource.launchActivity(CarLauncher.this, ActivityOptions.makeBasic());
    };

    // 2. Der gesäuberte onCreate-Block (Lösche ALLES Alte in onCreate!)
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Log.i(TAG, "onCreate: launcher starting, build=" + BUILD_MARKER
                + ", apkBuildTime="
                + new java.util.Date(new java.io.File(getApplicationInfo().sourceDir).lastModified())
                + ", pid=" + android.os.Process.myPid());

        // Fenster-Transparenz
        getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);

        if (isDewdActive()) {
            setContentView(R.layout.home);
            return;
        }

        // Layout EINMALIG wählen
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
        }

        boolean isPassengerDisplay = isPassengerDisplay();

        if (!isPassengerDisplay) {
            mUseSmallCanvasOptimizedMap = CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(this);
            mActivityManager = getSystemService(ActivityManager.class);
            mCarLauncherTaskId = getTaskId();
            TaskStackChangeListeners.getInstance().registerTaskStackListener(mTaskStackListener);

            getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

            if (!UserHelperLite.isHeadlessSystemUser(getUserId())) {
                mMapsCard = findViewById(R.id.maps_card);
                mMapsPlaceholder = findViewById(R.id.maps_placeholder_text);

                // Load LUm mode
                String persistedMode = CarLauncherUtils.readPersistedNavigationUiMode(this);
                Log.i(TAG, "Restored persisted nav UI mode: " + persistedMode);
                mNavUiMode = persistedMode;
                updateNavigationLayerUi();

                ContextCompat.registerReceiver(
                        this,
                        mNavUiModeReceiver,
                        new IntentFilter(CarLauncherUtils.ACTION_NAVIGATION_UI_MODE_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED);

                mNavUiModeReceiverRegistered = true;
                ContextCompat.registerReceiver(
                        this,
                        mShutdownReceiver,
                        new IntentFilter(Intent.ACTION_SHUTDOWN),
                        ContextCompat.RECEIVER_NOT_EXPORTED);

                mShutdownReceiverRegistered = true;
                broadcastNavigationUiMode(mNavUiMode);

                syncEmbeddedNavigationHost();
            }
        } else {
            getSupportFragmentManager().beginTransaction().replace(R.id.maps_card,
                    AppGridFragment.newInstance(ALL_APPS)).commit();
        }

        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);
        initializeCards();
    }

    private void broadcastNavigationUiMode(String mode) {
        if (!isValidNavUiMode(mode)) {
            return;
        }

        Log.i(TAG, "broadcastNavigationUiMode: mode=" + mode);
        CarLauncherUtils.requestNavigationUiMode(this, mode);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String requestedMode = intent != null
                ? intent.getStringExtra(CarLauncherUtils.EXTRA_NAVIGATION_UI_MODE)
                : null;

        if (requestedMode != null) {
            applyNavUiMode(requestedMode, true);
        }
    }

    private void setupRemoteCarTaskView(ViewGroup parent) {
        mCarLauncherViewModel = new ViewModelProvider(this,
                new CarLauncherViewModelFactory(this, getMapsIntent()))
                .get(CarLauncherViewModel.class);

        getLifecycle().addObserver(mCarLauncherViewModel);
        addOnNewIntentListener(mCarLauncherViewModel.getNewIntentListener());

        observeRemoteCarTaskView();
    }

    private void observeRemoteCarTaskView() {
        LiveData<android.car.app.RemoteCarTaskView> taskViewSource =
                mCarLauncherViewModel.getRemoteCarTaskView();
        if (taskViewSource == mObservedTaskViewSource) {
            Log.d(TAG, "observeRemoteCarTaskView: same LiveData source, skip re-register");
            return;
        }

        Log.i(TAG, "observeRemoteCarTaskView: attaching to new LiveData source");
        mObservedTaskViewSource = taskViewSource;
        taskViewSource.observe(this, taskView -> {
            ViewGroup container = findViewById(R.id.maps_card_container);
            if (taskView == null) {
                Log.i(TAG, "RemoteCarTaskView observer: taskView=null, mode=" + mNavUiMode
                        + ", embeddedAttached=" + (mEmbeddedTaskView != null));
                if (mEmbeddedTaskView != null) {
                    // The host went away (e.g. car service disconnect); allow re-creation.
                    mEmbeddedNavigationRequested = false;
                }
                removeEmbeddedTaskView();
                if (mMapsPlaceholder != null) {
                    mMapsPlaceholder.setVisibility(
                            CarLauncherUtils.NAVIGATION_UI_MODE_HOME.equals(mNavUiMode)
                                    ? View.VISIBLE : View.GONE);
                }
                return;
            }

            if (container == null) {
                Log.w(TAG, "RemoteCarTaskView observer: maps_card_container is null");
                return;
            }

            if (mEmbeddedTaskView == taskView && taskView.getParent() == container) {
                Log.d(TAG, "RemoteCarTaskView observer: taskView already attached, keep state");
                if (mMapsPlaceholder != null) {
                    mMapsPlaceholder.setVisibility(View.GONE);
                }
                return;
            }

            Log.i(TAG, "RemoteCarTaskView observer: attaching taskView=" + taskView
                    + ", oldEmbedded=" + mEmbeddedTaskView
                    + ", parent=" + taskView.getParent()
                    + ", mode=" + mNavUiMode);
            removeEmbeddedTaskView();

            if (taskView.getParent() != null) {
                ((ViewGroup) taskView.getParent()).removeView(taskView);
            }

            container.addView(taskView, 0);
            mEmbeddedTaskView = taskView;

            // HOME mode keeps the map behind launcher widgets inside the launcher window.
            taskView.setVisibility(View.VISIBLE);
            taskView.setZOrderOnTop(false);
            taskView.setZOrderMediaOverlay(true);
            taskView.setObscuredTouchRegion(null);

            Log.i(TAG, "RemoteCarTaskView attached: visible=" + taskView.getVisibility()
                    + ", parent=" + taskView.getParent()
                    + ", placeholderVisible="
                    + (mMapsPlaceholder != null && mMapsPlaceholder.getVisibility() == View.VISIBLE));

            if (mMapsPlaceholder != null) {
                mMapsPlaceholder.setVisibility(View.GONE);
            }
        });
    }

    /**
     * Makes sure exactly one embedded navigation task view exists. The task view is never torn
     * down on a HOME/FULLSCREEN switch: a released task view leaves its task hidden and a newly
     * created task view does not re-adopt it, which results in a black surface.
     */
    private void syncEmbeddedNavigationHost() {
        if (mMapsCard == null) {
            return;
        }

        if (mCarLauncherViewModel == null) {
            Log.i(TAG, "syncEmbeddedNavigationHost: first creation, mode=" + mNavUiMode);
            setupRemoteCarTaskView(mMapsCard);
            mEmbeddedNavigationRequested = true;
        } else if (!mEmbeddedNavigationRequested
                && mCarLauncherViewModel.getRemoteCarTaskView().getValue() == null) {
            Log.i(TAG, "syncEmbeddedNavigationHost: re-creation, mode=" + mNavUiMode);
            mCarLauncherViewModel.initializeRemoteCarTaskView(getMapsIntent());
            mEmbeddedNavigationRequested = true;
            observeRemoteCarTaskView();
        } else {
            Log.d(TAG, "syncEmbeddedNavigationHost: keep existing task view, mode=" + mNavUiMode
                    + ", embeddedAttached=" + (mEmbeddedTaskView != null));
        }

        if (mTosContentObserver == null) {
            setupContentObserversForTos();
        }
    }

    private void removeEmbeddedTaskView() {
        if (mEmbeddedTaskView == null) {
            return;
        }
        Log.i(TAG, "removeEmbeddedTaskView: removing=" + mEmbeddedTaskView
                + ", parent=" + mEmbeddedTaskView.getParent());
        if (mEmbeddedTaskView.getParent() instanceof ViewGroup parent) {
            parent.removeView(mEmbeddedTaskView);
        }
        mEmbeddedTaskView = null;
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.i(TAG, "Home Screen resumed");
        // Intentionally not touching the nav UI mode or task host here. onResume can fire for
        // reasons unrelated to the Home/Nav buttons (e.g. screen on/off); mode changes are
        // driven by the shared navigation-mode broadcast.
    }

    /**
     * Applies the given navigation UI mode (HOME embedded vs. FULLSCREEN) to CarLauncher's own
     * home cards. Launcher is the source of truth for the persisted mode; SystemUI and the
     * navigator app observe the shared mode broadcast.
     */
    private void applyNavUiMode(String mode, boolean notifyNavigator) {
        if (!isValidNavUiMode(mode)) {
            Log.w(TAG, "Ignoring invalid nav UI mode request: " + mode);
            return;
        }

        boolean modeChanged = !mode.equals(mNavUiMode);
        Log.i(TAG, "applyNavUiMode: build=" + BUILD_MARKER + ", oldMode=" + mNavUiMode
                + ", newMode=" + mode + ", changed=" + modeChanged
                + ", notifyNavigator=" + notifyNavigator
                + ", embeddedAttached=" + (mEmbeddedTaskView != null));

        mNavUiMode = mode;

        // Our own broadcast comes back through mNavUiModeReceiver; do not redo the work.
        if (modeChanged) {
            updateNavigationLayerUi();
        }

        syncEmbeddedNavigationHost();

        if (notifyNavigator) {
            broadcastNavigationUiMode(mode);
        }
    }

    private void updateNavigationLayerUi() {
        boolean fullscreen =
                CarLauncherUtils.NAVIGATION_UI_MODE_FULLSCREEN.equals(mNavUiMode);

Log.i(TAG, "updateNavigationLayerUi: fullscreen=" + fullscreen);

        View audioCard = findViewById(R.id.bottom_card);
        if (audioCard != null) {
            audioCard.setVisibility(fullscreen ? View.GONE : View.VISIBLE);
        }

        initializeCards();
    }

    private boolean isValidNavUiMode(String mode) {
        return CarLauncherUtils.NAVIGATION_UI_MODE_HOME.equals(mode)
                || CarLauncherUtils.NAVIGATION_UI_MODE_FULLSCREEN.equals(mode);
    }

    private void persistCurrentNavUiMode() {
        Log.i(TAG, "Persisting nav UI mode: " + mNavUiMode);
        CarLauncherUtils.persistNavigationUiMode(this, mNavUiMode);
	}

    @Override
    protected void onPause() {
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        if (isDewdActive()) {
            // no-op
            return;
        }

        TaskStackChangeListeners.getInstance().unregisterTaskStackListener(mTaskStackListener);
        if (mNavUiModeReceiverRegistered) {
            unregisterReceiver(mNavUiModeReceiver);
            mNavUiModeReceiverRegistered = false;
        }
        if (mShutdownReceiverRegistered) {
            unregisterReceiver(mShutdownReceiver);
            mShutdownReceiverRegistered = false;
        }
        if (isFinishing()) {
            persistCurrentNavUiMode();
        }
        unregisterTosContentObserver();
        release();
    }

    private void unregisterTosContentObserver() {
        if (mTosContentObserver != null) {
            Log.i(TAG, "Unregister content observer for tos state");
            getContentResolver().unregisterContentObserver(mTosContentObserver);
            mTosContentObserver = null;
        }
    }

    private int getTaskViewTaskId() {
        if (mCarLauncherViewModel != null) {
            return mCarLauncherViewModel.getRemoteCarTaskViewTaskId();
        }
        return INVALID_TASK_ID;
    }

    private void release() {
        if (mMapsCard != null) {
            // This is important as the TaskView is preserved during config change in ViewModel and
            // to avoid the memory leak, it should be plugged out of the View hierarchy.
            mMapsCard.removeAllViews();
            mMapsCard = null;
        }

        if (mCar != null) {
            mCar.disconnect();
            mCar = null;
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);

        if (isDewdActive()) {
            // no-op
            return;
        }

        initializeCards();
        syncEmbeddedNavigationHost();
    }

    private void initializeCards() {
        if (mHomeCardModules == null) {
            mHomeCardModules = new ArraySet<>();
            for (String providerClassName : getResources().getStringArray(
                    R.array.config_homeCardModuleClasses_vertical)) {
                try {
                    long reflectionStartTime = System.currentTimeMillis();
                    HomeCardModule cardModule = (HomeCardModule)
                            Class.forName(providerClassName).newInstance();
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
        transaction.commitNowAllowingStateLoss();
    }

    /** Logs that the Activity is ready. Used for startup time diagnostics. */
    private void maybeLogReady() {
        boolean isResumed = isResumed();
        if (isResumed) {
            // We should report every time - the Android framework will take care of logging just
            // when it's effectively drawn for the first time, but....
            reportFullyDrawn();
            if (!mIsReadyLogged) {
                // ... we want to manually check that the Log.i below (which is useful to show
                // the user id) is only logged once (otherwise it would be logged every time the
                // user taps Home)
                Log.i(TAG, "Launcher for user " + getUserId() + " is ready");
                mIsReadyLogged = true;
            }
        }
    }

    /** Brings the Car Launcher to the foreground. */
    private void bringToForeground() {
        if (mCarLauncherTaskId != INVALID_TASK_ID) {
            mActivityManager.moveTaskToFront(mCarLauncherTaskId,  /* flags= */ 0);
        }
    }

    @VisibleForTesting
    protected Intent getMapsIntent() {
        Intent mapIntent = mUseSmallCanvasOptimizedMap
                ? CarLauncherUtils.getSmallCanvasOptimizedMapIntent(this)
                : CarLauncherUtils.getMapsIntent(this);

        Log.d(TAG, "Building maps intent with nav UI mode=" + mNavUiMode
            + ", smallCanvasOptimized=" + mUseSmallCanvasOptimizedMap);

        // Don't want to show this Activity in Recents.
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
        mapIntent.putExtra(CarLauncherUtils.EXTRA_NAVIGATION_UI_MODE, mNavUiMode);
        return mapIntent;
    }

    private void setupContentObserversForTos() {
        if (AppLauncherUtils.tosStatusUninitialized(/* context = */ this)
                || !AppLauncherUtils.tosAccepted(/* context = */ this)) {
            Log.i(TAG, "TOS not accepted, setting up content observers for TOS state");
        } else {
            Log.i(TAG,
                    "TOS accepted, state will remain accepted, don't need to observe this value");
            return;
        }
        mTosContentObserver = new ContentObserver(new Handler()) {
            @Override
            public void onChange(boolean selfChange) {
                super.onChange(selfChange);
                // Release the task view and re-initialize the remote car task view with the new
                // maps intent whenever an onChange is received. This is because the TOS state
                // can go from uninitialized to not accepted during which there could be a race
                // condition in which the maps activity is from the uninitialized state.
                Set<String> tosDisabledApps = AppLauncherUtils.getTosDisabledPackages(
                        getBaseContext());
                boolean tosAccepted = AppLauncherUtils.tosAccepted(getBaseContext());
                Log.i(TAG, "TOS state updated:" + tosAccepted);
                if (DEBUG) {
                    Log.d(TAG, "TOS disabled apps:" + tosDisabledApps);
                }

                if (mCarLauncherViewModel != null
                        && mCarLauncherViewModel.getRemoteCarTaskView().getValue() != null) {
                    // Reinitialize the remote car task view with the new maps intent
                    mCarLauncherViewModel.initializeRemoteCarTaskView(getMapsIntent());
                    observeRemoteCarTaskView();
                }

                if (tosAccepted) {
                    unregisterTosContentObserver();
                }
            }
        };
        getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(KEY_UNACCEPTED_TOS_DISABLED_APPS),
                /* notifyForDescendants*/ false,
                mTosContentObserver);
    }

    /** Returns {@code true} if the launcher is currently running on a passenger display. */
    private boolean isPassengerDisplay() {
        UserManager um = getSystemService(UserManager.class);
        return getDisplayId() != Display.DEFAULT_DISPLAY
                || um.isVisibleBackgroundUsersOnDefaultDisplaySupported();
    }

    /** Returns {@code true} if the declarative launcher configuration is active. */
    private boolean isDewdActive() {
        // Note: for now, passengers do not support dewd
        return !isPassengerDisplay() && getResources().getBoolean(R.bool.config_useDewdLauncher);
    }
}
