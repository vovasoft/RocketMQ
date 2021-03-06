/**
 * $Id: StoreStatsService.java 1831 2013-05-16 01:39:51Z shijia.wxr $
 */
package com.alibaba.rocketmq.store;

import java.text.MessageFormat;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.rocketmq.common.MixAll;
import com.alibaba.rocketmq.common.ServiceThread;


/**
 * 存储层内部统计服务
 * 
 * @author vintage.wang@gmail.com shijia.wxr@taobao.com
 */
public class StoreStatsService extends ServiceThread {
    static class CallSnapshot {
        public final long timestamp;
        public final long callTimesTotal;


        public CallSnapshot(long timestamp, long callTimesTotal) {
            this.timestamp = timestamp;
            this.callTimesTotal = callTimesTotal;
        }


        public static double getTPS(final CallSnapshot begin, final CallSnapshot end) {
            long total = end.callTimesTotal - begin.callTimesTotal;
            Long time = end.timestamp - begin.timestamp;

            double tps = total / time.doubleValue();

            return tps * 1000;
        }
    }

    private static final Logger log = LoggerFactory.getLogger(MixAll.StoreLoggerName);
    // 启动时间
    private long messageStoreBootTimestamp = System.currentTimeMillis();
    // putMessage，写入整个消息耗时，含加锁竟争时间（单位毫秒）
    private volatile long putMessageEntireTimeMax = 0;
    // getMessage，读取一批消息耗时，含加锁竟争时间（单位毫秒）
    private volatile long getMessageEntireTimeMax = 0;

    // for putMessageEntireTimeMax
    private ReentrantLock lockPut = new ReentrantLock();
    // for getMessageEntireTimeMax
    private ReentrantLock lockGet = new ReentrantLock();

    // putMessage，失败次数
    private final AtomicLong putMessageFailedTimes = new AtomicLong(0);
    // putMessage，调用总数
    private final AtomicLong putMessageTimesTotal = new AtomicLong(0);
    // getMessage，调用总数
    private final AtomicLong getMessageTimesTotalFound = new AtomicLong(0);
    private final AtomicLong getMessageTransferedMsgCount = new AtomicLong(0);
    private final AtomicLong getMessageTimesTotalMiss = new AtomicLong(0);
    // putMessage，Message Size Total
    private final AtomicLong putMessageSizeTotal = new AtomicLong(0);
    // putMessage，耗时分布
    private final AtomicLong[] putMessageDistributeTime = new AtomicLong[7];
    // DispatchMessageService，缓冲区最大值
    private volatile long dispatchMaxBuffer = 0;

    // 采样频率，1秒钟采样一次
    private static final int FrequencyOfSampling = 1000;
    // 采样最大记录数，超过则将之前的删除掉
    private static final int MaxRecordsOfSampling = 60 * 10;
    // 针对采样线程加锁
    private ReentrantLock lockSampling = new ReentrantLock();

    // put最近10分钟采样
    private final LinkedList<CallSnapshot> putTimesList = new LinkedList<CallSnapshot>();
    // get最近10分钟采样
    private final LinkedList<CallSnapshot> getTimesFoundList = new LinkedList<CallSnapshot>();
    private final LinkedList<CallSnapshot> getTimesMissList = new LinkedList<CallSnapshot>();
    private final LinkedList<CallSnapshot> transferedMsgCountList = new LinkedList<CallSnapshot>();

    // 打印TPS数据间隔时间，单位秒，1分钟
    private static int PrintTPSInterval = 60 * 1;
    private long lastPrintTimestamp = System.currentTimeMillis();


    public StoreStatsService() {
        for (int i = 0; i < this.putMessageDistributeTime.length; i++) {
            putMessageDistributeTime[i] = new AtomicLong(0);
        }
    }


    public long getPutMessageEntireTimeMax() {
        return putMessageEntireTimeMax;
    }


