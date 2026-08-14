package net.trollyloki.dicsit;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.Channel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.MarkdownUtil;
import net.dv8tion.jda.api.utils.Timestamp;
import net.trollyloki.dicsit.data.GuildData;
import net.trollyloki.dicsit.data.ServerData;
import net.trollyloki.dicsit.monitoring.ServerInfoCache;
import net.trollyloki.dicsit.monitoring.ServerMonitor;
import net.trollyloki.jicsit.save.SaveFileReader;
import net.trollyloki.jicsit.server.https.exception.SaveFailedException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

import java.io.File;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static net.trollyloki.dicsit.AddressUtils.validateHostAddress;
import static net.trollyloki.dicsit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.dicsit.FormattingUtils.safeMonospace;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public class GuildManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(GuildManager.class);

    private static final Emoji ALERT_EMOJI = Emoji.fromUnicode("⚠️");

    private static final JsonMapper DATA_MAPPER = JsonMapper.builder()
            .enable(MapperFeature.USE_GETTERS_AS_SETTERS).build();

    private static File dataFile(String guildId) {
        return new File(Dicsit.DATA_DIRECTORY, guildId + ".json");
    }

    private static @Nullable Duration secondsToDuration(long seconds) {
        return seconds < 0 ? null : Duration.ofSeconds(seconds);
    }

    private static long durationToSeconds(@Nullable Duration delay) {
        if (delay != null && delay.isNegative()) {
            throw new IllegalArgumentException("Non-null delay cannot be negative");
        }
        return delay == null ? -1 : delay.toSeconds();
    }

    private final JDA jda;
    private final String guildId;
    private final GuildData data;

    private final Map<UUID, ServerMonitor> monitors = new HashMap<>();

    private GuildManager(JDA jda, String guildId, GuildData data) {
        this.jda = jda;
        this.guildId = guildId;
        this.data = data;
    }

    public static GuildManager load(JDA jda, String guildId) {
        File dataFile = dataFile(guildId);

        GuildData data;
        if (!dataFile.exists()) {
            data = new GuildData();
        } else {
            data = DATA_MAPPER.readValue(dataFile, GuildData.class);

            // Data migration
            if (data.getDataVersion() == 0) { // before per-server offline alert delays and separate alert role were added
                LOGGER.info("Migrating data for guild {}", guildId);
                for (ServerData serverData : data.getServers().values()) {
                    // Copy global offline alert delay to all existing servers
                    serverData.setOfflineAlertDelaySeconds(data.getOfflineAlertDelaySeconds());
                }
                // Copy admin role to alert role
                data.setAlertRoleId(data.getAdminRoleId());
            }

        }
        data.setDataVersion(1);

        GuildManager guildManager = new GuildManager(jda, guildId, data);
        guildManager.data.getServers().keySet().forEach(guildManager::initServer);
        return guildManager;
    }

    private void save() {
        CompletableFuture.runAsync(withMDC(() -> {
            File dataFile = dataFile(guildId);
            synchronized (this) {
                try {
                    boolean ignored = dataFile.getParentFile().mkdirs();
                    DATA_MAPPER.writerWithDefaultPrettyPrinter().writeValue(dataFile(guildId), data);
                } catch (JacksonException e) {
                    LOGGER.error("Failed to save data for guild {}", guildId, e);
                }
            }
        }));
    }

    public Guild getGuild() {
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalStateException("Guild missing: " + guildId);
        }
        return guild;
    }

    private @Nullable Role getRole(@Nullable String roleId) {
        if (roleId == null) return null;
        return getGuild().getRoleById(roleId);
    }

    private @Nullable GuildMessageChannel getMessageChannel(@Nullable String channelId) {
        if (channelId == null) return null;
        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    private boolean hasRole(Member member, @Nullable Role role) {
        return role != null && member.getUnsortedRoles().contains(role);
    }

    public boolean hasAdminRole(Member member) {
        return hasRole(member, getAdminRole());
    }

    public boolean hasSaveManagerRole(Member member) {
        return hasRole(member, getSaveManagerRole());
    }

    public @Nullable Role getAdminRole() {
        return getRole(data.getAdminRoleId());
    }

    public @Nullable Role getSaveManagerRole() {
        return getRole(data.getSaveManagerRoleId());
    }

    public @Nullable GuildMessageChannel getDashboardChannel() {
        return getMessageChannel(data.getDashboardChannelId());
    }

    public @Nullable GuildMessageChannel getLogChannel() {
        return getMessageChannel(data.getLogChannelId());
    }

    public @Nullable Role getAlertRole() {
        return getRole(data.getAlertRoleId());
    }

    public @Nullable Duration getDefaultOfflineAlertDelay() {
        return secondsToDuration(data.getOfflineAlertDelaySeconds());
    }

    public void setAdminRole(@Nullable String roleId) {
        data.setAdminRoleId(roleId);
        save();
    }

    public void setSaveManagerRole(@Nullable String roleId) {
        data.setSaveManagerRoleId(roleId);
        save();
    }

    public void setDashboardChannel(@Nullable String channelId) {
        data.setDashboardChannelId(channelId);
        save();
    }

    public void setLogChannel(@Nullable String channelId) {
        data.setLogChannelId(channelId);
        save();
    }

    public void setAlertRole(@Nullable String roleId) {
        data.setAlertRoleId(roleId);
        save();
    }

    public void setDefaultOfflineAlertDelay(@Nullable Duration delay) {
        data.setOfflineAlertDelaySeconds(durationToSeconds(delay));
        save();
    }

    public void log(String text) {
        GuildMessageChannel channel = getLogChannel();
        if (channel == null) return;

        try {
            channel.sendMessage(text).queue();
        } catch (Exception e) {
            LOGGER.warn("Cannot send log message", e);
        }
    }

    public void logAction(User user, String action) {
        log(user.getAsMention() + " " + action);
    }

    private void logAlert(String alert, @Nullable Consumer<Message> successConsumer) {
        GuildMessageChannel channel = getLogChannel();
        if (channel == null) return;

        String text = ALERT_EMOJI.getFormatted() + " " + alert;
        Role role = getAlertRole();
        if (role != null) text += " " + role.getAsMention();

        try {
            RestAction<Message> action = channel.sendMessage(text).setAllowedMentions(Collections.singleton(Message.MentionType.ROLE));
            if (successConsumer == null) action.queue();
            else action.queue(successConsumer);
        } catch (Exception e) {
            LOGGER.warn("Cannot send alert message", e);
        }
    }

    public void logAlert(String alert) {
        logAlert(alert, null);
    }

    public void logOfflineAlert(UUID serverId, Timestamp timestamp) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return;

        logAlert(inlineServerDisplayName(server.getName()) + " went down " + timestamp, message -> {
            synchronized (server) {
                server.setLastOfflineAlertMessageId(message.getId());
                save();
            }
        });
    }

    public void strikeLastOfflineAlert(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return;

        synchronized (server) {
            String messageId = server.getLastOfflineAlertMessageId();
            if (messageId == null) return;

            GuildMessageChannel channel = getLogChannel();
            if (channel == null) return;

            try {
                channel.retrieveMessageById(messageId).flatMap(message -> message.editMessage(
                        MarkdownUtil.strike(message.getContentRaw())
                )).queue();
            } catch (Exception e) {
                LOGGER.warn("Cannot strike offline alert message", e);
            }

            server.setLastOfflineAlertMessageId(null);
            save();
        }
    }

    public boolean isDashboard(@Nullable Channel channel) {
        return channel != null && channel.getId().equals(data.getDashboardChannelId());
    }

    public Map<UUID, Server> getServers() {
        return Collections.unmodifiableMap(data.getServers());
    }

    public @Nullable Server getServer(UUID serverId) {
        return data.getServers().get(serverId);
    }

    public synchronized Map.Entry<UUID, Server> addServer(String host, int port, String fingerprint) {
        if (validateHostAddress(host) == null) {
            throw new IllegalArgumentException("Invalid host address");
        }

        ServerData serverData = new ServerData(host, port, fingerprint);
        serverData.setOfflineAlertDelaySeconds(data.getOfflineAlertDelaySeconds());

        UUID serverId;
        do {
            serverId = UUID.randomUUID();
        } while (data.getServers().putIfAbsent(serverId, serverData) != null);
        save();

        initServer(serverId);

        return Map.entry(serverId, serverData);
    }

    private void initServer(UUID serverId) {
        monitors.put(serverId, new ServerMonitor(this, serverId));
    }

    public synchronized @Nullable Server removeServer(UUID serverId) {
        ServerData serverData = data.getServers().remove(serverId);
        if (serverData == null) return null;
        save();

        ServerMonitor monitor = monitors.remove(serverId);
        if (monitor != null) monitor.close();

        return serverData;
    }

    public void refreshServer(UUID serverId) {
        ServerMonitor monitor = monitors.get(serverId);
        if (monitor == null) {
            LOGGER.warn("Could not find monitor for server {}", serverId);
            return;
        }

        monitor.refresh();
    }

    public CompletableFuture<@Nullable Void> waitForServer(UUID serverId) {
        ServerMonitor monitor = monitors.get(serverId);
        if (monitor == null) {
            throw new IllegalArgumentException("Could not find monitor for server " + serverId);
        }

        return monitor.waitForResponse();
    }

    public void updateServerName(UUID serverId, @Nullable String name) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not update name for unknown server {}", serverId);
            return;
        }

        server.setName(name);
        save();
    }

    public boolean updateServerFingerprint(UUID serverId, String newFingerprint) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not update fingerprint for unknown server {}", serverId);
            return false;
        }

        server.setToken(null); // clear credentials if fingerprint changes for security
        server.setFingerprint(newFingerprint);
        save();

        updateAuthenticated(serverId, false);

        return true;
    }

    public boolean setServerToken(UUID serverId, @Nullable String token) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not set token for unknown server {}", serverId);
            return false;
        }

        server.setToken(token);
        save();

        updateAuthenticated(serverId, server.hasToken());

        return true;
    }

    private void updateAuthenticated(UUID serverId, boolean authenticated) {
        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setAuthenticated(authenticated);
    }

    public boolean setAllowReloading(UUID serverId, boolean allowReloading) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not set allow reloading value for unknown server {}", serverId);
            return false;
        }

        server.setAllowReloading(allowReloading);
        save();

        return true;
    }

    public boolean setDisableSaving(UUID serverId, boolean disableSaving) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not set disable saving value for unknown server {}", serverId);
            return false;
        }

        server.setDisableSaving(disableSaving);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDisableSaving(disableSaving);

        return true;
    }

    public @Nullable Duration getOfflineAlertDelay(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return null;

        return secondsToDuration(server.getOfflineAlertDelaySeconds());
    }

    public void setOfflineAlertDelay(UUID serverId, @Nullable Duration delay) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not set offline alert delay for unknown server {}", serverId);
            return;
        }

        server.setOfflineAlertDelaySeconds(durationToSeconds(delay));
        save();
    }

    public void deferLoad(UUID serverId, String saveName) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not defer load for unknown server {}", serverId);
            return;
        }

        server.setDeferredLoadSaveName(saveName);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredLoadSaveName(saveName);

    }

    public void deferReload(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not defer reload for unknown server {}", serverId);
            return;
        }

        server.setDeferredReload(true);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredReload(true);

    }

    public void deferRestart(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not defer restart for unknown server {}", serverId);
            return;
        }

        server.setDeferredRestart(true);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredRestart(true);

    }

    public void cancelAllDeferredActions() {
        for (ServerData server : data.getServers().values()) {
            server.setDeferredLoadSaveName(null);
            server.setDeferredReload(false);
            server.setDeferredRestart(false);
        }
        save();

        for (ServerMonitor monitor : monitors.values()) {
            ServerInfoCache infoCache = monitor.getInfoCache();
            infoCache.setDeferredLoadSaveName(null);
            infoCache.setDeferredReload(false);
            infoCache.setDeferredRestart(false);
        }

    }

    public boolean cancelDeferredLoad(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not cancel deferred load for unknown server {}", serverId);
            return false;
        }

        if (server.getDeferredLoadSaveName() == null) {
            return false;
        }

        server.setDeferredLoadSaveName(null);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredLoadSaveName(null);

        return true;
    }

    public boolean cancelDeferredReload(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not cancel deferred reload for unknown server {}", serverId);
            return false;
        }

        if (!server.isDeferredReload()) {
            return false;
        }

        server.setDeferredReload(false);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredReload(false);

        return true;
    }

    public boolean cancelDeferredRestart(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not cancel deferred restart for unknown server {}", serverId);
            return false;
        }

        if (!server.isDeferredRestart()) {
            return false;
        }

        server.setDeferredRestart(false);
        save();

        ServerMonitor monitor = monitors.get(serverId);
        if (monitor != null) monitor.getInfoCache().setDeferredRestart(false);

        return true;
    }

    public synchronized CompletableFuture<Boolean> executeDeferredAction(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not execute deferred action for unknown server {}", serverId);
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unknown server"));
        }

        // Check for deferred actions
        String loadSaveName = server.getDeferredLoadSaveName();
        boolean reload = server.isDeferredReload();
        boolean restart = server.isDeferredRestart();

        if (loadSaveName == null && !reload && !restart) {
            // No deferred actions
            return CompletableFuture.completedFuture(false);
        }

        ServerMonitor monitor = monitors.get(serverId);
        ServerInfoCache infoCache = monitor != null ? monitor.getInfoCache() : null;

        CompletableFuture<Boolean> future = new CompletableFuture<>();

        // Note that loading a new save and/or restarting the server also serves as a reload
        if (restart) {

            // Clear deferred restart and potential reload
            server.setDeferredRestart(false);
            server.setDeferredReload(false);
            save();

            executeDeferredRestart(serverId, server, future, 1);
            // Potential deferred load will be executed once the server fully restarts and this method is called again

        } else {

            // Clear all deferred actions
            server.setDeferredLoadSaveName(null);
            server.setDeferredReload(false);
            server.setDeferredRestart(false);
            save();

            if (infoCache != null) infoCache.setDeferredLoadSaveName(null);

            if (loadSaveName != null) {
                executeDeferredLoad(server, loadSaveName, future);
            } else {
                executeDeferredReload(server, future, 1);
            }

        }

        if (infoCache != null) {
            infoCache.setDeferredReload(false);
            infoCache.setDeferredRestart(false);
        }

        return future;
    }

    private static boolean isSaveFailure(@Nullable Throwable throwable) {
        if (throwable == null) return false;
        Throwable formattedException = throwable.getCause(); // unwrap CompletionException
        if (formattedException == null) return false;
        Throwable actualException = formattedException.getCause(); // get actual underlying exception
        return actualException instanceof SaveFailedException;
    }

    private void executeDeferredRestart(UUID serverId, ServerData server, CompletableFuture<Boolean> future, int attempt) {
        LOGGER.info("Executing deferred restart of {}", serverNameForLog(server.getName()));

        InteractionUtils.saveAndRestartAsyncWithMDC(server).thenComposeAsync(withMDC(_ -> {
            log("Restarting " + inlineServerDisplayName(server.getName()));
            return waitForServer(serverId);
        })).whenCompleteAsync(withMDC((_, throwable) -> {
            if (attempt < Dicsit.MAX_ACTION_ATTEMPTS && isSaveFailure(throwable)) {
                CompletableFuture.delayedExecutor(Dicsit.ACTION_ATTEMPT_INTERVAL, TimeUnit.MILLISECONDS)
                        .execute(withMDC(() -> executeDeferredRestart(serverId, server, future, attempt + 1)));
                return;
            }

            if (throwable != null) {
                logAlert(InteractionUtils.exceptionMessage(throwable));
                future.completeExceptionally(throwable);
            } else {
                log("Successfully restarted " + inlineServerDisplayName(server.getName()));
                future.complete(true);
            }
        }));
    }

    private void executeDeferredLoad(ServerData server, String saveName, CompletableFuture<Boolean> future) {
        LOGGER.info("Executing deferred load of save \"{}\" on {}", saveName, serverNameForLog(server.getName()));

        String saveFilename = safeMonospace(saveName + SaveFileReader.EXTENSION);
        InteractionUtils.requestAsyncWithMDC(server, "load " + saveFilename + " on", httpsApi -> {
            httpsApi.loadSave(saveName, false);
        }).whenCompleteAsync(withMDC((_, throwable) -> {
            if (throwable != null) {
                logAlert(InteractionUtils.exceptionMessage(throwable));
                future.completeExceptionally(throwable);
            } else {
                log("Successfully loaded " + saveFilename + " on " + inlineServerDisplayName(server.getName()));
                future.complete(true);
            }
        }));
    }

    private void executeDeferredReload(ServerData server, CompletableFuture<Boolean> future, int attempt) {
        LOGGER.info("Executing deferred reload of {}", serverNameForLog(server.getName()));

        InteractionUtils.reloadAsyncWithMDC(server).whenCompleteAsync(withMDC((verified, throwable) -> {
            if (attempt < Dicsit.MAX_ACTION_ATTEMPTS && isSaveFailure(throwable)) {
                CompletableFuture.delayedExecutor(Dicsit.ACTION_ATTEMPT_INTERVAL, TimeUnit.MILLISECONDS)
                        .execute(withMDC(() -> executeDeferredReload(server, future, attempt + 1)));
                return;
            }

            if (throwable != null) {
                logAlert(InteractionUtils.exceptionMessage(throwable));
                future.completeExceptionally(throwable);
            } else if (!verified) {
                logAlert("Reload verification for " + inlineServerDisplayName(server.getName()) + " failed, try reloading again");
                future.complete(false);
            } else {
                log("Successfully reloaded " + inlineServerDisplayName(server.getName()));
                future.complete(true);
            }
        }));
    }

    public @Nullable String getDashboardMessageId(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return null;

        return server.getDashboardMessageId();
    }

    public void updateDashboardMessageId(UUID serverId, @Nullable String messageId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return;

        server.setDashboardMessageId(messageId);
        save();
    }

    public Map.@Nullable Entry<UUID, Server> getChannelServer(@Nullable String channelId) {
        if (channelId == null) return null;
        for (Map.Entry<UUID, ServerData> entry : data.getServers().entrySet()) {
            if (channelId.equals(entry.getValue().getServerChannelId())) {
                return Map.entry(entry.getKey(), entry.getValue());
            }
        }
        return null;
    }

    public @Nullable GuildMessageChannel getServerChannel(UUID serverId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) return null;

        String channelId = server.getServerChannelId();
        if (channelId == null) return null;

        return getGuild().getChannelById(GuildMessageChannel.class, channelId);
    }

    public synchronized @Nullable Server setServerChannel(UUID serverId, @Nullable String channelId) {
        ServerData server = data.getServers().get(serverId);
        if (server == null) {
            LOGGER.warn("Could not set server channel for unknown server {}", serverId);
            return null;
        }

        Map.Entry<UUID, Server> channelServer = getChannelServer(channelId);
        if (channelServer != null) {
            // Channel is already associated with a server
            return channelServer.getValue();
        }

        server.setServerChannelId(channelId);
        save();

        return channelId == null ? null : server;
    }

}
