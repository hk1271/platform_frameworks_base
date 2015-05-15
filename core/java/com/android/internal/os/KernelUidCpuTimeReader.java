/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.internal.os;

import android.annotation.Nullable;
import android.text.TextUtils;
import android.util.Slog;
import android.util.SparseLongArray;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;

/**
 * Reads /proc/uid_cputime/show_uid_stat which has the line format:
 *
 * uid: user_time_micro_seconds system_time_micro_seconds
 *
 * This provides the time a UID's processes spent executing in user-space and kernel-space.
 * The file contains a monotonically increasing count of time for a single boot. This class
 * maintains the previous results of a call to {@link #readDelta} in order to provide a proper
 * delta.
 */
public class KernelUidCpuTimeReader {
    private static final String TAG = "KernelUidCpuTimeReader";
    private static final String sProcFile = "/proc/uid_cputime/show_uid_stat";
    private static final String sRemoveUidProcFile = "/proc/uid_cputime/remove_uid_range";

    /**
     * Callback interface for processing each line of the proc file.
     */
    public interface Callback {
        void onUidCpuTime(int uid, long userTimeUs, long systemTimeUs);
    }

    private SparseLongArray mLastUserTimeUs = new SparseLongArray();
    private SparseLongArray mLastSystemTimeUs = new SparseLongArray();

    /**
     * Reads the proc file, calling into the callback with a delta of time for each UID.
     * @param callback The callback to invoke for each line of the proc file. If null,
     *                 the data is consumed and subsequent calls to readDelta will provide
     *                 a fresh delta.
     */
    public void readDelta(@Nullable Callback callback) {
        try (BufferedReader reader = new BufferedReader(new FileReader(sProcFile))) {
            TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(' ');
            String line;
            while ((line = reader.readLine()) != null) {
                splitter.setString(line);
                final String uidStr = splitter.next();
                final int uid = Integer.parseInt(uidStr.substring(0, uidStr.length() - 1), 10);
                final long userTimeUs = Long.parseLong(splitter.next(), 10);
                final long systemTimeUs = Long.parseLong(splitter.next(), 10);

                if (callback != null) {
                    long userTimeDeltaUs = userTimeUs;
                    long systemTimeDeltaUs = systemTimeUs;
                    int index = mLastUserTimeUs.indexOfKey(uid);
                    if (index >= 0) {
                        userTimeDeltaUs -= mLastUserTimeUs.valueAt(index);
                        systemTimeDeltaUs -= mLastSystemTimeUs.valueAt(index);

                        if (userTimeDeltaUs < 0 || systemTimeDeltaUs < 0) {
                            // The UID must have been removed from accounting, then added back.
                            userTimeDeltaUs = userTimeUs;
                            systemTimeDeltaUs = systemTimeUs;
                        }
                    }

                    if (userTimeDeltaUs != 0 || systemTimeDeltaUs != 0) {
                        callback.onUidCpuTime(uid, userTimeDeltaUs, systemTimeDeltaUs);
                    }
                }
                mLastUserTimeUs.put(uid, userTimeUs);
                mLastSystemTimeUs.put(uid, systemTimeUs);
            }
        } catch (IOException e) {
            Slog.e(TAG, "Failed to read uid_cputime", e);
        }
    }

    /**
     * Removes the UID from the kernel module and from internal accounting data.
     * @param uid The UID to remove.
     */
    public void removeUid(int uid) {
        int index = mLastUserTimeUs.indexOfKey(uid);
        if (index >= 0) {
            mLastUserTimeUs.removeAt(index);
            mLastSystemTimeUs.removeAt(index);
        }

        try (FileWriter writer = new FileWriter(sRemoveUidProcFile)) {
            writer.write(Integer.toString(uid) + "-" + Integer.toString(uid));
            writer.flush();
        } catch (IOException e) {
            Slog.e(TAG, "failed to remove uid from uid_cputime module", e);
        }
    }
}
