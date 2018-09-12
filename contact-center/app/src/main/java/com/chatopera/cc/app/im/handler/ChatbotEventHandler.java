/*
 * Copyright (C) 2018 Chatopera Inc, <https://www.chatopera.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.chatopera.cc.app.im.handler;

import com.chatopera.cc.app.algorithm.AutomaticServiceDist;
import com.chatopera.cc.app.basic.MainContext;
import com.chatopera.cc.app.im.util.ChatbotUtils;
import com.chatopera.cc.util.IP;
import com.chatopera.cc.util.IPTools;
import com.chatopera.cc.app.basic.MainUtils;
import com.chatopera.cc.app.im.client.NettyClients;
import com.chatopera.cc.app.model.*;
import com.chatopera.cc.app.cache.CacheHelper;
import com.chatopera.cc.app.persistence.repository.AgentUserRepository;
import com.chatopera.cc.app.persistence.repository.ConsultInviteRepository;
import com.chatopera.cc.app.persistence.repository.OnlineUserRepository;
import com.chatopera.cc.app.im.router.OutMessageRouter;
import com.chatopera.cc.util.OnlineUserUtils;
import com.chatopera.cc.app.im.message.AgentStatusMessage;
import com.chatopera.cc.app.im.message.ChatMessage;
import com.chatopera.cc.app.im.message.NewRequestMessage;
import com.corundumstudio.socketio.AckRequest;
import com.corundumstudio.socketio.SocketIOClient;
import com.corundumstudio.socketio.SocketIOServer;
import com.corundumstudio.socketio.annotation.OnConnect;
import com.corundumstudio.socketio.annotation.OnDisconnect;
import com.corundumstudio.socketio.annotation.OnEvent;
import org.apache.commons.lang.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.InetSocketAddress;
import java.util.Date;

public class ChatbotEventHandler {
    private static final Logger logger = LoggerFactory.getLogger(ChatbotEventHandler.class);

    protected SocketIOServer server;

    private AgentUserRepository agentUserRes;
    private OnlineUserRepository onlineUserRes;

    @Autowired
    public ChatbotEventHandler(SocketIOServer server) {
        this.server = server;
    }

    @OnConnect
    public void onConnect(SocketIOClient client) {
        try {
            String user = client.getHandshakeData().getSingleUrlParam("userid");
            String nickname = client.getHandshakeData().getSingleUrlParam("nickname");
            String orgi = client.getHandshakeData().getSingleUrlParam("orgi");
            String session = client.getHandshakeData().getSingleUrlParam("session");
            String appid = client.getHandshakeData().getSingleUrlParam("appid");
            String aiid = client.getHandshakeData().getSingleUrlParam("aiid");
//			String agent = client.getHandshakeData().getSingleUrlParam("agent") ;
//			String skill = client.getHandshakeData().getSingleUrlParam("skill") ;
            Date now = new Date();

            if (StringUtils.isNotBlank(user)) {
//				/**
//				 * 加入到 缓存列表
//				 */
                NettyClients.getInstance().putChatbotEventClient(user, client);
                MessageOutContent outMessage = new MessageOutContent();
                CousultInvite invite = OnlineUserUtils.cousult(appid, orgi, MainContext.getContext().getBean(ConsultInviteRepository.class));
                if (invite != null && StringUtils.isNotBlank(invite.getAisuccesstip())) {
                    outMessage.setMessage(invite.getAisuccesstip());
                } else {
                    outMessage.setMessage("欢迎使用华夏春松机器人客服！");
                }

                outMessage.setMessageType(MainContext.MessageTypeEnum.MESSAGE.toString());
                outMessage.setCalltype(MainContext.CallTypeEnum.IN.toString());
                outMessage.setNickName(invite.getAiname());
                outMessage.setCreatetime(MainUtils.dateFormate.format(now));

                client.sendEvent(MainContext.MessageTypeEnum.STATUS.toString(), outMessage);

                InetSocketAddress address = (InetSocketAddress) client.getRemoteAddress();
                String ip = MainUtils.getIpAddr(client.getHandshakeData().getHttpHeaders(), address.getHostString());
                OnlineUser onlineUser = getOnlineUserRes().findOne(user);

                if (onlineUser == null) {
                    onlineUser = new OnlineUser();
                    onlineUser.setAppid(appid);
                    if (StringUtils.isNotBlank(nickname)) {
                        onlineUser.setUsername(nickname);
                    } else {
                        onlineUser.setUsername(MainContext.GUEST_USER + "_" + MainUtils.genIDByKey(user));
                    }

                    onlineUser.setSessionid(session);
                    onlineUser.setOptype(MainContext.OptTypeEnum.CHATBOT.toString());
                    onlineUser.setUserid(user);
                    onlineUser.setId(user);
                    onlineUser.setOrgi(orgi);
                    onlineUser.setChannel(MainContext.ChannelTypeEnum.WEBIM.toString());
                    onlineUser.setIp(ip);
                    onlineUser.setUpdatetime(now);
                    onlineUser.setLogintime(now);
                    onlineUser.setCreatetime(now);
                    IP ipdata = IPTools.getInstance().findGeography(ip);
                    onlineUser.setCity(ipdata.getCity());
                    onlineUser.setCountry(ipdata.getCountry());
                    onlineUser.setProvince(ipdata.getProvince());
                    onlineUser.setIsp(ipdata.getIsp());
                    onlineUser.setRegion(ipdata.getRegion());
                    onlineUser.setStatus(MainContext.OnlineUserOperatorStatus.ONLINE.toString());
                }

                // 在线客服访客咨询记录
                AgentUser agentUser = new AgentUser(onlineUser.getId(),
                        MainContext.ChannelTypeEnum.WEBIM.toString(), // callout
                        onlineUser.getId(),
                        onlineUser.getUsername(),
                        MainContext.SYSTEM_ORGI,
                        appid);

                agentUser.setServicetime(now);
                agentUser.setCreatetime(now);
                agentUser.setUpdatetime(now);
                agentUser.setSessionid(session);
                // 聊天机器人处理的请求
                agentUser.setOpttype(MainContext.OptTypeEnum.CHATBOT.toString());
                agentUser.setAgentno(aiid); // 聊天机器人ID
                agentUser.setCity(onlineUser.getCity());
                agentUser.setProvince(onlineUser.getProvince());
                agentUser.setCountry(onlineUser.getCountry());
                AgentService agentService = AutomaticServiceDist.processChatbotService(agentUser, orgi);
                agentUser.setAgentserviceid(agentService.getId());

                getAgentUserRes().save(agentUser);
                getOnlineUserRes().save(onlineUser);
                CacheHelper.getAgentUserCacheBean().put(user, agentUser, orgi);
                CacheHelper.getOnlineUserCacheBean().put(user, onlineUser, orgi);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //添加@OnDisconnect事件，客户端断开连接时调用，刷新客户端信息  
    @OnDisconnect
    public void onDisconnect(SocketIOClient client) throws Exception {
        String user = client.getHandshakeData().getSingleUrlParam("userid");
        String orgi = client.getHandshakeData().getSingleUrlParam("orgi");
        if (StringUtils.isNotBlank(user)) {
            NettyClients.getInstance().removeChatbotEventClient(user, MainUtils.getContextID(client.getSessionId().toString()));
            AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(user, orgi);
            OnlineUser onlineUser = (OnlineUser) CacheHelper.getOnlineUserCacheBean().getCacheObject(user, orgi);
            if (agentUser != null) {
                AutomaticServiceDist.processChatbotService(agentUser, orgi);
                CacheHelper.getAgentUserCacheBean().delete(user, MainContext.SYSTEM_ORGI);
                CacheHelper.getOnlineUserCacheBean().delete(user, orgi);
                agentUser.setStatus(MainContext.OnlineUserOperatorStatus.OFFLINE.toString());
                onlineUser.setStatus(MainContext.OnlineUserOperatorStatus.OFFLINE.toString());
                getAgentUserRes().save(agentUser);
                getOnlineUserRes().save(onlineUser);
            }
        }
        client.disconnect();
    }

    //消息接收入口，网站有新用户接入对话  
    @OnEvent(value = "new")
    public void onEvent(SocketIOClient client, AckRequest request, NewRequestMessage data) {

    }

    //消息接收入口，坐席状态更新
    @OnEvent(value = "agentstatus")
    public void onEvent(SocketIOClient client, AckRequest request, AgentStatusMessage data) {
        System.out.println(data.getMessage());
    }

    //消息接收入口，收发消息，用户向坐席发送消息和 坐席向用户发送消息  
    @OnEvent(value = "message")
    public void onEvent(SocketIOClient client, AckRequest request, ChatMessage data) {
        String orgi = client.getHandshakeData().getSingleUrlParam("orgi");
        String aiid = client.getHandshakeData().getSingleUrlParam("aiid");
        String user = client.getHandshakeData().getSingleUrlParam("userid");
        if (data.getType() == null) {
            data.setType("message");
        }
        /**
         * 以下代码主要用于检查 访客端的字数限制
         */
        CousultInvite invite = OnlineUserUtils.cousult(data.getAppid(), data.getOrgi(), MainContext.getContext().getBean(ConsultInviteRepository.class));
        if (invite != null && invite.getMaxwordsnum() > 0) {
            if (!StringUtils.isBlank(data.getMessage()) && data.getMessage().length() > invite.getMaxwordsnum()) {
                data.setMessage(data.getMessage().substring(0, invite.getMaxwordsnum()));
            }
        } else if (!StringUtils.isBlank(data.getMessage()) && data.getMessage().length() > 300) {
            data.setMessage(data.getMessage().substring(0, 300));
        }
        data.setSessionid(MainUtils.getContextID(client.getSessionId().toString()));
        /**
         * 处理表情
         */
        data.setMessage(MainUtils.processEmoti(data.getMessage()));
        data.setTousername(invite.getAiname());

        data.setAiid(aiid);

        AgentUser agentUser = (AgentUser) CacheHelper.getAgentUserCacheBean().getCacheObject(user, orgi);
        if (agentUser != null) {
            data.setAgentserviceid(agentUser.getAgentserviceid());
            data.setChannel(agentUser.getChannel());
            /**
             * 一定要设置 ContextID
             */
            data.setContextid(agentUser.getAgentserviceid());
        }
        MessageOutContent outMessage = ChatbotUtils.createTextMessage(data, data.getAppid(), data.getChannel(), MainContext.CallTypeEnum.IN.toString(), MainContext.ChatbotItemType.USERINPUT.toString(), data.getUserid());
        if (StringUtils.isNotBlank(data.getUserid()) && MainContext.MessageTypeEnum.MESSAGE.toString().equals(data.getType())) {
            if (!StringUtils.isBlank(data.getTouser())) {
                OutMessageRouter router = null;
                router = (OutMessageRouter) MainContext.getContext().getBean(data.getChannel());
                if (router != null) {
                    router.handler(data.getTouser(), MainContext.MessageTypeEnum.MESSAGE.toString(), data.getAppid(), outMessage);
                }
            }
            if (agentUser != null) {
                Date now = new Date();
                agentUser.setUpdatetime(now);
                agentUser.setLastmessage(now);
                agentUser.setLastmsg(data.getMessage());
                CacheHelper.getAgentUserCacheBean().put(user, agentUser, MainContext.SYSTEM_ORGI);
            }
        }
    }

    /**
     * Lazy load
     * @return
     */
    public AgentUserRepository getAgentUserRes() {
        if (agentUserRes == null)
            agentUserRes = MainContext.getContext().getBean(AgentUserRepository.class);
        return agentUserRes;
    }

    /**
     * Lazy load
     * @return
     */
    public OnlineUserRepository getOnlineUserRes() {
        if (onlineUserRes == null)
            onlineUserRes = MainContext.getContext().getBean(OnlineUserRepository.class);
        return onlineUserRes;
    }
}