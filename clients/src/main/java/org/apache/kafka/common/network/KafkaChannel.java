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

package org.apache.kafka.common.network;


import java.io.IOException;

import java.net.InetAddress;
import java.net.Socket;
import java.nio.channels.SelectionKey;

import java.security.Principal;

import org.apache.kafka.common.utils.Utils;

//TODO 我们认为这个KafkaChannel就是对javaNIO里面的SocketChannel
//进行了封装。
public class KafkaChannel {
    //一个broker就对应一个KafkaChannel
    //这个id就是broker的id
    private final String id;
    //我们推测这个里面应该会有SocketChannel
    private final TransportLayer transportLayer;
    private final Authenticator authenticator;
    private final int maxReceiveSize;
    //接收到的响应
    private NetworkReceive receive;
    //发送出去的请求
    private Send send;

    public KafkaChannel(String id, TransportLayer transportLayer, Authenticator authenticator, int maxReceiveSize) throws IOException {
        this.id = id;
        this.transportLayer = transportLayer;
        this.authenticator = authenticator;
        this.maxReceiveSize = maxReceiveSize;
    }

    public void close() throws IOException {
        Utils.closeAll(transportLayer, authenticator);
    }

    /**
     * Returns the principal returned by `authenticator.principal()`.
     */
    public Principal principal() throws IOException {
        return authenticator.principal();
    }

    /**
     * Does handshake of transportLayer and authentication using configured authenticator
     */
    public void prepare() throws IOException {
        if (!transportLayer.ready())
            transportLayer.handshake();
        if (transportLayer.ready() && !authenticator.complete())
            authenticator.authenticate();
    }

    public void disconnect() {
        transportLayer.disconnect();
    }


    public boolean finishConnect() throws IOException {
        return transportLayer.finishConnect();
    }

    public boolean isConnected() {
        return transportLayer.isConnected();
    }

    public String id() {
        return id;
    }

    public void mute() {
        //对当前的这个连接移除OP_READ事件
        transportLayer.removeInterestOps(SelectionKey.OP_READ);
    }

    public void unmute() {
        //重新监听 OP_READ
        transportLayer.addInterestOps(SelectionKey.OP_READ);
    }

    public boolean isMute() {
        return transportLayer.isMute();
    }

    public boolean ready() {
        return transportLayer.ready() && authenticator.complete();
    }

    public boolean hasSend() {
        return send != null;
    }

    /**
     * Returns the address to which this channel's socket is connected or `null` if the socket has never been connected.
     *
     * If the socket was connected prior to being closed, then this method will continue to return the
     * connected address after the socket is closed.
     */
    public InetAddress socketAddress() {
        return transportLayer.socketChannel().socket().getInetAddress();
    }

    public String socketDescription() {
        Socket socket = transportLayer.socketChannel().socket();
        if (socket.getInetAddress() == null)
            return socket.getLocalAddress().toString();
        return socket.getInetAddress().toString();
    }

    public void setSend(Send send) {
        if (this.send != null)
            throw new IllegalStateException("Attempt to begin a send operation with prior send operation still in progress.");
        //往KafkaChannel里面绑定一个发送出去的请求。
        this.send = send;
        //关键的代码来了
        //这儿绑定了一个OP_WRITE事件。
        //一旦绑定了这个事件以后，我们就可以往服务端发送请求了。
        this.transportLayer.addInterestOps(SelectionKey.OP_WRITE);
    }

    public NetworkReceive read() throws IOException {
        NetworkReceive result = null;

        if (receive == null) {
            receive = new NetworkReceive(maxReceiveSize, id);
        }
        //一直在读取数据。
        receive(receive);
        //是否读完一个完整的响应消息
        if (receive.complete()) {
            receive.payload().rewind();

            result = receive;
            receive = null;
        }
        return result;
    }

    public Send write() throws IOException {
        Send result = null;
        //send方法就是发送网络请求的方法
        if (send != null && send(send)) {
            result = send;
            send = null;
        }
        return result;
    }

    private long receive(NetworkReceive receive) throws IOException {
        return receive.readFrom(transportLayer);
    }

    private boolean send(Send send) throws IOException {
        //最终执行发送请求的代码是在这儿
        send.writeTo(transportLayer);
        //如果已经完成网络请求的发送。
        if (send.completed())
            //然后就移除OP_WRITE
            transportLayer.removeInterestOps(SelectionKey.OP_WRITE);

        return send.completed();
    }

}
