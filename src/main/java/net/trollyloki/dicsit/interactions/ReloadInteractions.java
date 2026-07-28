package net.trollyloki.dicsit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.dicsit.GuildManager;
import net.trollyloki.dicsit.InteractionUtils;
import net.trollyloki.dicsit.Server;
import net.trollyloki.dicsit.interactions.cache.AutoKeyedCache;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static net.trollyloki.dicsit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.*;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public final class ReloadInteractions {
    private ReloadInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(ReloadInteractions.class);

    public static final String
            RELOAD_COMMAND_NAME = "reload",
            RELOAD_MODAL_ID = "reload",
            RELOAD_CANCEL_BUTTON_ID = "reload-cancel",
            RELOAD_CONFIRM_BUTTON_ID = "reload-confirm",
            RELOAD_DEFER_BUTTON_ID = "reload-defer",
            RELOAD_BUTTON_ID = "reload";

    public static final String
            RESTART_COMMAND_NAME = "restart",
            RESTART_MODAL_ID = "restart",
            RESTART_CONFIRM_BUTTON_ID = "restart-confirm",
            RESTART_DEFER_BUTTON_ID = "restart-defer",
            RESTART_BUTTON_ID = "restart";

    private static final AutoKeyedCache<List<String>> SERVER_SELECTION_CACHE = new AutoKeyedCache<>();

    public static void onReloadCommand(SlashCommandInteractionEvent event, boolean shutdown) {
        Map.Entry<UUID, Server> channelServer = getGuildManager(event).getChannelServer(event.getChannelId());
        if (channelServer != null) {
            // Skip modal
            if (isNotAdmin(event) && (shutdown || !channelServer.getValue().isAllowReloading()))
                return;

            confirmReload(event, shutdown, Collections.singletonList(channelServer.getKey().toString()), Collections.singletonList(channelServer.getValue()));
            return;
        }

        Map<UUID, Server> servers = getAllServersIfAdmin(event, true);
        if (servers == null)
            return;

        Modal.Builder modal = shutdown
                ? Modal.create(RESTART_MODAL_ID, "Restart Server")
                : Modal.create(RELOAD_MODAL_ID, "Reload Session");
        event.replyModal(modal.addComponents(
                Label.of("Servers", "The server(s) that should be re" + (shutdown ? "start" : "load") + "ed",
                        serverSelectMenu("servers", servers)
                                .setMaxValues(SelectMenu.OPTIONS_MAX_AMOUNT)
                                .setPlaceholder("Select one or more servers")
                                .build())
        ).build()).queue();
    }

    public static void onReloadModal(ModalInteractionEvent event, boolean shutdown) {
        ModalMapping serverIds = event.getValue("servers");
        if (serverIds == null) {
            event.reply("Please select servers").setEphemeral(true).queue();
            return;
        }

        List<Server> servers = getServersIfAdmin(event, serverIds.getAsStringList());
        if (servers == null)
            return;

        confirmReload(event, shutdown, serverIds.getAsStringList(), servers);
    }

    private static void confirmReload(IReplyCallback callback, boolean shutdown, List<String> serverIdStrings, List<Server> servers) {
        callback.deferReply(isDashboard(callback)).queue();

        checkForPlayersAsyncWithMDC(servers).thenAcceptAsync(withMDC(message -> {
            if (message == null) {
                // Skip confirmation if no players are connected
                reload(callback, shutdown, serverIdStrings, servers);
                return;
            }

            UUID key = SERVER_SELECTION_CACHE.put(serverIdStrings);
            String confirmButtonId, deferButtonId, actionLabel;
            if (shutdown) {
                confirmButtonId = RESTART_CONFIRM_BUTTON_ID;
                deferButtonId = RESTART_DEFER_BUTTON_ID;
                actionLabel = "Restart";
            } else {
                confirmButtonId = RELOAD_CONFIRM_BUTTON_ID;
                deferButtonId = RELOAD_DEFER_BUTTON_ID;
                actionLabel = "Reload";
            }
            callback.getHook().editOriginal(message).setComponents(ActionRow.of(
                    Button.primary(buildId(deferButtonId, callback.getUser().getId(), key), "Defer " + actionLabel),
                    Button.danger(buildId(confirmButtonId, callback.getUser().getId(), key), actionLabel + " Anyway"),
                    Button.secondary(buildId(RELOAD_CANCEL_BUTTON_ID, callback.getUser().getId(), key), "Cancel")
            )).queue();
        }));
    }

    public static void onReloadCancelButton(ButtonInteractionEvent event, String userId, String keyString) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        SERVER_SELECTION_CACHE.pop(UUID.fromString(keyString));

        event.deferEdit().queue();
        event.getHook().deleteOriginal().queue();
    }

    public static void onReloadConfirmButton(ButtonInteractionEvent event, boolean shutdown, String userId, String keyString, boolean defer) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        List<String> serverIdStrings = SERVER_SELECTION_CACHE.pop(UUID.fromString(keyString));
        if (serverIdStrings == null) {
            event.deferEdit().queue();
            event.getHook().deleteOriginal().queue();
            event.getHook().sendMessage("Context expired, please try again").setEphemeral(true).queue();
            return;
        }

        // Special case of button in channel server and reloading is allowed
        if (!shutdown && serverIdStrings.size() == 1) {
            Map.Entry<UUID, Server> channelServer = getGuildManager(event).getChannelServer(event.getChannelId());
            if (channelServer != null && channelServer.getValue().isAllowReloading() && channelServer.getKey().toString().equals(serverIdStrings.getFirst())) {
                List<String> singleServerIdString = Collections.singletonList(serverIdStrings.getFirst());
                List<Server> singleServer = Collections.singletonList(channelServer.getValue());

                event.deferEdit().queue();
                if (defer) {
                    deferReload(event, false, singleServerIdString, singleServer);
                } else {
                    reload(event, false, singleServerIdString, singleServer);
                }
                return;
            }
        }

        List<Server> servers = getServersIfAdmin(event, serverIdStrings);
        if (servers == null)
            return;

        event.deferEdit().queue();
        if (defer) {
            deferReload(event, shutdown, serverIdStrings, servers);
        } else {
            reload(event, shutdown, serverIdStrings, servers);
        }
    }

    public static void onReloadButton(ButtonInteractionEvent event, boolean shutdown, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        confirmReload(event, shutdown, Collections.singletonList(serverIdString), Collections.singletonList(server));
    }

    private static void deferReload(IReplyCallback callback, boolean shutdown, List<String> serverIdStrings, List<Server> servers) {
        GuildManager guildManager = getGuildManager(callback);
        for (String serverIdString : serverIdStrings) {
            UUID serverId = UUID.fromString(serverIdString);
            if (shutdown) {
                guildManager.deferRestart(serverId);
            } else {
                guildManager.deferReload(serverId);
            }
        }

        for (Server server : servers) {
            logActionWithServer(callback, "deferred re" + (shutdown ? "start" : "load") + "ing", server.getName());
        }

        String suffix = " will be re" + (shutdown ? "start" : "load") + "ed when there are no players connected";
        callback.getHook().editOriginal(servers.stream()
                .map(server -> inlineServerDisplayName(server.getName()) + suffix)
                .collect(Collectors.joining("\n"))
        ).setComponents(Collections.emptySet()).queue();
    }

    private static void reload(IReplyCallback callback, boolean shutdown, List<String> serverIdStrings, List<Server> servers) {
        String actionPrefix = "Re" + (shutdown ? "start" : "load") + "ing ";
        List<String> messageLines = Collections.synchronizedList(servers.stream()
                .map(server -> actionPrefix + inlineServerDisplayName(server.getName()) + "...")
                .collect(Collectors.toList())
        );
        // No need to synchronize here, the list won't be changing yet
        callback.getHook().editOriginal(String.join("\n", messageLines))
                .setComponents(Collections.emptySet()).queue();

        if (shutdown) {
            for (Server server : servers) {
                logActionWithServer(callback, "is restarting", server.getName());
            }
        }

        GuildManager guildManager = getGuildManager(callback);
        for (int i = 0; i < servers.size(); i++) {
            final int index = i;
            UUID serverId = UUID.fromString(serverIdStrings.get(index));
            Server server = servers.get(index);

            LOGGER.info("{}{}", actionPrefix, serverNameForLog(server.getName()));

            CompletableFuture<Boolean> actionFuture;
            if (shutdown) {
                actionFuture = saveAndRestartAsyncWithMDC(server).thenCompose(_ -> guildManager.waitForServer(serverId)).thenApplyAsync(_ -> true);
            } else {
                actionFuture = reloadAsyncWithMDC(server);
            }
            actionFuture.thenApplyAsync(withMDC(verified -> {
                if (!verified) {
                    return "Reload verification for " + inlineServerDisplayName(server.getName()) + " failed, please try again";
                }

                // Cancel previously deferred reloads and (if restarting) restarts
                guildManager.cancelDeferredReload(serverId);
                if (shutdown) {
                    guildManager.cancelDeferredRestart(serverId);
                }

                String action = "re" + (shutdown ? "start" : "load") + "ed";
                logActionWithServer(callback, action, server.getName());
                return "Successfully " + action + " " + inlineServerDisplayName(server.getName());
            })).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                messageLines.set(index, message);
                synchronized (messageLines) {
                    callback.getHook().editOriginal(String.join("\n", messageLines)).queue();
                }
            }));
        }
    }

}