    public void setPutMessageEntireTimeMax(long value) {
        // 微秒
        if (value <= 0) {
            this.putMessageDistributeTime[0].incrementAndGet();
        }
        // 几毫秒
        else if (value < 10) {
            this.putMessageDistributeTime[1].incrementAndGet();
        }
        // 几十毫秒
        else if (value < 100) {
            this.putMessageDistributeTime[2].incrementAndGet();
        }
        // 几百毫秒（500毫秒以内）
        else if (value < 500) {
            this.putMessageDistributeTime[3].incrementAndGet();
        }
        // 几百毫秒（500毫秒以上）
        else if (value < 1000) {
            this.putMessageDistributeTime[4].incrementAndGet();
        }
        // 几秒
        else if (value < 10000) {
            this.putMessageDistributeTime[5].incrementAndGet();
        }
        // 大等于10秒
        else {
            this.putMessageDistributeTime[6].incrementAndGet();
        }

        if (value > this.putMessageEntireTimeMax) {
            this.lockPut.lock();
            this.putMessageEntireTimeMax =
                    value > this.putMessageEntireTimeMax ? value : this.putMessageEntireTimeMax;
            this.lockPut.unlock();
        }
    }


    public long getGetMessageEntireTimeMax() {
        return getMessageEntireTimeMax;
    }


    public void setGetMessageEntireTimeMax(long value) {
        if (value > this.getMessageEntireTimeMax) {
            this.lockGet.lock();
            this.getMessageEntireTimeMax =
                    value > this.getMessageEntireTimeMax ? value : this.getMessageEntireTimeMax;
            this.lockGet.unlock();
        }
    }


    public AtomicLong getPutMessageTimesTotal() {
        return putMessageTimesTotal;
    }


    public AtomicLong getPutMessageSizeTotal() {
        return putMessageSizeTotal;
    }


    public long getDispatchMaxBuffer() {
        return dispatchMaxBuffer;
    }


    public void setDispatchMaxBuffer(long value) {
        this.dispatchMaxBuffer = value > this.dispatchMaxBuffer ? value : this.dispatchMaxBuffer;
    }


    private String getPutMessageDistributeTimeStringInfo(Long total) {
        final StringBuilder sb = new StringBuilder(512);

        for (AtomicLong i : this.putMessageDistributeTime) {
            long value = i.get();
            double ratio = value / total.doubleValue();
            sb.append("\r\n\t\t");
            sb.append(value + "(" + (ratio * 100) + "%)");
        }

        return sb.toString();
    }


    private String getFormatRuntime() {
        final long MILLISECOND = 1;
        final long SECOND = 1000 * MILLISECOND;
        final long MINUTE = 60 * SECOND;
        final long HOUR = 60 * MINUTE;
        final long DAY = 24 * HOUR;
        final MessageFormat TIME = new MessageFormat("[ {0} days, {1} hours, {2} minutes, {3} seconds ]");

        long time = System.currentTimeMillis() - this.messageStoreBootTimestamp;
        long days = time / DAY;
        long hours = (time % DAY) / HOUR;
        long minutes = (time % HOUR) / MINUTE;
        long seconds = (time % MINUTE) / SECOND;
        return TIME.format(new Long[] { days, hours, minutes, seconds });
    }


    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder(1024);
        Long totalTimes = this.putMessageTimesTotal.get();
        if (0 == totalTimes) {
            totalTimes = 1L;
        }

