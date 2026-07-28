package net.trollyloki.dicsit.interactions;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.trollyloki.dicsit.GuildManager;
import net.trollyloki.dicsit.Server;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiPredicate;
import java.util.function.Function;

import static net.trollyloki.dicsit.FormattingUtils.escapedServerName;
import static net.trollyloki.dicsit.FormattingUtils.safeMonospace;
import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.*;

@NullMarked
public final class DeferredActionsInteractions {
    private DeferredActionsInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(DeferredActionsInteractions.class);

    public static final String
            DEFERRED_ACTIONS_COMMAND_NAME = "deferred",
            CANCEL_DEFERRED_LOAD_BUTTON_ID = "deferred-cancel-load",
            CANCEL_DEFERRED_RELOAD_BUTTON_ID = "deferred-cancel-reload",
            CANCEL_DEFERRED_RESTART_BUTTON_ID = "deferred-cancel-restart";

    private static MessageTopLevelComponent deferredActionsContainer(Map<UUID, Server> servers) {
        List<ContainerChildComponent> components = new ArrayList<>();

        for (Map.Entry<UUID, Server> entry : servers.entrySet()) {
            UUID serverId = entry.getKey();
            Server server = entry.getValue();

            List<Button> buttons = new ArrayList<>(3);
            if (server.getDeferredLoadSaveName() != null) {
                buttons.add(Button.secondary(buildId(CANCEL_DEFERRED_LOAD_BUTTON_ID, serverId), "Cancel Load"));
            }
            if (server.isDeferredReload()) {
                buttons.add(Button.secondary(buildId(CANCEL_DEFERRED_RELOAD_BUTTON_ID, serverId), "Cancel Reload"));
            }
            if (server.isDeferredRestart()) {
                buttons.add(Button.secondary(buildId(CANCEL_DEFERRED_RESTART_BUTTON_ID, serverId), "Cancel Restart"));
            }

            if (!buttons.isEmpty()) {
                components.add(TextDisplay.of("### " + escapedServerName(server.getName())));
                components.add(ActionRow.of(buttons));
            }
        }

        if (components.isEmpty()) {
            // Don't display the container if there are no deferred actions
            return TextDisplay.of("No deferred actions are pending");
        }

        components.addFirst(Separator.createDivider(Separator.Spacing.SMALL));
        components.addFirst(TextDisplay.of("## Deferred Actions"));
        return Container.of(components);
    }

    public static void onDeferredActionsCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event, true);
        if (servers == null)
            return;

        event.replyComponents(deferredActionsContainer(servers)).useComponentsV2().setEphemeral(true).queue();
    }

    public static void onCancelDeferredLoadButton(ButtonInteractionEvent event, String serverIdString) {
        onCancelDeferredActionHelper(event, serverIdString, GuildManager::cancelDeferredLoad, server ->
                "cancelled the deferred load of " + safeMonospace(server.getDeferredLoadSaveName() + SaveFileReader.EXTENSION) + " on"
        );
    }

    public static void onCancelDeferredReloadButton(ButtonInteractionEvent event, String serverIdString) {
        onCancelDeferredActionHelper(event, serverIdString, GuildManager::cancelDeferredReload, server ->
                "cancelled the deferred reload of"
        );
    }

    public static void onCancelDeferredRestartButton(ButtonInteractionEvent event, String serverIdString) {
        onCancelDeferredActionHelper(event, serverIdString, GuildManager::cancelDeferredRestart, server ->
                "cancelled the deferred restart of"
        );
    }

    private static void onCancelDeferredActionHelper(ButtonInteractionEvent event, String serverIdString, BiPredicate<GuildManager, UUID> cancelMethod, Function<Server, String> actionFunction) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event, true);
        if (servers == null)
            return;

        UUID serverId = UUID.fromString(serverIdString);
        boolean success = cancelMethod.test(getGuildManager(event), serverId);

        if (success) {
            Server server = servers.get(serverId);
            if (server != null) {
                logActionWithServer(event, actionFunction.apply(server), server.getName());
            } else {
                LOGGER.error("Couldn't find known server in all servers map");
            }
        }

        event.editComponents(deferredActionsContainer(servers)).useComponentsV2().queue();
    }

}
