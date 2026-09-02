/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.carlauncher.homescreen.audio.telecom;

import android.telecom.InCallService;

import com.android.car.carlauncher.homescreen.audio.InCallServiceManagerProvider;
import com.android.car.carlauncher.homescreen.audio.InCallViewModel;
import com.android.car.telephony.calling.InCallModel;
import com.android.car.telephony.calling.SimpleInCallServiceImpl;

/**
 * Implementation of {@link InCallService}, an {@link android.telecom} service which must be
 * implemented by an app that wishes to provide functionality for managing phone calls. This service
 * is bound by android telecom and {@link InCallViewModel}. {@link SimpleInCallServiceImpl} provides
 * an interface for call state callbacks which can be used together with {@link InCallModel} to
 * ensure call model consistency between all apps that use these classes.
 */
public class InCallServiceImpl extends SimpleInCallServiceImpl {
    private static final String TAG = "Home.InCallServiceImpl";

    @Override
    public void onCreate() {
        super.onCreate();
        InCallServiceManagerProvider.get().setInCallService(this);
    }

    @Override
    public void onDestroy() {
        InCallServiceManagerProvider.get().setInCallService(null);
        super.onDestroy();
    }
}