        sb.append("\truntime: " + this.getFormatRuntime() + "\r\n");
        sb.append("\tputMessageEntireTimeMax: " + this.putMessageEntireTimeMax + "\r\n");
        sb.append("\tputMessageTimesTotal: " + totalTimes + "\r\n");
        sb.append("\tputMessageSizeTotal: " + this.putMessageSizeTotal.get() + "\r\n");
        sb.append("\tputMessageDistributeTime: " + this.getPutMessageDistributeTimeStringInfo(totalTimes) + "\r\n");
        sb.append("\tputMessageAverageSize: " + (this.putMessageSizeTotal.get() / totalTimes.doubleValue())
                + "\r\n");
        sb.append("\tdispatchMaxBuffer: " + this.dispatchMaxBuffer + "\r\n");
        sb.append("\tgetMessageEntireTimeMax: " + this.getMessageEntireTimeMax + "\r\n");
        sb.append("\tputTps: " + this.getPutTps() + "\r\n");
        sb.append("\tgetFoundTps: " + this.getGetFoundTps() + "\r\n");
        sb.append("\tgetMissTps: " + this.getGetMissTps() + "\r\n");
        sb.append("\tgetTotalTps: " + this.getGetTotalTps() + "\r\n");
        sb.append("\tgetTransferedTps: " + this.getGetTransferedTps() + "\r\n");
        return sb.toString();
    }


    public HashMap<String, String> getRuntimeInfo() {
        HashMap<String, String> result = new HashMap<String, String>(64);

        Long totalTimes = this.putMessageTimesTotal.get();
        if (0 == totalTimes) {
            totalTimes = 1L;
        }

        result.put("bootimestamp", String.valueOf(this.messageStoreBootTimestamp));
        result.put("runtime", this.getFormatRuntime());
        result.put("putMessageEntireTimeMax", String.valueOf(this.putMessageEntireTimeMax));
        result.put("putMessageAverageSize",
            String.valueOf((this.putMessageSizeTotal.get() / totalTimes.doubleValue())));
        result.put("dispatchMaxBuffer", String.valueOf(this.dispatchMaxBuffer));

        return result;
    }


    private void sampling() {
        this.lockSampling.lock();

        this.putTimesList.add(new CallSnapshot(System.currentTimeMillis(), this.putMessageTimesTotal.get()));
        if (this.putTimesList.size() > (MaxRecordsOfSampling + 1)) {
            this.putTimesList.removeFirst();
        }

        this.getTimesFoundList.add(new CallSnapshot(System.currentTimeMillis(), this.getMessageTimesTotalFound
            .get()));
        if (this.getTimesFoundList.size() > (MaxRecordsOfSampling + 1)) {
            this.getTimesFoundList.removeFirst();
        }

        this.getTimesMissList
            .add(new CallSnapshot(System.currentTimeMillis(), this.getMessageTimesTotalMiss.get()));
        if (this.getTimesMissList.size() > (MaxRecordsOfSampling + 1)) {
            this.getTimesMissList.removeFirst();
        }

        this.transferedMsgCountList.add(new CallSnapshot(System.currentTimeMillis(),
            this.getMessageTransferedMsgCount.get()));
        if (this.transferedMsgCountList.size() > (MaxRecordsOfSampling + 1)) {
            this.transferedMsgCountList.removeFirst();
        }

        this.lockSampling.unlock();
    }


    private String getPutTps(int time) {
        String result = "";
        this.lockSampling.lock();
        CallSnapshot last = this.putTimesList.getLast();

        if (this.putTimesList.size() > time) {
            CallSnapshot lastBefore = this.putTimesList.get(this.putTimesList.size() - (time + 1));
            result += CallSnapshot.getTPS(lastBefore, last);
        }

        this.lockSampling.unlock();

        return result;
    }


    private String getPutTps() {
        StringBuilder sb = new StringBuilder();
        // 10秒钟
        sb.append(this.getPutTps(10));
        sb.append(" ");

        // 1分钟
        sb.append(this.getPutTps(60));
        sb.append(" ");

        // 10分钟
        sb.append(this.getPutTps(600));

        return sb.toString();
    }


    private String getGetFoundTps(int time) {
        String result = "";
        this.lockSampling.lock();
        CallSnapshot last = this.getTimesFoundList.getLast();

        if (this.getTimesFoundList.size() > time) {
            CallSnapshot lastBefore = this.getTimesFoundList.get(this.getTimesFoundList.size() - (time + 1));
            result += CallSnapshot.getTPS(lastBefore, last);
        }

        this.lockSampling.unlock();

        return result;
    }


    private String getGetFoundTps() {
        StringBuilder sb = new StringBuilder();
        // 10秒钟
        sb.append(this.getGetFoundTps(10));
        sb.append(" ");

        // 1分钟
        sb.append(this.getGetFoundTps(60));
        sb.append(" ");

        // 10分钟
        sb.append(this.getGetFoundTps(600));

        return sb.toString();
    }


    private String getGetMissTps(int time) {
        String result = "";
        this.lockSampling.lock();
        CallSnapshot last = this.getTimesMissList.getLast();

        if (this.getTimesMissList.size() > time) {
            CallSnapshot lastBefore = this.getTimesMissList.get(this.getTimesMissList.size() - (time + 1));
            result += CallSnapshot.getTPS(lastBefore, last);
        }

        this.lockSampling.unlock();

        return result;
    }


    private String getGetMissTps() {
        StringBuilder sb = new StringBuilder();
        // 10秒钟
        sb.append(this.getGetMissTps(10));
        sb.append(" ");

        // 1分钟
        sb.append(this.getGetMissTps(60));
        sb.append(" ");

        // 10分钟
        sb.append(this.getGetMissTps(600));

        return sb.toString();
    }


    private String getGetTransferedTps(int time) {
        String result = "";
        this.lockSampling.lock();
        CallSnapshot last = this.transferedMsgCountList.getLast();

        if (this.transferedMsgCountList.size() > time) {
            CallSnapshot lastBefore =
                    this.transferedMsgCountList.get(this.transferedMsgCountList.size() - (time + 1));
            result += CallSnapshot.getTPS(lastBefore, last);
        }

        this.lockSampling.unlock();

        return result;
    }


    private String getGetTransferedTps() {
        StringBuilder sb = new StringBuilder();
        // 10秒钟
        sb.append(this.getGetTransferedTps(10));
        sb.append(" ");

        // 1分钟
        sb.append(this.getGetTransferedTps(60));
        sb.append(" ");

        // 10分钟
        sb.append(this.getGetTransferedTps(600));

        return sb.toString();
    }


    private String getGetTotalTps(int time) {
        this.lockSampling.lock();
        double found = 0;
        double miss = 0;
        {
            CallSnapshot last = this.getTimesFoundList.getLast();

            if (this.getTimesFoundList.size() > time) {
                CallSnapshot lastBefore = this.getTimesFoundList.get(this.getTimesFoundList.size() - (time + 1));
                found = CallSnapshot.getTPS(lastBefore, last);
            }
        }
        {
            CallSnapshot last = this.getTimesMissList.getLast();

            if (this.getTimesMissList.size() > time) {
                CallSnapshot lastBefore = this.getTimesMissList.get(this.getTimesMissList.size() - (time + 1));
                miss = CallSnapshot.getTPS(lastBefore, last);
            }
        }

        this.lockSampling.unlock();

        return Double.toString(found + miss);
    }


    private String getGetTotalTps() {
        StringBuilder sb = new StringBuilder();
        // 10秒钟
        sb.append(this.getGetTotalTps(10));
        sb.append(" ");

        // 1分钟
        sb.append(this.getGetTotalTps(60));
        sb.append(" ");

        // 10分钟
        sb.append(this.getGetTotalTps(600));

        return sb.toString();
    }


    /**
     * 1分钟打印一次TPS
     */
    private void printTps() {
        if (System.currentTimeMillis() > (this.lastPrintTimestamp + PrintTPSInterval * 1000)) {
            this.lastPrintTimestamp = System.currentTimeMillis();

            log.info("put_tps {}", this.getPutTps(PrintTPSInterval));

            log.info("get_found_tps {}", this.getGetFoundTps(PrintTPSInterval));

            log.info("get_miss_tps {}", this.getGetMissTps(PrintTPSInterval));

            log.info("get_transfered_tps {}", this.getGetTransferedTps(PrintTPSInterval));
        }
    }


    public void run() {
        log.info(this.getServiceName() + " service started");

        while (!this.isStoped()) {
            try {
                this.waitForRunning(FrequencyOfSampling);

                this.sampling();

                this.printTps();
            }
            catch (Exception e) {
                log.warn(this.getServiceName() + " service has exception. ", e);
            }
        }

        log.info(this.getServiceName() + " service end");
    }


    @Override
    public String getServiceName() {
        return StoreStatsService.class.getSimpleName();
    }


    public AtomicLong getGetMessageTimesTotalFound() {
        return getMessageTimesTotalFound;
    }


    public AtomicLong getGetMessageTimesTotalMiss() {
        return getMessageTimesTotalMiss;
    }


    public AtomicLong getGetMessageTransferedMsgCount() {
        return getMessageTransferedMsgCount;
    }


    public AtomicLong getPutMessageFailedTimes() {
        return putMessageFailedTimes;
    }
}
