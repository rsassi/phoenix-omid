/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.omid.tso;

import com.google.common.base.Optional;
import org.apache.omid.committable.CommitTable;
import org.apache.omid.committable.CommitTable.CommitTimestamp;
import org.apache.omid.committable.InMemoryCommitTable;
import org.apache.omid.metrics.MetricsRegistry;
import org.apache.omid.metrics.NullMetricsProvider;
import org.jboss.netty.channel.Channel;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.testng.AssertJUnit.assertEquals;

public class TestRetryProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(TestRetryProcessor.class);

    private MetricsRegistry metrics = new NullMetricsProvider();

    private static long NON_EXISTING_ST_TX = 1000;
    private static long ST_TX_1 = 0;
    private static long CT_TX_1 = 1;
    private static long ST_TX_2 = 2;

    @Mock
    private Channel channel;
    @Mock
    private ReplyProcessor replyProc;
    @Mock
    private Panicker panicker;

    private CommitTable commitTable;

    @BeforeMethod(alwaysRun = true, timeOut = 30_000)
    public void initMocksAndComponents() {
        MockitoAnnotations.initMocks(this);
        // Init components
        commitTable = new InMemoryCommitTable();
        metrics = new NullMetricsProvider();

    }

    @Test(timeOut = 10_000)
    public void testBasicFunctionality() throws Exception {

        // The element to test
        RetryProcessor retryProc = new RetryProcessorImpl(metrics, commitTable, replyProc, panicker);

        // Test we'll reply with an abort for a retry request when the start timestamp IS NOT in the commit table
        retryProc.disambiguateRetryRequestHeuristically(NON_EXISTING_ST_TX, channel, new MonitoringContext(metrics));
        ArgumentCaptor<Long> firstTScapture = ArgumentCaptor.forClass(Long.class);
        verify(replyProc, timeout(100).times(1)).abortResponse(firstTScapture.capture(), any(Channel.class), any(MonitoringContext.class));

        long startTS = firstTScapture.getValue();
        assertEquals("Captured timestamp should be the same as NON_EXISTING_ST_TX", NON_EXISTING_ST_TX, startTS);

        // Test we'll reply with a commit for a retry request when the start timestamp IS in the commit table
        commitTable.getWriter().addCommittedTransaction(ST_TX_1, CT_TX_1); // Add a tx to commit table

        retryProc.disambiguateRetryRequestHeuristically(ST_TX_1, channel, new MonitoringContext(metrics));
        ArgumentCaptor<Long> secondTScapture = ArgumentCaptor.forClass(Long.class);
        verify(replyProc, timeout(100).times(1))
                .commitResponse(firstTScapture.capture(), secondTScapture.capture(), any(Channel.class), any(MonitoringContext.class));

        startTS = firstTScapture.getValue();
        long commitTS = secondTScapture.getValue();
        assertEquals("Captured timestamp should be the same as ST_TX_1", ST_TX_1, startTS);
        assertEquals("Captured timestamp should be the same as CT_TX_1", CT_TX_1, commitTS);
    }

    @Test(timeOut = 10_000)
    public void testRetriedRequestForInvalidatedTransactionReturnsAnAbort() throws Exception {

        // Invalidate the transaction
        commitTable.getClient().tryInvalidateTransaction(ST_TX_1);

        // Pre-start verification: Validate that the transaction is invalidated
        // NOTE: This test should be in the a test class for InMemoryCommitTable
        Optional<CommitTimestamp> invalidTxMarker = commitTable.getClient().getCommitTimestamp(ST_TX_1).get();
        Assert.assertTrue(invalidTxMarker.isPresent());
        Assert.assertEquals(invalidTxMarker.get().getValue(), InMemoryCommitTable.INVALID_TRANSACTION_MARKER);

        // The element to test
        RetryProcessor retryProc = new RetryProcessorImpl(metrics, commitTable, replyProc, panicker);

        // Test we'll reply with an abort for a retry request when the
        // transaction id IS in the commit table BUT invalidated
        retryProc.disambiguateRetryRequestHeuristically(ST_TX_1, channel, new MonitoringContext(metrics));
        ArgumentCaptor<Long> startTScapture = ArgumentCaptor.forClass(Long.class);
        verify(replyProc, timeout(100).times(1)).abortResponse(startTScapture.capture(), any(Channel.class), any(MonitoringContext.class));

        long startTS = startTScapture.getValue();
        Assert.assertEquals(startTS, ST_TX_1, "Captured timestamp should be the same as NON_EXISTING_ST_TX");

    }

}

