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

package com.android.car.carlauncher.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;

/**
 * A {@link HorizontalScrollView} that snaps to pairs of child views together.
 */
public class SnappingHorizontalScrollView extends HorizontalScrollView {

    private boolean mIsFlinging;

    public SnappingHorizontalScrollView(Context context) {
        super(context);
    }

    public SnappingHorizontalScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public SnappingHorizontalScrollView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            mIsFlinging = false;
        }
        boolean result = super.onTouchEvent(ev);
        if ((ev.getAction() == MotionEvent.ACTION_UP || ev.getAction() == MotionEvent.ACTION_CANCEL)
                && !mIsFlinging) {
            snapToNearest();
        }
        return result;
    }

    @Override
    public void fling(int velocityX) {
        mIsFlinging = true;
        int scrollX = getScrollX();
        int targetScrollX = getSnapTarget(scrollX, velocityX);
        smoothScrollTo(targetScrollX, 0);
    }

    private void snapToNearest() {
        int scrollX = getScrollX();
        int targetScrollX = getSnapTarget(scrollX, 0);
        smoothScrollTo(targetScrollX, 0);
    }

    private int getSnapTarget(int currentScrollX, int velocityX) {
        if (getChildCount() == 0) return 0;
        ViewGroup container = (ViewGroup) getChildAt(0);
        if (container.getChildCount() == 0) return 0;

        int count = container.getChildCount();
        int closestIdx = -1;
        int minDistance = Integer.MAX_VALUE;

        // Valid snap indices: 0 (W1, W2), 1 (W2, W3), 2 (W3, W4), 3 ...
        for (int i = 0; i < count; i++) {
            View child = container.getChildAt(i);
            if (child.getVisibility() == View.GONE) {
                continue;
            }
            int childLeft = child.getLeft();
            int distance = Math.abs(childLeft - currentScrollX);

            if (distance < minDistance) {
                minDistance = distance;
                closestIdx = i;
            }
        }

        // If flinging, adjust target
        if (velocityX > 0) { // Fling right -> Next page
            int currentIdx = closestIdx;
            if (currentIdx != -1) {
                // Check if we are at or past the snap point for this item.
                // The snap point is the right edge of the previous visible item (or 0).
                int prevVisibleIdx = getPrevVisibleIndex(container, currentIdx);
                int snapPoint = (prevVisibleIdx != -1)
                        ? container.getChildAt(prevVisibleIdx).getRight() : 0;

                if (currentScrollX >= snapPoint) {
                    int nextIdx = getNextVisibleIndex(container, currentIdx);
                    if (nextIdx != -1) closestIdx = nextIdx;
                }
            }
        } else if (velocityX < 0) { // Fling left -> Prev page
            int currentIdx = closestIdx;
            if (currentIdx != -1) {
                // Check if we are at or before the snap point for this item.
                int prevVisibleIdx = getPrevVisibleIndex(container, currentIdx);
                int snapPoint = (prevVisibleIdx != -1)
                        ? container.getChildAt(prevVisibleIdx).getRight() : 0;

                if (currentScrollX <= snapPoint) {
                    int prevIdx = getPrevVisibleIndex(container, currentIdx);
                    if (prevIdx != -1) closestIdx = prevIdx;
                }
            }
        }

        if (closestIdx == -1) {
            return 0;
        }

        // Snap to the right edge of the previous visible child.
        // This effectively aligns the left edge of the current child (closestIdx)
        // with the left edge of the ScrollView.
        int prevVisibleIdx = getPrevVisibleIndex(container, closestIdx);
        if (prevVisibleIdx != -1) {
            return container.getChildAt(prevVisibleIdx).getRight();
        }

        return 0;
    }

    private int getNextVisibleIndex(ViewGroup container, int currentIdx) {
        for (int i = currentIdx + 1; i < container.getChildCount(); i++) {
            if (container.getChildAt(i).getVisibility() == View.VISIBLE) {
                return i;
            }
        }
        return -1;
    }

    private int getPrevVisibleIndex(ViewGroup container, int currentIdx) {
        for (int i = currentIdx - 1; i >= 0; i--) {
            if (container.getChildAt(i).getVisibility() == View.VISIBLE) {
                return i;
            }
        }
        return -1;
    }
}
