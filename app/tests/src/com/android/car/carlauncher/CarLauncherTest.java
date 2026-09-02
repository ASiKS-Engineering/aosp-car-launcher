/*
 * Copyright (C) 2020 Google Inc.
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

import static android.car.settings.CarSettings.Secure.KEY_UNACCEPTED_TOS_DISABLED_APPS;
import static android.car.settings.CarSettings.Secure.KEY_USER_TOS_ACCEPTED;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.RootMatchers.hasWindowLayoutParams;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.CoreMatchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;

import android.app.Activity;
import android.car.test.mocks.AbstractExtendedMockitoTestCase;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;
import android.testing.TestableContext;
import android.util.ArraySet;
import android.view.WindowManager;

import androidx.lifecycle.Lifecycle;
import androidx.test.InstrumentationRegistry;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.net.URISyntaxException;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class CarLauncherTest extends AbstractExtendedMockitoTestCase {

    @Rule
    public TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
    private ActivityScenario<CarLauncher> mActivityScenario;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final String TOS_MAP_INTENT = "intent:#Intent;"
            + "component=com.android.car.carlauncher/"
            + "com.android.car.carlauncher.homescreen.MapActivityTos;"
            + "action=android.intent.action.MAIN;end";

    // TOS disabled app list is non empty when TOS is not accepted.
    private static final String NON_EMPTY_TOS_DISABLED_APPS =
            "com.test.package1, com.test.package2";
    // TOS disabled app list is empty when TOS has been accepted or uninitialized.
    private static final String EMPTY_TOS_DISABLED_APPS = "";

    @Override
    protected void onSessionBuilder(CustomMockitoSessionBuilder session) {
        session.spyStatic(AppLauncherUtils.class);
        session.spyStatic(CarLauncherUtils.class);
    }

    @Before
    public void setUp() {
        assumeFalse(hasSplitscreenMultitaskingFeature());
    }

    @After
    public void tearDown() {
        if (mActivityScenario != null) {
            mActivityScenario.close();
        }
    }

    @Test
    public void onResume_mapsCard_isVisible() {
        setUpActivityScenario();

        onView(withId(R.id.maps_card))
                .inRoot(hasWindowLayoutParams())
                .check(matches(isDisplayed()));
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_MEDIA_CARD_FULLSCREEN)
    public void onResume_assistiveCard_isVisible() {
        setUpActivityScenario();

        onView(withId(R.id.top_card))
                .inRoot(hasWindowLayoutParams())
                .check(matches(isDisplayed()));
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_MEDIA_CARD_FULLSCREEN)
    public void onResume_fullscreenMediaCard_assistiveCard_isGone() {
        setUpActivityScenario();

        onView(withId(R.id.top_card))
                .inRoot(hasWindowLayoutParams())
                .check(matches(not(isDisplayed())));
    }

    @Test
    public void onResume_audioCard_isVisible() {
        setUpActivityScenario();

        onView(withId(R.id.bottom_card))
                .inRoot(hasWindowLayoutParams())
                .check(matches(isDisplayed()));
    }

    @Test
    public void onCreate_tosUnacceptedAndCanvasOptimizedMapsDisabledByTos_launchesTosMapIntent() {
        doReturn(false).when(() -> AppLauncherUtils.tosAccepted(any()));
        doReturn(true)
                .when(() -> CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(any()));
        doReturn(createIntentFromString(TOS_MAP_INTENT))
                .when(() -> CarLauncherUtils.getTosMapIntent(any()));
        doReturn(tosDisabledPackages())
                .when(() -> AppLauncherUtils.getTosDisabledPackages(any()));

        mActivityScenario = ActivityScenario.launch(CarLauncher.class);

        mActivityScenario.onActivity(activity -> {
            Intent mapIntent = activity.getMapsIntent();
            // If TOS is not accepted, and the default map is disabled by TOS, or
            // package name maybe null when resolving intent from package manager.
            // We replace the map intent with TOS map activity
            assertEquals(createIntentFromString(TOS_MAP_INTENT).getComponent().getClassName(),
                    mapIntent.getComponent().getClassName());
        });
    }

    @Test
    public void onCreate_tosUnacceptedAndDefaultMapsNotDisabledByTos_doesNotLaunchTosMapIntent() {
        doReturn(false).when(() -> AppLauncherUtils.tosAccepted(any()));
        doReturn(false)
                .when(() -> CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(any()));
        doReturn(createIntentFromString(TOS_MAP_INTENT))
                .when(() -> CarLauncherUtils.getTosMapIntent(any()));
        doReturn(tosDisabledPackages())
                .when(() -> AppLauncherUtils.getTosDisabledPackages(any()));
        // Enable the TestMapActivity
        TestMapActivity.enableActivity(mContext, true);

        mActivityScenario = ActivityScenario.launch(CarLauncher.class);

        mActivityScenario.onActivity(activity -> {
            Intent mapIntent = activity.getMapsIntent();
            // If TOS is not accepted, and the default map is not disabled by TOS,
            // these can be some other navigation app set as default,
            // package name will not be null.
            // We will not replace the map intent with TOS map activity
            assertNotEquals(
                    createIntentFromString(TOS_MAP_INTENT).getComponent().getClassName(),
                    mapIntent.getComponent().getClassName());
        });

        // Cleanup - Disable TestMapActivity
        TestMapActivity.enableActivity(mContext, false);
    }

    @Test
    public void onCreate_tosAccepted_doesNotLaunchTosMapIntent() {
        doReturn(true).when(() -> AppLauncherUtils.tosAccepted(any()));
        doReturn(false)
                .when(() -> CarLauncherUtils.isSmallCanvasOptimizedMapIntentConfigured(any()));
        doReturn(createIntentFromString(TOS_MAP_INTENT))
                .when(() -> CarLauncherUtils.getTosMapIntent(any()));
        // Enable the TestMapActivity
        TestMapActivity.enableActivity(mContext, true);

        mActivityScenario = ActivityScenario.launch(CarLauncher.class);

        mActivityScenario.onActivity(activity -> {
            Intent mapIntent = activity.getMapsIntent();
            // If TOS is accepted, map intent is not replaced
            assertNotEquals(
                    createIntentFromString(TOS_MAP_INTENT).getComponent().getClassName(),
                    mapIntent.getComponent().getClassName());
        });

        // Cleanup - Disable TestMapActivity
        TestMapActivity.enableActivity(mContext, false);
    }

    @Test
    public void onCreate_whenTosAccepted_tosContentObserverIsNull() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 2);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));
        mActivityScenario.moveToState(Lifecycle.State.RESUMED);

        mActivityScenario.onActivity(activity -> {
            // Content observer not setup because tos is accepted
            assertNull(activity.mTosContentObserver);
        });
    }

    @Test
    public void onCreate_whenTosNotAccepted_tosContentObserverIsNotNull() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 1);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                NON_EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));
        mActivityScenario.moveToState(Lifecycle.State.RESUMED);

        mActivityScenario.onActivity(activity -> {
            // Content observer is setup because tos is not accepted
            assertNotNull(activity.mTosContentObserver);
        });
    }

    @Test
    public void onCreate_whenTosNotInitialized_tosContentObserverIsNotNull() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 0);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));
        mActivityScenario.moveToState(Lifecycle.State.RESUMED);

        mActivityScenario.onActivity(activity -> {
            // Content observer is setup because tos is not initialized
            assertNotNull(activity.mTosContentObserver);
        });
    }

    @Test
    public void recreate_afterTosIsAccepted_tosStateContentObserverIsNull() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 0);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                NON_EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));

        mActivityScenario.onActivity(activity -> {
            assertNotNull(activity.mTosContentObserver); // Content observer is setup

            // Accept TOS
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 2);
            Settings.Secure.putString(mContext.getContentResolver(),
                    KEY_UNACCEPTED_TOS_DISABLED_APPS, EMPTY_TOS_DISABLED_APPS);
            activity.mTosContentObserver.onChange(true);
        });

        // Content observer is null after recreate
        mActivityScenario.onActivity(activity -> assertNull(activity.mTosContentObserver));
    }

    @Test
    public void onCreate_whenTosIsNull_tosStateContentObserverIsNotNull() {
        Settings.Secure.putString(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, null);
        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));

        // Content observer is not null after activity is created
        mActivityScenario.onActivity(activity -> assertNotNull(activity.mTosContentObserver));
    }

    @Test
    public void recreate_afterTosIsInitialized_tosStateContentObserverIsNotNull() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 0);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));

        mActivityScenario.onActivity(activity -> {
            assertNotNull(activity.mTosContentObserver); // Content observer is setup

            // Initialize TOS
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 1);
            Settings.Secure.putString(mContext.getContentResolver(),
                    KEY_UNACCEPTED_TOS_DISABLED_APPS, NON_EMPTY_TOS_DISABLED_APPS);
            activity.mTosContentObserver.onChange(true);
        });

        // Content observer is not null after recreate
        mActivityScenario.onActivity(activity -> assertNotNull(activity.mTosContentObserver));
    }

    @Test
    public void recreate_afterTosIsInitialized_releaseTaskView() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 0);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));

        mActivityScenario.onActivity(activity -> {
            assertNotNull(activity.mCarLauncherViewModel); // CarLauncherViewModel is setup

            Intent oldMapsIntent = activity.mCarLauncherViewModel.getMapsIntent();
            assertNotNull(oldMapsIntent);

            // Initialize TOS
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 1);
            Settings.Secure.putString(mContext.getContentResolver(),
                    KEY_UNACCEPTED_TOS_DISABLED_APPS, NON_EMPTY_TOS_DISABLED_APPS);
            activity.mTosContentObserver.onChange(true);

            // Different instance of maps intent since TOS has gone from uninitialized to
            // initialized
            assertThat(oldMapsIntent).isNotSameInstanceAs(
                    activity.mCarLauncherViewModel.getMapsIntent());
        });
    }

    @Test
    public void recreate_afterTosIsAccepted_releaseTaskView() {
        TestableContext mContext = new TestableContext(InstrumentationRegistry.getContext());
        Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 1);
        Settings.Secure.putString(mContext.getContentResolver(), KEY_UNACCEPTED_TOS_DISABLED_APPS,
                NON_EMPTY_TOS_DISABLED_APPS);

        mActivityScenario = ActivityScenario.launch(new Intent(mContext, CarLauncher.class));

        mActivityScenario.onActivity(activity -> {
            assertNotNull(activity.mCarLauncherViewModel); // CarLauncherViewModel is setup

            Intent oldMapsIntent = activity.mCarLauncherViewModel.getMapsIntent();
            assertNotNull(oldMapsIntent);

            // Accept TOS
            Settings.Secure.putInt(mContext.getContentResolver(), KEY_USER_TOS_ACCEPTED, 2);
            Settings.Secure.putString(mContext.getContentResolver(),
                    KEY_UNACCEPTED_TOS_DISABLED_APPS, EMPTY_TOS_DISABLED_APPS);
            activity.mTosContentObserver.onChange(true);

            // Different instance of maps intent since TOS has been accepted
            assertThat(oldMapsIntent).isNotSameInstanceAs(
                    activity.mCarLauncherViewModel.getMapsIntent());
        });
    }

    private Intent createIntentFromString(String intentString) {
        try {
            return Intent.parseUri(intentString, Intent.URI_ANDROID_APP_SCHEME);
        } catch (URISyntaxException se) {
            return null;
        }
    }

    private Set<String> tosDisabledPackages() {
        Set<String> packages = new ArraySet<>();
        packages.add("com.android.car.maps");
        packages.add("com.android.car.assistant");
        return packages;
    }

    private void setUpActivityScenario() {
        mActivityScenario = ActivityScenario.launch(CarLauncher.class);
        mActivityScenario.moveToState(Lifecycle.State.RESUMED);
        mActivityScenario.onActivity(activity -> {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    activity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
                }
            });
        });
    }

    /**
     * Checks whether the device has automotive split-screen multitasking feature enabled
     */
    private boolean hasSplitscreenMultitaskingFeature() {
        return mContext.getPackageManager()
                .hasSystemFeature(PackageManager.FEATURE_CAR_SPLITSCREEN_MULTITASKING);
    }

    public static class TestMapActivity extends Activity {
        static void enableActivity(Context context, boolean enable) {
            PackageManager pm = context.getPackageManager();
            // Enable the TestMapActivity
            ComponentName componentName = new ComponentName(
                    "com.android.car.carlauncher.test", // pkg
                    "com.android.car.carlauncher.CarLauncherTest$TestMapActivity" // cls
            );
            int state = enable
                    ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                    : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
            pm.setComponentEnabledSetting(componentName, state, PackageManager.DONT_KILL_APP);
        }
    }
}
