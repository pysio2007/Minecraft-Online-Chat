package online.pysio.minecraftonlinechat;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.command.CommandSource;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.util.text.StringTextComponent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegistryEvent;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.InterModComms;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.event.lifecycle.InterModEnqueueEvent;
import net.minecraftforge.fml.event.lifecycle.InterModProcessEvent;
import net.minecraftforge.fml.event.server.FMLServerStartingEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.Timer;
import java.util.TimerTask;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

@Mod("minecraftonlinechat")
public class Minecraftonlinechat {
    private static final Logger LOGGER = LogManager.getLogger();
    private WebSocketClient webSocketClient;
    private static final String WS_SERVER_URL = "ws://localhost:3000";
    private static final String HTTP_SERVER_URL = "http://localhost:3000/chat";
    private Timer reconnectTimer;
    private static final int RECONNECT_DELAY = 5000; // 5秒后重连
    private boolean isReconnecting = false;

    public Minecraftonlinechat() {
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::setup);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::enqueueIMC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::processIMC);
        FMLJavaModLoadingContext.get().getModEventBus().addListener(this::doClientStuff);
        MinecraftForge.EVENT_BUS.register(this);
    }

    private void connectWebSocket() {
        try {
            if (webSocketClient != null) {
                webSocketClient.close();
            }

            webSocketClient = new WebSocketClient(new URI(WS_SERVER_URL)) {
                @Override
                public void onOpen(ServerHandshake handshakedata) {
                    LOGGER.info("Connected to WebSocket server");
                    if (isReconnecting) {
                        broadcastToChat("§a[WorldChat] 重新连接服务器成功!");
                    } else {
                        broadcastToChat("§a[WorldChat] 连接到聊天服务器成功!");
                    }
                    isReconnecting = false;
                    stopReconnectTimer();
                }

                @Override
                public void onMessage(String message) {
                    broadcastToChat("§b[WorldChat] " + message);
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    LOGGER.info("WebSocket closed: " + reason);
                    broadcastToChat("§c[WorldChat] 与聊天服务器断开连接");
                    if (remote) {
                        startReconnectTimer();
                    }
                }

                @Override
                public void onError(Exception ex) {
                    LOGGER.error("WebSocket error", ex);
                    broadcastToChat("§c[WorldChat] 连接错误: " + ex.getMessage());
                    startReconnectTimer();
                }
            };
            webSocketClient.connect();
        } catch (Exception e) {
            LOGGER.error("Failed to initialize WebSocket client", e);
            startReconnectTimer();
        }
    }


    private void startReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
        }
        isReconnecting = true;
        broadcastToChat("§e[WorldChat] 正在尝试重新连接...");
        
        reconnectTimer = new Timer();
        reconnectTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if (webSocketClient == null || !webSocketClient.isOpen()) {
                    connectWebSocket();
                }
            }
        }, RECONNECT_DELAY, RECONNECT_DELAY);
    }

    private void stopReconnectTimer() {
        if (reconnectTimer != null) {
            reconnectTimer.cancel();
            reconnectTimer = null;
        }
    }

    private void broadcastToChat(String message) {
        if (net.minecraft.client.Minecraft.getInstance().player != null) {
            net.minecraft.client.Minecraft.getInstance().player.sendMessage(
                new StringTextComponent(message), 
                net.minecraft.util.Util.NIL_UUID
            );
        }
    }

    @SubscribeEvent
    public void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (webSocketClient == null || !webSocketClient.isOpen()) {
            connectWebSocket();
        }
    }

    private void setup(final FMLCommonSetupEvent event) {
        LOGGER.info("HELLO FROM PREINIT");
    }

    private void doClientStuff(final FMLClientSetupEvent event) {
        LOGGER.info("Got game settings {}", event.getMinecraftSupplier().get().options);
    }

    private void enqueueIMC(final InterModEnqueueEvent event) {
        InterModComms.sendTo("minecraftonlinechat", "helloworld", () -> {
            LOGGER.info("Hello Minecraft Chat!");
            return "Hello Minecraft Chat!";
        });
    }

    private void processIMC(final InterModProcessEvent event) {
        LOGGER.info("Got IMC {}", event.getIMCStream().map(m -> m.getMessageSupplier().get()).collect(Collectors.toList()));
    }

    @SubscribeEvent
    public void onServerStarting(FMLServerStartingEvent event) {
        LOGGER.info("HELLO from server starting");
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSource> dispatcher = event.getDispatcher();
        dispatcher.register(
            net.minecraft.command.Commands.literal("wc")
                .then(net.minecraft.command.Commands.argument("message", StringArgumentType.greedyString())
                    .executes(context -> {
                        String message = StringArgumentType.getString(context, "message");
                        ServerPlayerEntity player = context.getSource().getPlayerOrException();
                        sendMessageToServer(player.getName().getString(), message);
                        return 1;
                    })
                )
        );
    }

private void sendMessageToServer(String username, String message) {
        try {
            // 构建 JSON 数据
            String jsonInputString = String.format("{\"username\":\"%s\",\"message\":\"%s\"}", 
                username.replace("\"", "\\\""), // 转义用户名中的引号
                message.replace("\"", "\\\"")   // 转义消息中的引号
            );
            
            LOGGER.info("Sending message: " + jsonInputString); // 调试日志

            URL url = new URL(HTTP_SERVER_URL);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Accept", "application/json");
            conn.setDoOutput(true);
            conn.setDoInput(true);

            // 发送请求
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = jsonInputString.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
                os.flush();
            }

            // 读取响应
            int responseCode = conn.getResponseCode();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                    responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream()))) {
                String response = br.lines().collect(Collectors.joining("\n"));
                LOGGER.info("Server response: " + response); // 调试日志
            }

            if (responseCode != 200) {
                LOGGER.error("Server returned code: " + responseCode);
                broadcastToChat("§c[WorldChat] 发送消息失败 (HTTP " + responseCode + ")");
            }

            conn.disconnect();
        } catch (Exception e) {
            LOGGER.error("Failed to send message to server", e);
            broadcastToChat("§c[WorldChat] 发送消息失败: " + e.getMessage());
        }
    }
}