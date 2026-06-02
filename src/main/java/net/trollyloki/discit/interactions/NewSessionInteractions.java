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
import net.trollyloki.jicsit.server.https.CreativeModeSettings;
import net.trollyloki.jicsit.server.https.NewGameData;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.HashMap;
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
                Label.of("Starting Tier", "Selecting a starting tier will enable Creative Mode", StringSelectMenu.create("tier")
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
                        .build()),
                Label.of("Set Game Phase", "Selecting a phase will enable Creative Mode", StringSelectMenu.create("phase")
                        .setPlaceholder("Select phase")
                        .setRequired(false)
                        .addOption("Onboarding", "0", "Unlocks Tiers 1 & 2")
                        .addOption("Distribution Platform (Phase 1)", "1", "Unlocks Tiers 3 & 4")
                        .addOption("Construction Dock (Phase 2)", "2", "Unlocks Tiers 5 & 6")
                        .addOption("Main Body (Phase 3)", "3", "Unlocks Tiers 7 & 8")
                        .addOption("Propulsion Systems (Phase 4)", "4", "Unlocks Tier 9")
                        .addOption("Assembly (Phase 5)", "5")
                        .addOption("Launch", "6")
                        .addOption("Completed", "7")
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
        ModalMapping phase = event.getValue("phase");

        NewGameData.Builder builder = NewGameData.builder(name.getAsString());
        builder.startingLocation(location.getAsStringList().getFirst());
        Map<String, String> settings = buildSettings(tier, phase);
        if (settings != null) builder.creativeModeSettings(settings);
        NewGameData newGameData = builder.build();

        event.deferReply(isDashboard(event)).queue();

        LOGGER.info("Creating new session on {}: {}", serverNameForLog(server.getName()), newGameData);

        requestAsyncWithMDC(server, "create new session on", httpsApi -> {
            httpsApi.createNewSession(newGameData);
        }).thenApplyAsync(withMDC(_ -> {
            logActionWithServer(event, "created new session " + escapeAll(newGameData.sessionName()) + " on", server.getName());
            return "Successfully created new session on " + inlineServerDisplayName(server.getName());
        })).exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
            event.getHook().editOriginal(message).queue();
        }));
    }

    private static @Nullable Map<String, String> buildSettings(@Nullable ModalMapping tier, @Nullable ModalMapping phase) {
        List<String> tierAsList = tier != null ? tier.getAsStringList() : Collections.emptyList();
        List<String> phaseAsList = phase != null ? phase.getAsStringList() : Collections.emptyList();

        Map<String, String> settings = null;
        if (!tierAsList.isEmpty() || !phaseAsList.isEmpty()) {
            settings = new HashMap<>();
            settings.put(CreativeModeSettings.STARTING_TIER, tierAsList.isEmpty() ? "0" : tierAsList.getFirst());
            settings.put(CreativeModeSettings.SET_GAME_PHASE, phaseAsList.isEmpty() ? "0" : phaseAsList.getFirst());

            // Workaround apparent bug where previous settings don't get replaced by explicitly setting values to false
            settings.put(CreativeModeSettings.NO_POWER, "False");
            settings.put(CreativeModeSettings.NO_FUEL, "False");
            settings.put(CreativeModeSettings.NO_UNLOCK_COST, "False");
            settings.put(CreativeModeSettings.UNLOCK_ALTERNATE_RECIPES_INSTANTLY, "False");
            settings.put(CreativeModeSettings.NO_BUILD_COST, "False");
            settings.put(CreativeModeSettings.GOD_MODE, "False");
            settings.put(CreativeModeSettings.FLIGHT_MODE, "False");
            settings.put(CreativeModeSettings.UNLOCK_ALL_RESEARCH, "False");
            settings.put(CreativeModeSettings.UNLOCK_ALL_IN_AWESOME_SHOP, "False");
            settings.put(CreativeModeSettings.DISABLE_ARACHNID_CREATURES, "False");
        }
        return settings;
    }

}
