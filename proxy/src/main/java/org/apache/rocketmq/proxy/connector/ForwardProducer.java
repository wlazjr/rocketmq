/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.proxy.connector;

import io.grpc.Context;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import org.apache.rocketmq.client.consumer.AckResult;
import org.apache.rocketmq.common.protocol.ResponseCode;
import org.apache.rocketmq.common.protocol.header.AckMessageRequestHeader;
import org.apache.rocketmq.proxy.connector.client.MQClientAPIExt;
import org.apache.rocketmq.client.producer.SendResult;
import org.apache.rocketmq.client.producer.SendStatus;
import org.apache.rocketmq.common.message.Message;
import org.apache.rocketmq.common.protocol.header.ConsumerSendMsgBackRequestHeader;
import org.apache.rocketmq.common.protocol.header.EndTransactionRequestHeader;
import org.apache.rocketmq.common.protocol.header.SendMessageRequestHeader;
import org.apache.rocketmq.common.protocol.heartbeat.HeartbeatData;
import org.apache.rocketmq.common.sysflag.MessageSysFlag;
import org.apache.rocketmq.proxy.config.ConfigurationManager;
import org.apache.rocketmq.proxy.connector.factory.ForwardClientManager;
import org.apache.rocketmq.proxy.connector.transaction.TransactionId;
import org.apache.rocketmq.remoting.protocol.RemotingCommand;

public class ForwardProducer extends AbstractForwardClient {
    private static final String PID_PREFIX = "PID_RMQ_PROXY_PUBLISH_MESSAGE_";

    public ForwardProducer(ForwardClientManager clientFactory) {
        super(clientFactory, PID_PREFIX);
    }

    @Override
    protected int getClientNum() {
        return ConfigurationManager.getProxyConfig().getForwardProducerNum();
    }

    @Override
    protected MQClientAPIExt createNewClient(ForwardClientManager clientFactory, String name) {
        double workerFactor = ConfigurationManager.getProxyConfig().getForwardProducerWorkerFactor();
        final int threadCount = (int) Math.ceil(Runtime.getRuntime().availableProcessors() * workerFactor);

        return clientFactory.getTransactionalProducer(name, threadCount);
    }

    public CompletableFuture<Integer> heartBeat(Context ctx, String brokerAddr, HeartbeatData heartbeatData) throws Exception {
        return this.heartBeat(ctx, brokerAddr, heartbeatData, DEFAULT_MQ_CLIENT_TIMEOUT);
    }
    public CompletableFuture<Integer> heartBeat(Context ctx, String brokerAddr, HeartbeatData heartbeatData, long timeout) throws Exception {
        return this.getClient().sendHeartbeatAsync(brokerAddr, heartbeatData, timeout);
    }

    public void endTransaction(Context ctx, String brokerAddr, EndTransactionRequestHeader requestHeader) throws Exception {
        this.endTransaction(ctx, brokerAddr, requestHeader, DEFAULT_MQ_CLIENT_TIMEOUT);
    }

    public void endTransaction(Context ctx, String brokerAddr, EndTransactionRequestHeader requestHeader, long timeoutMillis) throws Exception {
        this.getClient().endTransactionOneway(brokerAddr, requestHeader, "end transaction from rmq proxy", timeoutMillis);
    }

    public CompletableFuture<SendResult> sendMessage(
        Context ctx,
        String address,
        String brokerName,
        List<Message> msg,
        SendMessageRequestHeader requestHeader
    ) {
        return this.sendMessage(ctx, address, brokerName, msg, requestHeader, DEFAULT_MQ_CLIENT_TIMEOUT);
    }

    public CompletableFuture<SendResult> sendMessage(
        Context ctx,
        String address,
        String brokerName,
        List<Message> msg,
        SendMessageRequestHeader requestHeader,
        long timeoutMillis
    ) {
        CompletableFuture<SendResult> future;
        if (msg.size() == 1) {
            future = this.getClient().sendMessageAsync(address, brokerName, msg.get(0), requestHeader, timeoutMillis);
        } else {
            future = this.getClient().sendMessageAsync(address, brokerName, msg, requestHeader, timeoutMillis);
        }
        return processSendMessageResponseFuture(ctx, address, requestHeader, future);
    }

    protected CompletableFuture<SendResult> processSendMessageResponseFuture(
        Context ctx,
        String address,
        SendMessageRequestHeader requestHeader,
        CompletableFuture<SendResult> future) {
        return future.thenApply(sendResult -> {
            int tranType = MessageSysFlag.getTransactionValue(requestHeader.getSysFlag());
            if (SendStatus.SEND_OK.equals(sendResult.getSendStatus()) && tranType == MessageSysFlag.TRANSACTION_PREPARED_TYPE) {
                TransactionId transactionId = TransactionId.genByBrokerTransactionId(address, sendResult);
                sendResult.setTransactionId(transactionId.getProxyTransactionId());
            }
            return sendResult;
        });
    }

    public CompletableFuture<RemotingCommand> sendMessageBackThenAckOrg(Context ctx, String brokerAddr, ConsumerSendMsgBackRequestHeader sendMsgBackRequestHeader,
        AckMessageRequestHeader ackMessageRequestHeader) {
        return sendMessageBackThenAckOrg(ctx, brokerAddr, sendMsgBackRequestHeader, ackMessageRequestHeader, DEFAULT_MQ_CLIENT_TIMEOUT,
            future -> {
            });
    }

    public CompletableFuture<RemotingCommand> sendMessageBackThenAckOrg(Context ctx, String brokerAddr, ConsumerSendMsgBackRequestHeader sendMsgBackRequestHeader,
        AckMessageRequestHeader ackMessageRequestHeader, long timeoutMillis,
        Consumer<CompletableFuture<AckResult>> ackFutureSetter) {
        return this.sendMessageBack(ctx, brokerAddr, sendMsgBackRequestHeader, timeoutMillis).whenComplete((result, throwable) -> {
            if (throwable != null || ResponseCode.SUCCESS != result.getCode()) {
                return;
            }
            ackFutureSetter.accept(this.getClient().ackMessageAsync(brokerAddr, ackMessageRequestHeader, timeoutMillis));
        });
    }

    public CompletableFuture<RemotingCommand> sendMessageBack(Context ctx, String brokerAddr, ConsumerSendMsgBackRequestHeader requestHeader) {
        return this.sendMessageBack(ctx, brokerAddr, requestHeader, DEFAULT_MQ_CLIENT_TIMEOUT);
    }

    public CompletableFuture<RemotingCommand> sendMessageBack(Context ctx, String brokerAddr, ConsumerSendMsgBackRequestHeader requestHeader, long timeoutMillis) {
        return this.getClient().sendMessageBackAsync(brokerAddr, requestHeader, timeoutMillis);
    }
}
