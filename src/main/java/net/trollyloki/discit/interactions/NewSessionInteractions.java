package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.trollyloki.discit.InteractionUtils;
import net.trollyloki.discit.Server;
import net.trollyloki.jicsit.server.https.AdvancedGameSettings;
import net.trollyloki.jicsit.server.https.NewGameData;
import org.jspecify.annotations.NullMarked;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.trollyloki.discit.FormattingUtils.escapeAll;
import static net.trollyloki.discit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.discit.InteractionListener.buildId;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.LoggingUtils.serverNameForLog;
import static net.trollyloki.discit.LoggingUtils.withMDC;

@NullMarked
public final class NewSessionInteractions {
    private NewSessionInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(NewSessionInteractions.class);

    public static final String
            NEW_SESSION_BUTTON_ID = "new-session",
            NEW_SESSION_MODAL_ID = "new-session";

    public static void onNewSessionButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(Modal.create(buildId(NEW_SESSION_MODAL_ID, serverIdString), "New Session").addComponents(
                TextDisplay.of("Creating new session on " + inlineServerDisplayName(server.getName())),
                Label.of("Session Name", TextInput.create("name", TextInputStyle.SHORT)
                        .setMaxLength(100) // arbitrary
                        .build()),
                Label.of("Starting Area", StringSelectMenu.create("location")
                        .setPlaceholder("Select starting area")
                        .addOption("Grass Fields", NewGameData.GRASS_FIELDS, "Ideal for first-time pioneers")
                        .addOption("Rocky Desert", NewGameData.ROCKY_DESERT, "Suitable for first-time pioneers")
                        .addOption("Northern Forest", NewGameData.NORTHERN_FOREST, "Good for more experienced pioneers")
                        .addOption("Dune Desert", NewGameData.DUNE_DESERT, "Recommended for advanced pioneers")
                        .build()),
                Label.of("Starting Tier", "Selecting a starting tier will enable Advanced Game Settings", StringSelectMenu.create("tier")
                        .setPlaceholder("Select starting tier")
                        .setRequired(false)
                        .addOption("Onboarding", "0")
                        .addOption("Tier 1", "1")
                        .addOption("Tier 2", "2")
                        .addOption("Tier 3", "3")
                        .addOption("Tier 4", "4")
                        .addOption("Tier 5", "5")
                        .addOption("Tier 6", "6")
                        .addOption("Tier 7", "7")
                        .addOption("Tier 8", "8")
                        .addOption("Tier 9", "9")
                        .addOption("Unlock All Tiers", "10")
                        .build())
        ).build()).queue();
    }

    public static void onNewSessionModal(ModalInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        ModalMapping name = event.getValue("name");
        if (name == null) {
            event.reply("Please provide a name").setEphemeral(true).queue();
            return;
        }

        ModalMapping location = event.getValue("location");
        if (location == null) {
            event.reply("Please select a starting location").setEphemeral(true).queue();
            return;
        }

        ModalMapping tier = event.getValue("tier");
        List<String> tierAsList = tier != null ? tier.getAsStringList() : Collections.emptyList();

        NewGameData.Builder builder = NewGameData.builder(name.getAsString());
        builder.startingLocation(location.getAsStringList().getFirst());
        if (!tierAsList.isEmpty()) {
            builder.advancedGameSettings(Map.of(AdvancedGameSettings.STARTING_TIER, tierAsList.getFirst()));
        }
        NewGameData newGameData = builder.build();

        event.deferReply(isDashboard(event)).queue();

        LOGGER.info("Creating new session on {}: {}", serverNameForLog(server.getName()), newGameData);

        requestAsyncWithMDC(server, "create new session on", httpsApi -> {
            httpsApi.createNewSession(newGameData);
        }).thenApplyAsync(withMDC(_ -> {
            String inlineSessionName = "*" + escapeAll(newGameData.sessionName()) + "*";
            logActionWithServer(event, "created new session " + inlineSessionName + " on", server.getName());
            return "Successfully created new session on " + inlineServerDisplayName(server.getName());
        })).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
            event.getHook().editOriginal(message).queue();
        }));
    }

}
