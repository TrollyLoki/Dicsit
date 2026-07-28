package net.trollyloki.dicsit.interactions;

import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.components.textinput.TextInput;
import net.dv8tion.jda.api.components.textinput.TextInputStyle;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.FileUpload;
import net.trollyloki.dicsit.InteractionUtils;
import net.trollyloki.dicsit.SaveInfo;
import net.trollyloki.dicsit.Server;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

import static net.trollyloki.dicsit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.dicsit.FormattingUtils.safeMonospace;
import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.*;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public final class SaveInteractions {
    private SaveInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(SaveInteractions.class);

    public static final String
            SAVE_COMMAND_NAME = "save",
            SAVE_BUTTON_ID = "save",
            SAVE_MODAL_ID = "save";

    public static void onSaveCommand(SlashCommandInteractionEvent event) {
        Map<UUID, Server> servers = getAllServersIfAdmin(event, false, true);
        if (servers == null)
            return;

        event.replyModal(createSaveModal(servers)).queue();
    }

    public static void onSaveButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString, true);
        if (server == null)
            return;

        event.replyModal(createSaveModal(Collections.singletonMap(serverIdString, server))).queue();
    }

    private static Modal createSaveModal(Map<?, Server> servers) {
        String customId;
        ModalTopLevelComponent serverComponent;
        if (servers.size() == 1) {
            Map.Entry<?, Server> entry = servers.entrySet().iterator().next();
            customId = buildId(SAVE_MODAL_ID, entry.getKey());
            serverComponent = TextDisplay.of("Creating save on " + inlineServerDisplayName(entry.getValue().getName()));
        } else {
            customId = SAVE_MODAL_ID;
            serverComponent = Label.of("Server", serverSelectMenu("server", servers)
                    .setPlaceholder("Select a server")
                    .build());
        }

        return Modal.create(customId, "Create Save").addComponents(
                serverComponent,
                Label.of("Save Name", "Optional", TextInput.create("name", TextInputStyle.SHORT)
                        .setRequired(false)
                        .setPlaceholder("Session Name_DDMMYY-HHMMSS")
                        .setMaxLength(100) // arbitrary
                        .build())
        ).build();
    }

    public static void onSaveModal(ModalInteractionEvent event, @Nullable String serverIdString) {
        if (serverIdString == null) {
            ModalMapping serverIds = event.getValue("server");
            if (serverIds == null) {
                event.reply("Please select a server").setEphemeral(true).queue();
                return;
            }
            serverIdString = serverIds.getAsStringList().getFirst();
        }

        Server server = getServerIfAdmin(event, serverIdString, true);
        if (server == null)
            return;

        ModalMapping name = event.getValue("name");
        String saveName = name != null ? name.getAsString() : null;

        event.deferReply(isDashboard(event)).queue();

        LOGGER.info("Saving {} as \"{}\"", serverNameForLog(server.getName()), saveName);

        record SaveDownload(SaveInfo info, InputStream data) {
        }

        saveAsyncWithMDC(server, saveName).thenComposeAsync(withMDC(saveInfo -> {

            String monospaceFilename = safeMonospace(saveInfo.name() + SaveFileReader.EXTENSION);
            event.getHook().editOriginal("Downloading " + monospaceFilename + " from " + inlineServerDisplayName(server.getName()) + "...").queue();

            LOGGER.info("Downloading save \"{}\" from {}", saveInfo.name(), serverNameForLog(server.getName()));

            return requestAsyncWithMDC(server, "download " + monospaceFilename + " from", httpsApi -> {
                return new SaveDownload(saveInfo, httpsApi.downloadSave(saveInfo.name()));
            });

        })).thenAcceptAsync(withMDC(saveDownload -> {

            event.getHook().editOriginal(saveDownload.info.formatted(server.getName()))
                    .setFiles(FileUpload.fromData(saveDownload.data, saveDownload.info.name() + SaveFileReader.EXTENSION))
                    .queue(message -> logActionWithServer(event, "downloaded " + message.getAttachments().getFirst().getUrl() + " from", server.getName()));

        })).exceptionallyAsync(withMDC(throwable -> {
            event.getHook().editOriginal(InteractionUtils.exceptionMessage(throwable)).queue();
            return null;
        }));
    }

}
