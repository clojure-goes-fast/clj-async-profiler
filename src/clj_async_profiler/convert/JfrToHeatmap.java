/*
 * Copyright The async-profiler authors
 * SPDX-License-Identifier: Apache-2.0
 */

package clj_async_profiler.convert;

import clj_async_profiler.jfr.Dictionary;
import clj_async_profiler.jfr.JfrReader;
import clj_async_profiler.jfr.StackTrace;
import clj_async_profiler.jfr.event.AllocationSample;
import clj_async_profiler.jfr.event.ContendedLock;
import clj_async_profiler.jfr.event.Event;
import clj_async_profiler.jfr.event.EventCollector;

import java.io.*;
import java.util.*;

import static clj_async_profiler.convert.Frame.TYPE_INLINED;
import static clj_async_profiler.convert.Frame.TYPE_KERNEL;

public class JfrToHeatmap extends JfrConverter {
    // private final Heatmap heatmap;
    public final ArrayList<Object> events = new ArrayList<>();
    public final NaiveCollector collector = new NaiveCollector();

    public JfrToHeatmap(JfrReader jfr) {
        super(jfr);
    }

    @Override
    protected EventCollector createCollector() {
        return new EventCollector() {
            @Override
            public void collect(Event event) {
                int extra = 0;
                byte type = 0;
                if (event instanceof AllocationSample) {
                    extra = ((AllocationSample) event).classId;
                    type = ((AllocationSample) event).tlabSize == 0 ? TYPE_KERNEL : TYPE_INLINED;
                } else if (event instanceof ContendedLock) {
                    extra = ((ContendedLock) event).classId;
                    type = TYPE_KERNEL;
                }

                long msFromStart = (event.time - jfr.chunkStartTicks) * 1_000 / jfr.ticksPerSec;
                long timeMs = jfr.chunkStartNanos / 1_000_000 + msFromStart;
                collector.addEvent(event.stackTraceId, msFromStart, JfrToHeatmap.this.jfr.durationNanos());
            }

            @Override
            public void beforeChunk() {
                // heatmap.beforeChunk();
                jfr.stackTraces.forEach(new Dictionary.Visitor<StackTrace>() {
                    @Override
                    public void visit(long traceId, StackTrace trace) {
                        collector.addStack(traceId, trace, JfrToHeatmap.this);
                    }
                });
            }

            @Override
            public void afterChunk() {
                // jfr.stackTraces.clear();
            }

            @Override
            public boolean finish() {
                // heatmap.finish(jfr.startNanos / 1_000_000);
                return false;
            }

            @Override
            public void forEach(Visitor visitor) {
                throw new AssertionError("Should not be called");
            }
        };
    }

    public static void convert(String input, String output) throws IOException {
        JfrToHeatmap converter;
        try (JfrReader jfr = new JfrReader(input)) {
            converter = new JfrToHeatmap(jfr);
            converter.convert();
        }
    }
}
