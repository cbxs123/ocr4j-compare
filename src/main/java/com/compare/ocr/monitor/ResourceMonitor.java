package com.compare.ocr.monitor;

import oshi.SystemInfo;
import oshi.hardware.CentralProcessor;
import oshi.hardware.GlobalMemory;
import oshi.hardware.HardwareAbstractionLayer;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.List;

public class ResourceMonitor {
    private final SystemInfo systemInfo;
    private final OperatingSystem os;
    private final HardwareAbstractionLayer hal;
    private final int currentPid;
    private final MemoryMXBean memoryMXBean;
    private final List<GarbageCollectorMXBean> gcBeans;

    private long[] prevTicks;
    private long prevProcessCpuTime;
    private long prevUpTime;

    public record Snapshot(
            long jvmHeapUsedBytes,
            long jvmHeapMaxBytes,
            long nativeRssBytes,
            long nativeVirtualBytes,
            double processCpuLoadPercent,
            double systemCpuLoadPercent,
            long gcTimeMillis
    ) {
        public double jvmHeapUsedMB() {
            return jvmHeapUsedBytes / (1024.0 * 1024.0);
        }

        public double nativeRssMB() {
            return nativeRssBytes / (1024.0 * 1024.0);
        }
    }

    public ResourceMonitor() {
        this.systemInfo = new SystemInfo();
        this.os = systemInfo.getOperatingSystem();
        this.hal = systemInfo.getHardware();
        this.currentPid = os.getProcessId();
        this.memoryMXBean = ManagementFactory.getMemoryMXBean();
        this.gcBeans = ManagementFactory.getGarbageCollectorMXBeans();

        CentralProcessor processor = hal.getProcessor();
        this.prevTicks = processor.getSystemCpuLoadTicks();
        OSProcess process = os.getProcess(currentPid);
        this.prevProcessCpuTime = process != null ? (process.getKernelTime() + process.getUserTime()) : 0;
        this.prevUpTime = process != null ? process.getUpTime() : 0;
    }

    public Snapshot captureSnapshot() {
        long heapUsed = memoryMXBean.getHeapMemoryUsage().getUsed();
        long heapMax = memoryMXBean.getHeapMemoryUsage().getMax();

        OSProcess process = os.getProcess(currentPid);
        long rss = 0;
        long vms = 0;
        double processCpu = 0.0;
        if (process != null) {
            rss = process.getResidentSetSize();
            vms = process.getVirtualSize();

            long currentCpuTime = process.getKernelTime() + process.getUserTime();
            long currentUpTime = process.getUpTime();
            long cpuDiff = currentCpuTime - prevProcessCpuTime;
            long timeDiff = currentUpTime - prevUpTime;
            if (timeDiff > 0) {
                processCpu = (100.0 * cpuDiff) / (timeDiff * hal.getProcessor().getLogicalProcessorCount());
            }
            this.prevProcessCpuTime = currentCpuTime;
            this.prevUpTime = currentUpTime;
        }

        CentralProcessor processor = hal.getProcessor();
        double systemCpu = processor.getSystemCpuLoadBetweenTicks(prevTicks) * 100.0;
        this.prevTicks = processor.getSystemCpuLoadTicks();

        long gcTotalTime = 0;
        for (GarbageCollectorMXBean gc : gcBeans) {
            long t = gc.getCollectionTime();
            if (t > 0) gcTotalTime += t;
        }

        return new Snapshot(heapUsed, heapMax, rss, vms, processCpu, systemCpu, gcTotalTime);
    }
}
