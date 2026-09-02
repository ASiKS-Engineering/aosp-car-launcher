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

package com.android.car.carlauncher.homescreen.audio;

import static android.content.pm.PackageManager.GET_RESOLVED_FILTER;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.telecom.Call;
import android.telecom.CallAudioState;
import android.telecom.TelecomManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;

import com.android.car.carlauncher.R;
import com.android.car.carlauncher.homescreen.audio.dialer.InCallIntentRouter;
import com.android.car.carlauncher.homescreen.ui.CardContent;
import com.android.car.carlauncher.homescreen.ui.CardHeader;
import com.android.car.carlauncher.homescreen.ui.DescriptiveTextWithControlsView;
import com.android.car.telephony.calling.CallComparator;
import com.android.car.telephony.calling.CallDetailLiveData;
import com.android.car.telephony.calling.InCallModel;
import com.android.car.telephony.common.CallDetail;
import com.android.car.telephony.common.TelecomUtils;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;

/**
 * The {@link HomeCardInterface.Model} for ongoing phone calls.
 */
public class InCallViewModel implements AudioModel {

    private static final String TAG = "InCallViewModel";
    private static final String CAR_APP_SERVICE_INTERFACE = "androidx.car.app.CarAppService";
    private static final String CAR_APP_ACTIVITY_INTERFACE =
            "androidx.car.app.activity.CarAppActivity";
    /** androidx.car.app.CarAppService.CATEGORY_CALLING_APP from androidx car app library. */
    private static final String CAR_APP_CATEGORY_CALLING = "androidx.car.app.category.CALLING";
    private static final boolean DEBUG = false;

    private InCallModel mInCallModel;

    protected Context mContext;
    private TelecomManager mTelecomManager;

    private PackageManager mPackageManager;
    private final Clock mElapsedTimeClock;

    private final LiveData<Call> mPrimaryCallLiveData;
    private final LiveData<CallAudioState> mCallAudioStateLiveData;
    private final LiveData<CallDetail> mCallDetailLiveData;

    private Observer<Object> mCallObserver;
    private Observer<Object> mCallAudioStateObserver;

    protected Call mCurrentCall;
    private CompletableFuture<Void> mPhoneNumberInfoFuture;

    private CardHeader mDefaultDialerCardHeader;
    private CardHeader mCardHeader;
    private CardContent mCardContent;
    private CharSequence mOngoingCallSubtitle;
    private CharSequence mDialingCallSubtitle;
    protected DescriptiveTextWithControlsView.Control mMuteButton;
    protected DescriptiveTextWithControlsView.Control mEndCallButton;
    protected DescriptiveTextWithControlsView.Control mDialpadButton;
    private Drawable mContactImageBackground;
    protected OnModelUpdateListener mOnModelUpdateListener;

    protected final InCallIntentRouter mInCallIntentRouter = InCallIntentRouter.getInstance();


    public InCallViewModel() {
        mElapsedTimeClock = SystemClock.elapsedRealtimeClock();
        mInCallModel = new InCallModel(InCallServiceManagerProvider.get(), new CallComparator());
        mPrimaryCallLiveData = mInCallModel.getPrimaryCallLiveData();
        mCallDetailLiveData = Transformations.switchMap(mPrimaryCallLiveData, call -> {
            CallDetailLiveData callDetailLiveData = new CallDetailLiveData();
            callDetailLiveData.setTelecomCall(call);
            return callDetailLiveData;
        });
        mCallAudioStateLiveData = mInCallModel.getCallAudioStateLiveData();
    }

