package net.trollyloki.dicsit;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.entities.messages.MessageSnapshot;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.dv8tion.jda.api.interactions.InteractionHook;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import net.trollyloki.jicsit.save.SaveHeader;
import net.trollyloki.jicsit.save.Session;
import net.trollyloki.jicsit.server.https.CommandResult;
import net.trollyloki.jicsit.server.https.HttpsApi;
import net.trollyloki.jicsit.server.https.PrivilegeLevel;
import net.trollyloki.jicsit.server.https.exception.ApiException;
import net.trollyloki.jicsit.server.https.exception.InvalidTokenException;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.awt.Color;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.ConnectException;
import java.security.cert.CertificateException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import static net.trollyloki.dicsit.FormattingUtils.defaultSaveName;
import static net.trollyloki.dicsit.FormattingUtils.formatDuration;
import static net.trollyloki.dicsit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.dicsit.FormattingUtils.serverDisplayName;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public final class InteractionUtils {
    private InteractionUtils() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(InteractionUtils.class);

    public static final Color
            RED_ACCENT = Color.getHSBColor(.000f, .75f, 1.00f),
            YELLOW_ACCENT = Color.getHSBColor(.125f, .75f, 1.00f),
            GREEN_ACCENT = Color.getHSBColor(.375f, .75f, 1.00f);

    public static final Emoji
            CHECKBOX_CHECKED_EMOJI = Emoji.fromUnicode("✅"),
            CHECKBOX_EMPTY_EMOJI = Emoji.fromUnicode("🔳");

    private static final int[] DELAY_OPTIONS_SECONDS = {-1, 5, 10, 20, 30, 60, 2 * 60, 3 * 60, 4 * 60, 5 * 60, 10 * 60, 20 * 60, 30 * 60, 60 * 60};

    public static List<Message.Attachment> findMessageAttachments(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = new ArrayList<>(event.getTarget().getAttachments());
        for (MessageSnapshot snapshot : event.getTarget().getMessageSnapshots()) {
            attachments.addAll(snapshot.getAttachments());
        }
        return attachments;
    }

    public static GuildManager getGuildManager(Interaction interaction) {
        Guild guild = interaction.getGuild();
        if (guild == null) {
            throw new UnsupportedOperationException("Interaction must take place within a guild");
        }
        return Dicsit.get().getGuildManager(guild.getId());
    }

    public static boolean isDashboard(Interaction interaction) {
        return getGuildManager(interaction).isDashboard(interaction.getChannel());
    }

    public static @Nullable Member getMember(IReplyCallback callback) {
        Member member = callback.getMember();
        if (member == null) {
            callback.reply("That command can only be used within a guild").setEphemeral(true).queue();
            return null;
        }
        return member;
    }

    public static boolean cannotManageGuild(IReplyCallback callback) {
        Member member = getMember(callback);
        if (member == null)
            return true;

        if (member.hasPermission(Permission.MANAGE_SERVER)) {
            return false;
        }

        LOGGER.info("Unauthorized user: {} does not have the Manager Server permission", callback.getUser().getAsMention());
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static boolean isNotAdmin(IReplyCallback callback) {
        return isNotAdmin(callback, false);
    }

    public static boolean isNotAdmin(IReplyCallback callback, boolean allowSaveManagers) {
        Member member = getMember(callback);
        if (member == null)
            return true;

        GuildManager guildManager = getGuildManager(callback);
        if (
                member.hasPermission(Permission.MANAGE_SERVER)
                        || guildManager.hasAdminRole(member)
                        || allowSaveManagers && guildManager.hasSaveManagerRole(member)
        ) {
            return false;
        }

        LOGGER.info("Unauthorized user: {} does not have any of the allowed roles nor the Manager Server permission", callback.getUser().getAsMention());
        callback.reply("You do not have permission to do that!").setEphemeral(true).queue();
        return true;
    }

    public static @Nullable Server getServerIfAdmin(IReplyCallback callback, String serverIdString) {
        return getServerIfAdmin(callback, serverIdString, false);
    }

    public static @Nullable Server getServerIfAdmin(IReplyCallback callback, String serverIdString, boolean allowSaveManagers) {
        if (isNotAdmin(callback, allowSaveManagers))
            return null;

        Server server = getGuildManager(callback).getServer(UUID.fromString(serverIdString));
        if (server == null) {
            LOGGER.warn("Unknown server {}", serverIdString);
            callback.reply("Unknown server").setEphemeral(true).queue();
            return null;
        }
        return server;
    }

    public static @Nullable Map<UUID, Server> getAllServersIfAdmin(IReplyCallback callback, boolean ignoreServerChannels) {
        return getAllServersIfAdmin(callback, ignoreServerChannels, false);
    }

    public static @Nullable Map<UUID, Server> getAllServersIfAdmin(IReplyCallback callback, boolean ignoreServerChannels, boolean allowSaveManagers) {
        if (isNotAdmin(callback, allowSaveManagers))
            return null;

        GuildManager guildManager = getGuildManager(callback);

        if (!ignoreServerChannels) {
            // If this is a server channel, return a singleton map of the server
            Map.Entry<UUID, Server> channelServer = guildManager.getChannelServer(callback.getChannelId());
            if (channelServer != null) {
                return Map.ofEntries(channelServer);
            }
        }

        // Otherwise return a map of all servers, unless there isn't any
        Map<UUID, Server> servers = guildManager.getServers();
        if (servers.isEmpty()) {
            callback.reply("No servers added").setEphemeral(true).queue();
            return null;
        }
        return servers;
    }

    public static @Nullable List<Server> getServersIfAdmin(IReplyCallback callback, Collection<String> serverIdStrings) {
        return getServersIfAdmin(callback, serverIdStrings, false);
    }

    public static @Nullable List<Server> getServersIfAdmin(IReplyCallback callback, Collection<String> serverIdStrings, boolean allowSaveManagers) {
        List<Server> servers = new ArrayList<>(serverIdStrings.size());
        for (String serverIdString : serverIdStrings) {
            Server server = getServerIfAdmin(callback, serverIdString, allowSaveManagers);
            if (server == null)
                return null;

            servers.add(server);
        }
        return servers;
    }

    public static void logAction(Interaction interaction, String action) {
        getGuildManager(interaction).logAction(interaction.getUser(), action);
    }

    public static void logActionWithServer(Interaction interaction, String action, @Nullable String serverName) {
        getGuildManager(interaction).logAction(interaction.getUser(), action + " " + inlineServerDisplayName(serverName));
    }

    public static TextInput.Builder serverNameInput(String customId) {
        // servers seem to truncate names that are longer than 32 characters
        return TextInput.create(customId, TextInputStyle.SHORT).setMaxLength(32);
    }

    public static StringSelectMenu.Builder serverSelectMenu(String customId, Map<?, Server> servers, boolean forSaving) {
        StringSelectMenu.Builder builder = StringSelectMenu.create(customId);

        int count = 0;
        for (Map.Entry<?, Server> entry : servers.entrySet()) {
            if (forSaving && entry.getValue().isDisableSaving()) continue;

            if (builder.getOptions().size() < StringSelectMenu.OPTIONS_MAX_AMOUNT) {
                builder.addOption(serverDisplayName(entry.getValue().getName()), entry.getKey().toString());
            }

            count++;
        }

        if (count > builder.getOptions().size()) {
            LOGGER.warn("Truncated server select options from {} to {}", count, builder.getOptions().size());
        }

        return builder;
    }

    private static final List<ChannelType> GUILD_MESSAGE_CHANNEL_TYPES = ChannelType.guildTypes().stream().filter(ChannelType::isMessage).toList();

    public static EntitySelectMenu.Builder messageChannelSelect(String customId, @Nullable GuildChannel defaultValue) {
        EntitySelectMenu.Builder builder = EntitySelectMenu.create(customId, EntitySelectMenu.SelectTarget.CHANNEL);
        builder.setChannelTypes(GUILD_MESSAGE_CHANNEL_TYPES);
        if (defaultValue != null) {
            builder.setDefaultValues(EntitySelectMenu.DefaultValue.from(defaultValue));
        }
        return builder;
    }

    public static EntitySelectMenu.Builder roleSelect(String customId, @Nullable Role defaultValue) {
        EntitySelectMenu.Builder builder = EntitySelectMenu.create(customId, EntitySelectMenu.SelectTarget.ROLE);
        if (defaultValue != null) {
            builder.setDefaultValues(EntitySelectMenu.DefaultValue.from(defaultValue));
        }
        return builder;
    }

    public static StringSelectMenu createIntSelectMenu(String customId, IntFunction<String> labelFunction, @Nullable Integer current, int[] ascendingOptions) {
        int maxOptions = StringSelectMenu.OPTIONS_MAX_AMOUNT - 1;
        if (ascendingOptions.length > maxOptions) {
            throw new IllegalArgumentException("Too many options: " + ascendingOptions.length + " > " + maxOptions);
        }

        StringSelectMenu.Builder selectMenu = StringSelectMenu.create(customId);

        boolean currentAdded = current == null;
        for (int value : ascendingOptions) {

            if (!currentAdded && value >= current) {
                if (value != current) {
                    // Insert the current value before this value
                    selectMenu.addOption(labelFunction.apply(current), Integer.toString(current));
                }
                currentAdded = true;
            }

            selectMenu.addOption(labelFunction.apply(value), Integer.toString(value));
        }
        if (!currentAdded) {
            // Insert the current value at the end since it must be bigger than every other option
            selectMenu.addOption(labelFunction.apply(current), Integer.toString(current));
        }

        if (current != null) {
            selectMenu.setDefaultValues(Integer.toString(current));
        }
        return selectMenu.build();
    }

    public static StringSelectMenu createAlertDelaySelectMenu(String customId, @Nullable Duration currentDelay) {
        return createIntSelectMenu(customId, seconds -> {
            if (seconds < 0) return "Disable alerts";
            else return formatDuration(seconds);
        }, currentDelay == null ? -1 : (int) currentDelay.toSeconds(), DELAY_OPTIONS_SECONDS);
    }

    public static @Nullable Duration parseAlertDelay(String value) {
        int seconds = Integer.parseInt(value);
        return seconds < 0 ? null : Duration.ofSeconds(seconds);
    }

    public static String generateToken(HttpsApi httpsApi) {
        String output = httpsApi.runCommand("server.GenerateAPIToken").outputLines()[0];
        return output.substring(output.indexOf(':') + 1).trim();
    }

    public static CommandResult invalidateTokens(HttpsApi httpsApi) {
        return httpsApi.runCommand("server.InvalidateAPITokens");
    }

    public static boolean verifyAndSetToken(ModalInteractionEvent event, String serverIdString, String token, @Nullable String serverName) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return false;

        serverName = serverName != null ? serverName : server.getName();

        // Validate token
        try {
            PrivilegeLevel privilegeLevel = PrivilegeLevel.ofToken(token);
            if (privilegeLevel != PrivilegeLevel.API_TOKEN) {
                event.getHook().sendMessage("Incorrect token type").setEphemeral(true).queue();
                return false;
            }
        } catch (IllegalArgumentException e) {
            event.getHook().sendMessage("Incorrect token format").setEphemeral(true).queue();
            return false;
        }

        LOGGER.info("Verifying token for {}", serverNameForLog(serverName));

        // Verify token
        try {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            httpsApi.setToken(token);
            httpsApi.verifyAuthenticationToken();
        } catch (InvalidTokenException e) {
            event.getHook().sendMessage("Token is invalid").setEphemeral(true).queue();
            return false;
        } catch (Exception e) {
            LOGGER.warn("Failed to verify authentication token for {}", serverNameForLog(serverName), e);

            String reason = determineReasonForRequestFailure(e);
            String message = "Failed to verify token";
            if (reason != null) message += ": " + reason;

            event.getHook().sendMessage(message).setEphemeral(true).queue();
            return false;
        }

        boolean hadToken = server.hasToken();

        // Save token
        if (!getGuildManager(event).setServerToken(UUID.fromString(serverIdString), token)) {
            event.getHook().sendMessage("Failed to save token").setEphemeral(true).queue();
            return false;
        }

        event.getHook().sendMessage("Authentication successful").setEphemeral(true).queue();
        logActionWithServer(event, (hadToken ? "updated the" : "added an") + " authentication token for", serverName);
        return true;
    }

    public static CompletableFuture<@Nullable Void> requestAsyncWithMDC(Server server, String actionString, Consumer<HttpsApi> action) {
        return requestAsyncWithMDC(server, actionString, httpsApi -> {
            action.accept(httpsApi);
            return null;
        });
    }

    public static <T extends @Nullable Object> CompletableFuture<T> requestAsyncWithMDC(Server server, String actionString, Function<HttpsApi, T> action) {
        return requestAsyncWithMDC(CompletableFuture::supplyAsync, server, actionString, action);
    }

    public static CompletableFuture<@Nullable Void> requestAsyncWithMDC(Server server, String actionString, Consumer<HttpsApi> action, Executor executor) {
        return requestAsyncWithMDC(supplier -> CompletableFuture.supplyAsync(supplier, executor), server, actionString, httpsApi -> {
            action.accept(httpsApi);
            return null;
        });
    }

    private static <T extends @Nullable Object> CompletableFuture<T> requestAsyncWithMDC(Function<Supplier<T>, CompletableFuture<T>> futureCreator, Server server, String actionString, Function<HttpsApi, T> action) {
        return futureCreator.apply(withMDC(() -> {
            HttpsApi httpsApi = server.httpsApi(Duration.ofSeconds(3));
            return action.apply(httpsApi);
        })).exceptionally(withMDC((Function<Throwable, T>) exception -> {
            String message = actionString + " " + inlineServerDisplayName(server.getName());
            // Thrown exceptions are always wrapped in a CompletionException
            if (exception.getCause() instanceof GameNotRunningException) {
                message = "Cannot " + message + ": No session is running";
                LOGGER.warn("Refusing to execute request on {}", serverNameForLog(server.getName()), exception.getCause());
            } else if (exception.getCause() instanceof ApiException apiException) {
                message = "Unable to " + message + ": " + apiException.getMessage();
                LOGGER.warn("Unable to execute request on {}: {} ({})", serverNameForLog(server.getName()), apiException.getMessage(), apiException.getErrorCode());
            } else {
                String reason = determineReasonForRequestFailure(exception.getCause());
                message = "Failed to " + message;
                if (reason != null) message += ": " + reason;
                LOGGER.warn("Failed to execute request on {}", serverNameForLog(server.getName()), exception.getCause());
            }
            throw new FormattedException(message, exception.getCause());
        }));
    }

    public static @Nullable String determineReasonForRequestFailure(@Nullable Throwable throwable) {
        // Go down the chain of causes looking for a known exception type
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            switch (cause) {

                case CertificateException _ -> {
                    return "Server fingerprint is incorrect";
                }

                case ConnectException _ -> {
                    return "Could not connect to the server";
                }

                case EOFException _ -> {
                    return "Incomplete response received from the server";
                }

                case IOException _ -> {
                    String message = cause.getMessage();
                    if (message != null && message.startsWith("Connection reset")) {
                        return "Connection to the server was terminated (possibly due to a crash)";
                    }
                }

                default -> {
                }

            }
        }
        return null;
    }

    private static class FormattedException extends RuntimeException {
        private FormattedException(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }

    public static String exceptionMessage(Throwable throwable) {
        if (throwable instanceof CompletionException && throwable.getCause() != null) {
            // Unwrap CompletionExceptions
            throwable = throwable.getCause();
        }

        if (throwable instanceof FormattedException formatted) {
            return formatted.getMessage();
        } else {
            LOGGER.error("Unexpected exception while processing interaction", throwable);
            return "An unexpected error occurred";
        }
    }

    public static CompletableFuture<@Nullable String> checkForPlayersAsyncWithMDC(List<Server> servers) {
        List<CompletableFuture<Integer>> playerCountFutures = servers.stream().map(server -> {
            LOGGER.info("Checking if players are connected to {}", serverNameForLog(server.getName()));

            return requestAsyncWithMDC(server, "check if players are connected to", httpsApi -> {
                return httpsApi.queryServerState().connectedPlayerCount();
            });
        }).toList();

        return CompletableFuture.allOf(playerCountFutures.toArray(CompletableFuture[]::new)).handleAsync(withMDC((_, throwable) -> {
            if (throwable != null) {
                return exceptionMessage(throwable);
            }

            int totalPlayerCount = playerCountFutures.stream().map(CompletableFuture::join).reduce(Integer::sum).orElse(0);
            if (totalPlayerCount == 0) {
                return null;
            }

            String message;
            if (totalPlayerCount == 1) message = "There is currently 1 player";
            else message = "There are currently " + totalPlayerCount + " players";

            message += " connected to ";

            if (servers.size() == 1) message += inlineServerDisplayName(servers.getFirst().getName());
            else message += "those " + servers.size() + " servers";

            return message;
        }));
    }

    private static class GameNotRunningException extends IllegalStateException {
        private GameNotRunningException(String message) {
            super(message);
        }
    }

    private static void saveIfRunning(HttpsApi httpsApi, String saveName) {
        if (!httpsApi.queryServerState().isGameRunning()) {
            // Game is not running, save function will hang if executed
            throw new GameNotRunningException("Cannot create save because game is not running");
        }
        httpsApi.save(saveName);
    }

    public static CompletableFuture<@Nullable Void> saveAndRestartAsyncWithMDC(Server server) {
        String action = "restart";
        boolean save = !server.isDisableSaving();
        if (save) {
            action = "save and " + action;
        }

        return requestAsyncWithMDC(server, action, httpsApi -> {
            if (save && httpsApi.queryServerState().isGameRunning()) {
                httpsApi.save(Dicsit.RESTART_SAVE_NAME);
            }
            httpsApi.shutdownServer();
        });
    }

    public static CompletableFuture<Boolean> reloadAsyncWithMDC(Server server) {
        if (server.isDisableSaving()) {
            return CompletableFuture.failedFuture(new FormattedException("Cannot reload " + inlineServerDisplayName(server.getName()) + ": Saving is disabled", null));
        }

        String saveName = Dicsit.RELOAD_SAVE_NAME;
        return requestAsyncWithMDC(server, "reload", httpsApi -> {

            Optional<Instant> previousTimestamp = Optional.ofNullable(httpsApi.enumerateSessions().current())
                    .map(session -> session.find(saveName))
                    .map(SaveHeader::saveTimestamp);

            saveIfRunning(httpsApi, saveName);

            // Verify that we aren't going to inadvertently load the previous save
            Session session = httpsApi.enumerateSessions().current();
            if (session == null) {
                LOGGER.warn("Reload verification failed: Current session is null");
                return false;
            }

            SaveHeader header = session.find(saveName);
            if (header == null) {
                LOGGER.warn("Reload verification failed: Could not find save header");
                return false;
            }

            if (previousTimestamp.isPresent() && !header.saveTimestamp().isAfter(previousTimestamp.get())) {
                LOGGER.warn("Reload verification failed: Save is older than expected");
                return false;
            }

            httpsApi.loadSave(saveName, false);
            return true;
        });
    }

    public static CompletableFuture<SaveInfo> saveAsyncWithMDC(Server server, @Nullable String saveName) {
        if (server.isDisableSaving()) {
            return CompletableFuture.failedFuture(new FormattedException("Saving " + inlineServerDisplayName(server.getName()) + " is disabled", null));
        }

        return requestAsyncWithMDC(server, "save", httpsApi -> {

            String actualSaveName = saveName;
            if (actualSaveName == null || actualSaveName.isBlank()) {
                String sessionName = httpsApi.queryServerState().activeSessionName();
                actualSaveName = defaultSaveName(sessionName, LocalDateTime.now(Clock.systemUTC()));
            }

            saveIfRunning(httpsApi, actualSaveName);
            Instant fallbackTimestamp = Instant.now();

            SaveHeader saveHeader = null;
            Session session = httpsApi.enumerateSessions().current();
            if (session != null) saveHeader = session.find(actualSaveName);

            if (saveHeader != null) {
                return new SaveInfo(actualSaveName, saveHeader.sessionName(), saveHeader.saveTimestamp());
            } else {
                LOGGER.warn("Couldn't find save header for save name \"{}\"", actualSaveName);
                return new SaveInfo(actualSaveName, null, fallbackTimestamp);
            }

        });
    }

    public static void splitAndConsumeAttachment(InteractionHook hook, NamedAttachmentProxy attachment, int count, BiConsumer<InputStream[], Executor> consumer) {
        attachment.download().thenAcceptAsync(withMDC(downloadStream -> {

            InputStream[] uploadStreams;
            try {
                uploadStreams = splitInputStream(downloadStream, count, e -> {
                    hook.editOriginal("Failed to transfer data")
                            .setComponents(Collections.emptySet()).queue();
                    LOGGER.error("Error while streaming split save data", e);
                });
            } catch (Exception e) {
                hook.editOriginal("Failed to start data transfer")
                        .setComponents(Collections.emptySet()).queue();
                LOGGER.error("Failed to split save data", e);
                return;
            }

            consumer.accept(uploadStreams, Executors.newFixedThreadPool(count));

        })).exceptionallyAsync(withMDC(throwable -> {
            hook.editOriginal("Failed to retrieve attachment")
                    .setComponents(Collections.emptySet()).queue();
            LOGGER.error("Failed to retrieve attachment", throwable);
            return null;
        }));
    }

    private static InputStream[] splitInputStream(InputStream stream, int count, Consumer<Exception> errorCallback) throws IOException {
        PipedInputStream[] inputStreams = new PipedInputStream[count];
        PipedOutputStream[] outputStreams = new PipedOutputStream[count];
        try {
            for (int i = 0; i < count; i++) {
                //noinspection resource: inputStreams are returned from this method
                inputStreams[i] = new PipedInputStream();
                outputStreams[i] = new PipedOutputStream(inputStreams[i]);
            }
        } catch (Exception e) {
            for (PipedInputStream inputStream : inputStreams) {
                try {
                    inputStream.close();
                } catch (Exception ignored) {
                }
            }
            for (PipedOutputStream outputStream : outputStreams) {
                try {
                    outputStream.close();
                } catch (Exception ignored) {
                }
            }
            throw e;
        }

        Map<String, String> mdc = MDC.getCopyOfContextMap();
        new Thread(() -> {
            MDC.setContextMap(mdc);
            try (stream) {
                byte[] buffer = new byte[1024];

                int read;
                do {
                    read = stream.read(buffer);
                    if (read > 0) {
                        for (PipedOutputStream outputStream : outputStreams) {
                            outputStream.write(buffer, 0, read);
                        }
                    }
                } while (read >= 0);
            } catch (Exception e) {
                errorCallback.accept(e);
            } finally {
                for (PipedOutputStream outputStream : outputStreams) {
                    try {
                        outputStream.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }).start();

        return inputStreams;
    }

}
