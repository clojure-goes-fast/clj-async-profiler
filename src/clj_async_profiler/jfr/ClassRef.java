/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package clj_async_profiler.jfr;

public class ClassRef {
    public final long name;

    public ClassRef(long name) {
        this.name = name;
    }
}