    @Override
    public void onCreate(Context context) {
        mContext = context;
        mTelecomManager = context.getSystemService(TelecomManager.class);

        mOngoingCallSubtitle = context.getResources().getString(R.string.ongoing_call_text);
        mDialingCallSubtitle = context.getResources().getString(R.string.dialing_call_text);
        mContactImageBackground = context.getResources()
                .getDrawable(R.drawable.control_bar_contact_image_background, context.getTheme());
        initializeAudioControls();

        mPackageManager = context.getPackageManager();
        mDefaultDialerCardHeader = createCardHeader(mTelecomManager.getDefaultDialerPackage());
        mCardHeader = mDefaultDialerCardHeader;

        mCallObserver = o -> onCallChanged(mPrimaryCallLiveData.getValue());
        mPrimaryCallLiveData.observeForever(mCallObserver);

        mCallAudioStateObserver =
                o -> onCallAudioStateChanged(mInCallModel.getCallAudioStateLiveData().getValue());
        mCallAudioStateLiveData.observeForever(mCallAudioStateObserver);
    }

    @Override
    public void onDestroy(Context context) {
        if (mPhoneNumberInfoFuture != null) {
            mPhoneNumberInfoFuture.cancel(/* mayInterruptIfRunning= */true);
        }
        mPrimaryCallLiveData.removeObserver(mCallObserver);
        mCallAudioStateLiveData.removeObserver(mCallAudioStateObserver);
        mOnModelUpdateListener = null;
    }

    @Override
    public void setOnModelUpdateListener(OnModelUpdateListener onModelUpdateListener) {
        mOnModelUpdateListener = onModelUpdateListener;
    }

    @Override
    public CardHeader getCardHeader() {
        return mCardContent == null ? null : mCardHeader;
    }

    @Override
    public CardContent getCardContent() {
        return mCardContent;
    }

    /**
     * Clicking the card opens the default dialer application that fills the role of {@link
     * android.app.role.RoleManager#ROLE_DIALER}. This application will have an appropriate UI to
     * display as one of the requirements to fill this role is to provide an ongoing call UI.
     */
    @Override
    public Intent getIntent() {
        Intent intent = null;
        CallDetail callDetail = mCallDetailLiveData.getValue();
        if (callDetail != null && callDetail.isSelfManaged()) {
            String callingAppPackageName = callDetail.getCallingAppPackageName();
            if (!TextUtils.isEmpty(callingAppPackageName)) {
                if (isCarAppCallingService(callingAppPackageName)) {
                    intent = new Intent();
                    intent.setComponent(
                             new ComponentName(
                                    callingAppPackageName, CAR_APP_ACTIVITY_INTERFACE));
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                } else {
                    intent = mPackageManager.getLaunchIntentForPackage(callingAppPackageName);
                }
            }
        } else {
            intent = mPackageManager.getLaunchIntentForPackage(
                    mTelecomManager.getDefaultDialerPackage());
        }
        return intent;
    }

    /**
     * Clicking the card opens the default dialer application that fills the role of {@link
     * android.app.role.RoleManager#ROLE_DIALER}. This application will have an appropriate UI to
     * display as one of the requirements to fill this role is to provide an ongoing call UI.
     */
    public void onClick(View view) {
        Intent intent = getIntent();
        if (intent != null) {
            mInCallIntentRouter.handleInCallIntent(intent);
        } else {
            if (DEBUG) {
                Log.d(TAG, "No launch intent found to show in call ui for call : " + mCurrentCall);
            }
        }
    }

    @VisibleForTesting
    void onCallAudioStateChanged(CallAudioState audioState) {
        if (updateMuteButtonIconState(audioState)) {
            mOnModelUpdateListener.onModelUpdate(this);
        }
    }

    private void onCallChanged(Call call) {
        if (call != null) {
            mCurrentCall = call;
            handleActiveCall(mCurrentCall);
        } else if (mCurrentCall != null) {
            mCurrentCall = null;
            mCardHeader = null;
            mCardContent = null;
            mOnModelUpdateListener.onModelUpdate(this);
        }
    }

    /** Indicates whether there is an active call or not. */
    public boolean hasActiveCall() {
        return mCurrentCall != null;
    }

