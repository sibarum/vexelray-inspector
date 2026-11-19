package dev.sibarum.inspector.windows;

import dev.sibarum.inspector.core.ProcessMeters;
import dev.supirvast.ffi.Ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.util.Optional;

import static java.lang.foreign.ValueLayout.ADDRESS;
import static java.lang.foreign.ValueLayout.JAVA_INT;
import static java.lang.foreign.ValueLayout.JAVA_LONG;

/**
 * A process read on Windows: {@code GetProcessTimes}, {@code K32GetProcessMemoryInfo} and {@code GlobalMemoryStatusEx},
 * on a handle opened once with {@code PROCESS_QUERY_LIMITED_INFORMATION}, which a process of the same user grants.
 *
 * <p>Its CPU times are accounted in scheduler ticks (15.6 ms by default), so a second's share is good to a few percent
 * of one core. {@code QueryProcessCycleTime} is exact but counts cycles, and turning them into time needs the TSC's
 * rate, which the stack's {@code ProcessCpu} is to settle (framework-notes FN-25).
 *
 * <p>Every downcall here is in this module's native-image metadata.
 */
public final class WindowsProcessMeters implements ProcessMeters {

    /** Nothing in a static initializer calls native code ({@code Ffi}'s rule); binding is lazy, on first use. */
    public WindowsProcessMeters() {
    }

    @Override
    public Reader open(long pid) {
        return Handle.open(pid);
    }

    /** One process's handle and the buffers its readings are read into, reused for every reading. */
    private static final class Handle implements Reader {

        private static final SymbolLookup LIB = Ffi.library("kernel32");
        private static final MethodHandle OpenProcess = Ffi.downcall(LIB, "OpenProcess",
                FunctionDescriptor.of(ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT));
        private static final MethodHandle CloseHandle = Ffi.downcall(LIB, "CloseHandle",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));
        private static final MethodHandle GetProcessTimes = Ffi.downcall(LIB, "GetProcessTimes",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS));
        private static final MethodHandle K32GetProcessMemoryInfo = Ffi.downcall(LIB, "K32GetProcessMemoryInfo",
                FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT));
        private static final MethodHandle GlobalMemoryStatusEx = Ffi.downcall(LIB, "GlobalMemoryStatusEx",
                FunctionDescriptor.of(JAVA_INT, ADDRESS));

        private static final int PROCESS_QUERY_LIMITED_INFORMATION = 0x1000;
        /** {@code PROCESS_MEMORY_COUNTERS} on 64-bit: two DWORDs, then eight SIZE_Ts; the working set at 16. */
        private static final int COUNTERS_SIZE = 72;
        private static final long WORKING_SET = 16;
        /** {@code MEMORYSTATUSEX}: two DWORDs, then seven DWORDLONGs; total and available physical at 8 and 16. */
        private static final int STATUS_SIZE = 64;
        private static final long TOTAL_PHYS = 8;
        private static final long AVAIL_PHYS = 16;

        private final Arena arena = Arena.ofShared();
        private final MemorySegment process;
        private final MemorySegment creation;
        private final MemorySegment exit;
        private final MemorySegment kernel;
        private final MemorySegment user;
        private final MemorySegment counters;
        private final MemorySegment status;

        private Handle(MemorySegment process) {
            this.process = process;
            this.creation = arena.allocate(JAVA_LONG);
            this.exit = arena.allocate(JAVA_LONG);
            this.kernel = arena.allocate(JAVA_LONG);
            this.user = arena.allocate(JAVA_LONG);
            this.counters = arena.allocate(COUNTERS_SIZE, 8);
            this.status = arena.allocate(STATUS_SIZE, 8);
        }

        static Handle open(long pid) {
            MemorySegment h;
            try {
                h = (MemorySegment) OpenProcess.invokeExact(PROCESS_QUERY_LIMITED_INFORMATION, 0, (int) pid);
            } catch (Throwable t) {
                throw new IllegalStateException("OpenProcess failed: " + t, t);
            }
            if (h.equals(MemorySegment.NULL)) {
                throw new IllegalStateException("OpenProcess refused pid " + pid);
            }
            return new Handle(h);
        }

        @Override
        public Optional<Sample> read() {
            try {
                if ((int) GetProcessTimes.invokeExact(process, creation, exit, kernel, user) == 0) {
                    return Optional.empty();
                }
                // FILETIMEs, in 100 ns units.
                long cpu = (kernel.get(JAVA_LONG, 0) + user.get(JAVA_LONG, 0)) * 100L;
                counters.set(JAVA_INT, 0, COUNTERS_SIZE);
                long resident = (int) K32GetProcessMemoryInfo.invokeExact(process, counters, COUNTERS_SIZE) == 0 ? -1L
                        : counters.get(JAVA_LONG, WORKING_SET);
                status.set(JAVA_INT, 0, STATUS_SIZE);
                boolean machine = (int) GlobalMemoryStatusEx.invokeExact(status) != 0;
                return Optional.of(new Sample(cpu, resident, machine ? status.get(JAVA_LONG, TOTAL_PHYS) : -1L,
                        machine ? status.get(JAVA_LONG, AVAIL_PHYS) : -1L, Runtime.getRuntime().availableProcessors()));
            } catch (Throwable t) {
                return Optional.empty();
            }
        }

        @Override
        public void close() {
            try {
                int ignored = (int) CloseHandle.invokeExact(process);
            } catch (Throwable ignored) {
                // The handle of a process already gone closes all the same; nothing else to do.
            }
            arena.close();
        }
    }
}
