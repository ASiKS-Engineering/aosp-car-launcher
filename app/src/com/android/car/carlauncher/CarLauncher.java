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
import java.util.stream.Stream;

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
    private String mNavUiMode = CarLauncherUtils.NAVIGATION_UI_MODE_HOME;
    private boolean mNavUiModeReceiverRegistered;

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
                // The nav app is always embedded now (switching is done in-place via
                // ACTION_NAVIGATION_UI_MODE_CHANGED), so if its task ever tries to restart
                // outside of the TaskView, pull the launcher back to the foreground.
                bringToForeground();
            }
        }
    };

    /**
     * Keeps CarLauncher's own UI (home cards) and CamperNavigator's UI in sync whenever a
     * system bar button broadcasts a navigation UI mode change. This is the only way the mode
     * ever changes; the embedded nav instance itself is never restarted.
     */
    private final BroadcastReceiver mNavUiModeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String mode = intent.getStringExtra(CarLauncherUtils.EXTRA_NAVIGATION_UI_MODE);
            if (mode != null) {
                applyNavUiMode(mode);
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
        mediaSource.launchActivity(CarLauncher.this, ActivityOptions.makeBasic());
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);

        if (DEBUG) {
            Log.d(TAG, "onCreate(" + getUserId() + ") displayId=" + getDisplayId());
        }
        getTheme().applyStyle(R.style.CarLauncherActivityThemeOverlay, true);

        // Check für DEWD Launcher
        if (isDewdActive()) {
            if (DEBUG) {
                Log.d(TAG, "Dewd Launcher active");
            }
            if (!scalableUi()) {
                Log.e(TAG, "Scalable UI is disabled - home screen will appear empty!");
            }
            setContentView(R.layout.home);
            return;
        }

        // 1. Zuerst das Layout basierend auf dem Modus wählen (Wichtig für RPi5)
        if (isInMultiWindowMode() || isInPictureInPictureMode()) {
            setContentView(R.layout.car_launcher_multiwindow);
        } else {
            setContentView(R.layout.car_launcher);
        }

        boolean isPassengerDisplay = isPassengerDisplay();

        // 2. Gemeinsame System-Initialisierung (Fokus, Flags, Listener)
        if (!isPassengerDisplay) {
            mUseSmallCanvasOptimizedMap =
                    CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(this);

            mActivityManager = getSystemService(ActivityManager.class);
            mCarLauncherTaskId = getTaskId();
            TaskStackChangeListeners.getInstance().registerTaskStackListener(
                    mTaskStackListener);

            // Trusted Overlay setzen für Touch-Passthrough zur Karte
            getWindow().addPrivateFlags(PRIVATE_FLAG_TRUSTED_OVERLAY);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL);

            // 3. Karten-Initialisierung (TaskView) - JETZT FÜR BEIDE MODI AKTIV
            if (!UserHelperLite.isHeadlessSystemUser(getUserId())) {
                mMapsCard = findViewById(R.id.maps_card);
                mMapsPlaceholder = findViewById(R.id.maps_placeholder_text);

                mNavUiMode = resolveInitialNavUiMode(getIntent());

                if (mMapsCard != null) {
                    setupRemoteCarTaskView(mMapsCard);
                    setupContentObserversForTos();
                }

                // Broadcast Receiver für in-place Modus-Wechsel registrieren
                ContextCompat.registerReceiver(this, mNavUiModeReceiver,
                        new IntentFilter(CarLauncherUtils.ACTION_NAVIGATION_UI_MODE_CHANGED),
                        ContextCompat.RECEIVER_EXPORTED);

                // Broadcast Receiver für Shutdown registrieren
                ContextCompat.registerReceiver(this, mShutdownReceiver,
                        new IntentFilter(Intent.ACTION_SHUTDOWN),
                        ContextCompat.RECEIVER_NOT_EXPORTED);

                mNavUiModeReceiverRegistered = true;

                // Cards initialisieren mit dem beim Boot ermittelten Modus (kein Broadcast beim Start).
                initializeCards();
            }
        } else {
            // Spezialfall Beifahrer: App-Grid statt Karte anzeigen
            getSupportFragmentManager().beginTransaction().replace(R.id.maps_card,
                    AppGridFragment.newInstance(ALL_APPS)).commit();
        }

        // Router für Media und Telefonie registrieren
        MediaLaunchRouter.getInstance().registerMediaLaunchHandler(mMediaMediaLaunchHandler);
        InCallIntentRouter.getInstance().registerInCallIntentHandler(mIntentHandler);

        // Finale UI-Aktualisierung
        initializeCards();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);

        String requestedMode = resolveInitialNavUiMode(intent);
        applyNavUiMode(requestedMode);
    }

    private void setupRemoteCarTaskView(ViewGroup parent) {
        mCarLauncherViewModel = new ViewModelProvider(this,
                new CarLauncherViewModelFactory(this, getMapsIntent()))
                .get(CarLauncherViewModel.class);

        getLifecycle().addObserver(mCarLauncherViewModel);
        addOnNewIntentListener(mCarLauncherViewModel.getNewIntentListener());

        setUpRemoteCarTaskViewObserver(parent);
    }

    private void setUpRemoteCarTaskViewObserver(ViewGroup parent) {
        mCarLauncherViewModel.getRemoteCarTaskView().observe(this, taskView -> {
            if (taskView == null) {
                if (mMapsPlaceholder != null) mMapsPlaceholder.setVisibility(View.VISIBLE);
                return;
            }
            
            if (taskView.getParent() == parent) {
                return;
            }
            
            if (taskView.getParent() != null) {
                ((ViewGroup) taskView.getParent()).removeView(taskView);
            }
            
            // Container finden (unser FrameLayout)
            ViewGroup container = findViewById(R.id.maps_card_container);
            if (container != null) {
                if (taskView.getParent() != null) {
                    ((ViewGroup) taskView.getParent()).removeView(taskView);
                }
                container.addView(taskView, 0);

                // Zwinge das TaskView hinter das Menü-Layer
                taskView.setZOrderOnTop(true);
				// Erlaube Touches durch transparente Ebenen
				taskView.setObscuredTouchRegion(null); 

                if (mMapsPlaceholder != null) mMapsPlaceholder.setVisibility(View.GONE);
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();

        Log.i(TAG, "Home Screen resumed");
        // Intentionally not touching the nav UI mode or the TaskView here: with the nav app
        // always embedded, onResume can fire for reasons unrelated to the Home/Nav buttons
        // (e.g. screen on/off). Mode switches happen exclusively via mNavUiModeReceiver.
    }

    /**
     * Applies the given navigation UI mode (HOME embedded vs. FULLSCREEN) to CarLauncher's own
     * home cards and notifies CamperNavigator so both UIs stay in lock-step. The embedded nav
     * instance itself is never restarted. Mode is persisted to LUM only at system shutdown.
     */
    private void applyNavUiMode(String mode) {
        if (mode.equals(mNavUiMode)) {
            return;
        }
        mNavUiMode = mode;
        initializeCards();
        CarLauncherUtils.setNavigationUiMode(this, mode);
    }

    private final BroadcastReceiver mShutdownReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_SHUTDOWN.equals(intent.getAction())) {
                CarLauncherUtils.persistNavigationUiMode(context, mNavUiMode);
                Log.d(TAG, "Persisted navigation UI mode to LUM on shutdown: " + mNavUiMode);
            }
        }
    };

    private String resolveInitialNavUiMode(Intent intent) {
        String requestedMode = intent != null
                ? intent.getStringExtra(CarLauncherUtils.EXTRA_NAVIGATION_UI_MODE)
                : null;
        if (CarLauncherUtils.NAVIGATION_UI_MODE_FULLSCREEN.equals(requestedMode)
                || CarLauncherUtils.NAVIGATION_UI_MODE_HOME.equals(requestedMode)) {
            return requestedMode;
        }
        return CarLauncherUtils.readPersistedNavigationUiMode(this);
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
            unregisterReceiver(mShutdownReceiver);
            mNavUiModeReceiverRegistered = false;
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
        boolean fullscreen = CarLauncherUtils.NAVIGATION_UI_MODE_FULLSCREEN.equals(mNavUiMode);
        Stream.of(R.id.bottom_card).forEach(resId -> {
            View container = findViewById(resId);
            if (container == null) return;
            boolean isRequired = !fullscreen && mHomeCardModules.stream()
                    .anyMatch(m -> m.getCardResId() == resId);
            container.setVisibility(isRequired ? View.VISIBLE : View.GONE);
        });
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

            // KORREKTUR: Wenn der Launcher aktiv in den Vordergrund kommt (Home-Button),
            // schalte in-place zurück auf den HOME Modus (Splitscreen).
            applyNavUiMode(CarLauncherUtils.NAVIGATION_UI_MODE_HOME);
        }
    }

    @VisibleForTesting
    protected Intent getMapsIntent() {
        Intent mapIntent = mUseSmallCanvasOptimizedMap
                ? CarLauncherUtils.getSmallCanvasOptimizedMapIntent(this)
                : CarLauncherUtils.getMapsIntent(this);

        // Don't want to show this Activity in Recents.
        mapIntent.addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS);
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
                    setUpRemoteCarTaskViewObserver(mMapsCard);
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
