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

package com.android.car.carlauncher.widgets;

import android.appwidget.AppWidgetHostView;
import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.util.SizeF;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.RemoteViews;

import com.android.car.carlauncher.R;
import com.android.car.oem.tokens.Token;

import java.util.List;

/**
 * Custom {@link AppWidgetHostView} used in the car launcher to display app widgets.
 * It handles custom sizing and context creation for widget inflation,
 * particularly for widgets containing remote compose documents.
 */
public class CarAppWidgetHostView extends BaseLauncherAppWidgetHostView {
    private static final String TAG = "CarAppWidgetHostView";
    private AppWidgetProviderInfo mWidgetInfo = null;
    // Flag to suppress default padding applied by the AppWidgetHostView.
    // This is necessary to prevent the system's default widget padding from overriding
    // our custom layout margins and ensuring precise control over the widget's appearance.
    private boolean mDisableSetPadding = false;
    private int mWidth;
    private int mHeight;
    private final float mScaleFactor;

    public CarAppWidgetHostView(Context context) {
        super(context);
        mScaleFactor = getResources().getFloat(R.dimen.widget_scale_factor);
        setScaleX(mScaleFactor);
        setScaleY(mScaleFactor);
        setFocusable(true);
    }

    /**
     * Binds the widget with its provider information and sets its initial size.
     *
     * @param info   The {@link AppWidgetProviderInfo} for the widget.
     * @param width  The width of the host view.
     * @param height The height of the host view.
     */
    public void bind(AppWidgetProviderInfo info, int width, int height) {
        mWidgetInfo = info;

        mWidth = Math.round((info.maxResizeWidth > 0 ? Math.min(width, info.maxResizeWidth) : width)
                / mScaleFactor);
        mHeight = Math.round((info.maxResizeHeight > 0 ? Math.min(height, info.maxResizeHeight)
                : height) / mScaleFactor);

        Bundle options = new Bundle();
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, mWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, mWidth);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, mHeight);
        options.putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, mHeight);

        updateAppWidgetSize(options, List.of(new SizeF(mWidth, mHeight)));
    }

    private Context getRemoteContext() {
        if (mWidgetInfo == null) {
            return mContext;
        }

        String widgetPackageName = mWidgetInfo.provider.getPackageName();

        try {
            return mContext.createPackageContext(widgetPackageName, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return mContext;
        }
    }

    @Override
    public void setAppWidget(int appWidgetId, AppWidgetProviderInfo info) {
        mDisableSetPadding = true;
        super.setAppWidget(appWidgetId, info);
        mDisableSetPadding = false;
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (!mDisableSetPadding) {
            super.setPadding(left, top, right, bottom);
        }
    }

    @Override
    public void updateAppWidget(RemoteViews remoteViews) {
        if (remoteViews != null) {
            Context contextToUse = Token.createOemStyledContext(getRemoteContext());
            View view = remoteViews.apply(contextToUse, this);

            removeAllViews();
            prepareView(view);

            LayoutParams lp = (LayoutParams) getLayoutParams();
            lp.width = mWidth;
            lp.height = mHeight;
            lp.gravity = Gravity.CENTER;
            setLayoutParams(lp);

            addView(view);
        } else {
            super.updateAppWidget(remoteViews);
        }
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        switch (ev.getAction()) {
            case MotionEvent.ACTION_DOWN ->
                // Request the parent to not intercept touch events for the duration of this
                // gesture.
                // This ensures that scrollable widgets (like Lists) can receive touch events
                // and scroll internally.
                // Note: This has the side effect that starting a drag on a non-scrollable widget
                // will not scroll the parent page. This is a known trade-off to support
                // scrollable widgets.
                    getParent().requestDisallowInterceptTouchEvent(true);
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                // Re-allow interception when the gesture finishes
                    getParent().requestDisallowInterceptTouchEvent(false);
        }
        return super.onInterceptTouchEvent(ev);
    }
}