    protected Call getCurrentCall() {
        return mCurrentCall;
    }

    /**
     * Updates the mute button according to the CallAudioState supplied.
     * returns true if the model was updated and needs to refresh the view
     */
    @VisibleForTesting
    boolean updateMuteButtonIconState(CallAudioState audioState) {
        int[] iconState = mMuteButton.getIcon().getState();
        boolean selectedStateExists = ArrayUtils.contains(iconState,
                android.R.attr.state_selected);

        if (selectedStateExists == audioState.isMuted()) {
            // no need to update since the drawable was already muted
            return false;
        }

        if (audioState.isMuted()) {
            iconState = ArrayUtils.appendInt(iconState,
                    android.R.attr.state_selected);
        } else {
            iconState = ArrayUtils.removeInt(iconState,
                    android.R.attr.state_selected);
        }
        mMuteButton
                .getIcon()
                .setState(iconState);
        return true;
    }

    /**
     * Updates the model's content using the given phone number.
     */
    @VisibleForTesting
    void updateModelWithPhoneNumber(String number, /* @Call.CallState */ int callState) {
        String formattedNumber = TelecomUtils.getFormattedNumber(mContext, number);
        mCardContent = createPhoneCardContent(null, formattedNumber, callState);
        mOnModelUpdateListener.onModelUpdate(this);
    }

    /**
     * Updates the model's content using the given {@link TelecomUtils.PhoneNumberInfo}. If there is
     * a corresponding contact, use the contact's name and avatar. If the contact doesn't have an
     * avatar, use an icon with their first initial.
     */
    @VisibleForTesting
    void updateModelWithContact(TelecomUtils.PhoneNumberInfo phoneNumberInfo,
            /* @Call.CallState */ int callState) {
        String contactName = null;
        String initials = null;
        // If current call details exist, use the caller display name or contact display name first.
        if (mCurrentCall != null) {
            contactName = mCurrentCall.getDetails().getCallerDisplayName();
            if (TextUtils.isEmpty(contactName)) {
                contactName = mCurrentCall.getDetails().getContactDisplayName();
            }
        }
        if (TextUtils.isEmpty(contactName)) {
            contactName = phoneNumberInfo.getDisplayName();
            initials = phoneNumberInfo.getInitials();
        } else {
            initials = TelecomUtils.getInitials(contactName);
        }
        Drawable contactImage = null;
        if (phoneNumberInfo.getAvatarUri() != null) {
            try {
                InputStream inputStream = mContext.getContentResolver().openInputStream(
                        phoneNumberInfo.getAvatarUri());
                contactImage = Drawable.createFromStream(inputStream,
                        phoneNumberInfo.getAvatarUri().toString());
            } catch (FileNotFoundException e) {
                // If no file is found for the contact's avatar URI, the icon will be set to a
                // LetterTile below.
                if (DEBUG) {
                    Log.d(TAG, "Unable to find contact avatar from Uri: "
                            + phoneNumberInfo.getAvatarUri(), e);
                }
            }
        }
        if (contactImage == null) {
            contactImage = TelecomUtils.createLetterTile(mContext, initials, contactName);
        }

        mCardContent = createPhoneCardContent(
                new CardContent.CardBackgroundImage(contactImage, mContactImageBackground),
                contactName, callState);
        mOnModelUpdateListener.onModelUpdate(this);
    }

