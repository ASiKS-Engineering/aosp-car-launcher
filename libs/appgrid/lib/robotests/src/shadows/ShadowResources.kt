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

package shadows

import android.annotation.BoolRes
import android.content.res.Resources
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

/** Shadow of [Resources]. */
@Implements(Resources::class)
class ShadowResources {
    @Implementation
    fun getBoolean(@BoolRes id: Int): Boolean =
            booleanResourceMap.getOrDefault(id, defaultValue = false)

    companion object {
        private var booleanResourceMap = mutableMapOf<Int, Boolean>()

        fun setBoolean(id: Int, value: Boolean) {
            booleanResourceMap[id] = value
        }

        fun reset() {
            booleanResourceMap.clear()
        }
    }
}
