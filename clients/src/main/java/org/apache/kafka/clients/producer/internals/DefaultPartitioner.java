/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.kafka.clients.producer.internals;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.producer.Partitioner;
import org.apache.kafka.common.Cluster;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.utils.Utils;

/**
 * The default partitioning strategy:
 * <ul>
 * <li>If a partition is specified in the record, use it
 * <li>If no partition is specified but a key is present choose a partition based on a hash of the key
 * <li>If no partition or key is present choose a partition in a round-robin fashion
 */
public class DefaultPartitioner implements Partitioner {
    //原子类，初始化的时候 给了一个随机数
    private final AtomicInteger counter = new AtomicInteger(new Random().nextInt());

    public void configure(Map<String, ?> configs) {}

    /**
     * Compute the partition for the given record.
     *
     * @param topic The topic name
     * @param key The key to partition on (or null if no key)
     * @param keyBytes serialized key to partition on (or null if no key)
     * @param value The value to partition on or null
     * @param valueBytes serialized value to partition on or null
     * @param cluster The current cluster metadata
     */
    public int partition(String topic, Object key, byte[] keyBytes, Object value, byte[] valueBytes, Cluster cluster) {
        //首先获取到我们要发送消息的对应的topic的分区的信息
        List<PartitionInfo> partitions = cluster.partitionsForTopic(topic);
        //计算出来分区的总的个数
        int numPartitions = partitions.size();

        /**
         * producer 发送数据的时候：
         *  message
         *
         *  key,message
         *  message
         *
         */

        //策略一： 如果发送消息的时候，没有指定key
        if (keyBytes == null) {
            //这儿有一个计数器
            //每次执行都会加一
            //10

            int nextValue = counter.getAndIncrement();
            //获取可用的分区数
            List<PartitionInfo> availablePartitions = cluster.availablePartitionsForTopic(topic);
            if (availablePartitions.size() > 0) {
                //计算我们要发送到哪个分区上。
                //一个数如果对10进行取模， 0-9之间
                //11 % 9
                //12 % 9
                //13 % 9
                //实现一个轮训的效果，能达到负载均衡。
                int part = Utils.toPositive(nextValue) % availablePartitions.size();
                //根据这个值分配分区好。
                return availablePartitions.get(part).partition();
            } else {
                // no partitions are available, give a non-available partition
                return Utils.toPositive(nextValue) % numPartitions;
            }
        } else {
            //策略二：这个地方就是指定了key
            // hash the keyBytes to choose a partition
            //直接对key取一个hash值 % 分区的总数取模
            //如果是同一个key，计算出来的分区肯定是同一个分区。
            //如果我们想要让消息能发送到同一个分区上面，那么我们就
            //必须指定key. 这一点非常重要
            //希望大家一定一定要知道。
            return Utils.toPositive(Utils.murmur2(keyBytes)) % numPartitions;
        }
    }

    public void close() {}

}
