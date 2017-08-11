/*
 *
 *   Copyright 2015-2017 Vladimir Bukhtoyarov
 *
 *     Licensed under the Apache License, Version 2.0 (the "License");
 *     you may not use this file except in compliance with the License.
 *     You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package io.github.bucket4j.local;


import io.github.bucket4j.*;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class SynchronizedBucket extends AbstractBucket {

    private final BucketConfiguration configuration;
    private final Bandwidth[] bandwidths;
    private final TimeMeter timeMeter;
    private final BucketState state;
    private final Lock lock;

    public SynchronizedBucket(BucketConfiguration configuration, TimeMeter timeMeter) {
        this(configuration, timeMeter, new ReentrantLock());
    }

    public SynchronizedBucket(BucketConfiguration configuration, TimeMeter timeMeter, Lock lock) {
        this.configuration = configuration;
        this.bandwidths = configuration.getBandwidths();
        this.timeMeter = timeMeter;
        this.state = BucketState.createInitialState(configuration, timeMeter.currentTimeNanos());
        this.lock = lock;
    }

    @Override
    public boolean isAsyncModeSupported() {
        return true;
    }

    @Override
    protected long consumeAsMuchAsPossibleImpl(long limit) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            long availableToConsume = state.getAvailableTokens(bandwidths);
            long toConsume = Math.min(limit, availableToConsume);
            if (toConsume == 0) {
                return 0;
            }
            state.consume(bandwidths, toConsume);
            return toConsume;
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected boolean tryConsumeImpl(long tokensToConsume) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            long availableToConsume = state.getAvailableTokens(bandwidths);
            if (tokensToConsume > availableToConsume) {
                return false;
            }
            state.consume(bandwidths, tokensToConsume);
            return true;
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected ConsumptionProbe tryConsumeAndReturnRemainingTokensImpl(long tokensToConsume) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            long availableToConsume = state.getAvailableTokens(bandwidths);
            if (tokensToConsume > availableToConsume) {
                long nanosToWaitForRefill = state.delayNanosAfterWillBePossibleToConsume(bandwidths, tokensToConsume);
                return ConsumptionProbe.rejected(availableToConsume, nanosToWaitForRefill);
            }
            state.consume(bandwidths, tokensToConsume);
            return ConsumptionProbe.consumed(availableToConsume - tokensToConsume);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected long reserveAndCalculateTimeToSleepImpl(long tokensToConsume, long waitIfBusyNanosLimit) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            long nanosToCloseDeficit = state.delayNanosAfterWillBePossibleToConsume(bandwidths, tokensToConsume);

            if (waitIfBusyNanosLimit > 0 && nanosToCloseDeficit > waitIfBusyNanosLimit) {
                return Long.MAX_VALUE;
            }

            state.consume(bandwidths, tokensToConsume);
            return nanosToCloseDeficit;
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected void addTokensImpl(long tokensToAdd) {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            state.addTokens(bandwidths, tokensToAdd);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public long getAvailableTokens() {
        long currentTimeNanos = timeMeter.currentTimeNanos();
        lock.lock();
        try {
            state.refillAllBandwidth(bandwidths, currentTimeNanos);
            return state.getAvailableTokens(bandwidths);
        } finally {
            lock.unlock();
        }
    }

    @Override
    protected CompletableFuture<Boolean> tryConsumeAsyncImpl(long tokensToConsume) throws UnsupportedOperationException {
        boolean result = tryConsumeImpl(tokensToConsume);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    protected CompletableFuture<Void> addTokensAsyncImpl(long tokensToAdd) throws UnsupportedOperationException {
        addTokensImpl(tokensToAdd);
        return CompletableFuture.completedFuture(null);
    }

    @Override
    protected CompletableFuture<ConsumptionProbe> tryConsumeAndReturnRemainingTokensAsyncImpl(long tokensToConsume) throws UnsupportedOperationException {
        ConsumptionProbe result = tryConsumeAndReturnRemainingTokensImpl(tokensToConsume);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    protected CompletableFuture<Long> tryConsumeAsMuchAsPossibleAsyncImpl(long limit) throws UnsupportedOperationException {
        long result = tryConsumeAsMuchAsPossible(limit);
        return CompletableFuture.completedFuture(result);
    }

    @Override
    protected CompletableFuture<Long> reserveAndCalculateTimeToSleepAsyncImpl(long tokensToConsume, long maxWaitTimeNanos) throws UnsupportedOperationException {
        long result = reserveAndCalculateTimeToSleepImpl(tokensToConsume, maxWaitTimeNanos);
        return CompletableFuture.completedFuture(result);
    }


    @Override
    public BucketState createSnapshot() {
        lock.lock();
        try {
            return state.copy();
        } finally {
            lock.unlock();
        }
    }

    @Override
    public BucketConfiguration getConfiguration() {
        return configuration;
    }

    @Override
    public String toString() {
        synchronized (this) {
            return "SynchronizedBucket{" +
                "state=" + state +
                ", configuration=" + getConfiguration() +
                '}';
        }
    }

}