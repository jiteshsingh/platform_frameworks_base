/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar.notification.stack.data.repository

import com.android.systemui.common.shared.model.NotificationContainerBounds
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow

/** A repository which holds state about and controlling the appearance of the notification stack */
@SysUISingleton
class NotificationStackAppearanceRepository @Inject constructor() {
    /** The bounds of the notification stack in the current scene. */
    val stackBounds = MutableStateFlow(NotificationContainerBounds())

    /** The corner radius of the notification stack, in dp. */
    val cornerRadiusDp = MutableStateFlow(32f)
}