    protected void handleActiveCall(@NonNull Call call) {
        /* @Call.CallState */ int callState = call.getDetails().getState();
        CallDetail callDetails = CallDetail.fromTelecomCallDetail(call.getDetails());
        if (callDetails.isSelfManaged()) {
            String packageName = callDetails.getCallingAppPackageName();
            mCardHeader = createCardHeader(packageName);
        }
        if (mCardHeader == null) {
            // Default to show the default dialer app info
            mCardHeader = mDefaultDialerCardHeader;
        }

        // If the home app does not have permission to read contacts, just display the
        // phone number
        if (ContextCompat.checkSelfPermission(mContext, Manifest.permission.READ_CONTACTS)
                != PackageManager.PERMISSION_GRANTED) {
            updateModelWithPhoneNumber(callDetails.getNumber(), callState);
            return;
        }

        if (mPhoneNumberInfoFuture != null) {
            mPhoneNumberInfoFuture.cancel(/* mayInterruptIfRunning= */ true);
        }
        mPhoneNumberInfoFuture = TelecomUtils.getPhoneNumberInfo(mContext,
                        callDetails.getNumber())
                .thenAcceptAsync(x -> updateModelWithContact(x, callState),
                        mContext.getMainExecutor());
    }

    private CardContent createPhoneCardContent(CardContent.CardBackgroundImage image,
            CharSequence title, /* @Call.CallState */ int callState) {
        switch (callState) {
            case Call.STATE_DIALING:
                return new DescriptiveTextWithControlsView(image, title, mDialingCallSubtitle,
                        mMuteButton, mEndCallButton, mDialpadButton);
            case Call.STATE_ACTIVE:
                long callStartTime =
                        mCurrentCall != null ? mCurrentCall.getDetails().getConnectTimeMillis()
                                - System.currentTimeMillis() + mElapsedTimeClock.millis()
                                : mElapsedTimeClock.millis();
                return new DescriptiveTextWithControlsView(image, title, mOngoingCallSubtitle,
                        callStartTime, mMuteButton, mEndCallButton, mDialpadButton);
            default:
                if (DEBUG) {
                    Log.d(TAG, "Call State " + callState
                            + " is not currently supported by this model");
                }
                return null;
        }
    }

    protected void initializeAudioControls() {
        mMuteButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_mute_activatable),
                v -> {
                    boolean toggledValue = !v.isSelected();
                    InCallServiceManagerProvider.get().setMuted(toggledValue);
                    v.setSelected(toggledValue);
                });
        mEndCallButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_call_end_button),
                v -> mCurrentCall.disconnect());
        mDialpadButton = new DescriptiveTextWithControlsView.Control(
                mContext.getDrawable(R.drawable.ic_dialpad), this::onClick);
    }

    @VisibleForTesting
    void updateMuteButtonDrawableState(int[] state) {
        mMuteButton.getIcon().setState(state);
    }

    @VisibleForTesting
    int[] getMuteButtonDrawableState() {
        return mMuteButton.getIcon().getState();
    }

    private CardHeader createCardHeader(String packageName) {
        if (!TextUtils.isEmpty(packageName)) {
            try {
                ApplicationInfo applicationInfo = mPackageManager.getApplicationInfo(
                        packageName, PackageManager.ApplicationInfoFlags.of(0));
                Drawable appIcon = mPackageManager.getApplicationIcon(applicationInfo);
                CharSequence appName = mPackageManager.getApplicationLabel(applicationInfo);
                return new CardHeader(appName, appIcon);
            } catch (PackageManager.NameNotFoundException e) {
                Log.w(TAG, "No such package found " + packageName, e);
            }
        }
        return null;
    }

    private boolean isCarAppCallingService(String packageName) {
        // Check that app is integrated with CAL and handles calls
        Intent serviceIntent =
                new Intent(CAR_APP_SERVICE_INTERFACE)
                        .setPackage(packageName)
                        .addCategory(CAR_APP_CATEGORY_CALLING);

        if (mPackageManager.queryIntentServices(serviceIntent, GET_RESOLVED_FILTER).isEmpty()) {
            return false;
        }

        // Check that app has CAL activity
        Intent activityIntent = new Intent();
        activityIntent.setComponent(new ComponentName(packageName, CAR_APP_ACTIVITY_INTERFACE));

        return mPackageManager
                .resolveActivity(activityIntent, PackageManager.MATCH_DEFAULT_ONLY) != null;
    }
}
