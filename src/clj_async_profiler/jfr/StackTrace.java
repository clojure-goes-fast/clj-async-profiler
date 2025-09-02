/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package clj_async_profiler.jfr;

public class StackTrace {
    public final long[] methods;
    public final byte[] types;
    public final int[] locations;

    public StackTrace(long[] methods, byte[] types, int[] locations) {
        this.methods = methods;
        this.types = types;
        this.locations = locations;
    }
}
