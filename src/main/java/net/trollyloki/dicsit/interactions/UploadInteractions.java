package net.trollyloki.dicsit.interactions;

import net.dv8tion.jda.api.components.ModalTopLevelComponent;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.attachmentupload.AttachmentUpload;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.checkbox.Checkbox;
import net.dv8tion.jda.api.components.label.Label;
import net.dv8tion.jda.api.components.label.LabelChildComponent;
import net.dv8tion.jda.api.components.selections.SelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.MessageContextInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.interactions.callbacks.IDeferrableCallback;
import net.dv8tion.jda.api.interactions.callbacks.IModalCallback;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
import net.dv8tion.jda.api.interactions.modals.ModalMapping;
import net.dv8tion.jda.api.modals.Modal;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import net.trollyloki.dicsit.AttachmentInfo;
import net.trollyloki.dicsit.GuildManager;
import net.trollyloki.dicsit.InteractionUtils;
import net.trollyloki.dicsit.Server;
import net.trollyloki.dicsit.interactions.cache.AutoKeyedCache;
import net.trollyloki.dicsit.interactions.cache.ModalOptionCache;
import net.trollyloki.jicsit.save.SaveFileReader;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

import static net.trollyloki.dicsit.FormattingUtils.inlineServerDisplayName;
import static net.trollyloki.dicsit.FormattingUtils.safeMonospace;
import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.*;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.LoggingUtils.withMDC;

@NullMarked
public final class UploadInteractions {
    private UploadInteractions() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger(UploadInteractions.class);

    public static final String
            UPLOAD_CONTEXT_COMMAND_NAME = "Upload save file",
            UPLOAD_COMMAND_NAME = "upload",
            UPLOAD_BUTTON_ID = "upload",
            UPLOAD_MODAL_ID = "upload",
            UPLOAD_CANCEL_BUTTON_ID = "upload-cancel",
            UPLOAD_CONFIRM_BUTTON_ID = "upload-confirm",
            UPLOAD_AND_DEFER_LOAD_BUTTON_ID = "upload-defer";

    private record UploadInfo(AttachmentInfo attachmentInfo, List<String> serverIdStrings) {
    }

    private static final ModalOptionCache<AttachmentInfo> ATTACHMENT_CACHE = new ModalOptionCache<>();
    private static final AutoKeyedCache<UploadInfo> UPLOAD_INFO_CACHE = new AutoKeyedCache<>();

    public static void onUploadFromMessage(MessageContextInteractionEvent event) {
        List<Message.Attachment> attachments = findSaveFileAttachments(event);
        if (attachments.isEmpty()) {
            event.reply("Could not find any save files attached to that message").setEphemeral(true).queue();
            return;
        }

        ATTACHMENT_CACHE.put(event.getUser(), attachments.stream().map(AttachmentInfo::new).toList());

        onUploadHelper(event, event, customId -> {
            StringSelectMenu.Builder builder = StringSelectMenu.create(customId);
            for (int i = 0; i < attachments.size(); i++) {
                builder.addOption(attachments.get(i).getFileName(), Integer.toString(i));
            }
            return builder.setDefaultValues("0").build();
        });
    }

    public static void onUploadCommand(SlashCommandInteractionEvent event) {
        onUploadHelper(event, event, AttachmentUpload::of);
    }

    // There's no common interface combining just IReplyCallback and IModalCallback
    private static void onUploadHelper(IReplyCallback replyCallback, IModalCallback modalCallback, Function<String, LabelChildComponent> saveFileComponentCreator) {
        Map<UUID, Server> servers = getAllServersIfAdmin(replyCallback, false);
        if (servers == null)
            return;

        modalCallback.replyModal(createUploadModal(servers, saveFileComponentCreator)).queue();
    }

    public static void onUploadButton(ButtonInteractionEvent event, String serverIdString) {
        Server server = getServerIfAdmin(event, serverIdString);
        if (server == null)
            return;

        event.replyModal(createUploadModal(Collections.singletonMap(serverIdString, server), AttachmentUpload::of)).queue();
    }

    private static Modal createUploadModal(Map<?, Server> servers, Function<String, LabelChildComponent> saveFileComponentCreator) {
        String customId;
        ModalTopLevelComponent serversComponent;
        if (servers.size() == 1) {
            Map.Entry<?, Server> entry = servers.entrySet().iterator().next();
            customId = buildId(UPLOAD_MODAL_ID, entry.getKey());
            serversComponent = TextDisplay.of("Uploading save to " + inlineServerDisplayName(entry.getValue().getName()));
        } else {
            customId = UPLOAD_MODAL_ID;
            serversComponent = Label.of("Servers", "The server(s) that the save should be uploaded to", serverSelectMenu("servers", servers, false)
                    .setMaxValues(SelectMenu.OPTIONS_MAX_AMOUNT)
                    .setPlaceholder("Select one or more servers")
                    .build());
        }

        return Modal.create(customId, "Upload Save").addComponents(
                serversComponent,
                Label.of("Save File", saveFileComponentCreator.apply("save")),
                Label.of("Load", Checkbox.of("load", true))
        ).build();
    }

    public static void onUploadModal(ModalInteractionEvent event, @Nullable String fixedServerIdString) {
        List<String> serverIdStrings;
        if (fixedServerIdString != null) {

            //noinspection NullableProblems: fixedServerIdString is non-null here
            serverIdStrings = Collections.singletonList(fixedServerIdString);

        } else {

            ModalMapping serverIds = event.getValue("servers");
            if (serverIds == null) {
                event.reply("Please select servers").setEphemeral(true).queue();
                return;
            }
            serverIdStrings = serverIds.getAsStringList();

        }

        List<Server> servers = getServersIfAdmin(event, serverIdStrings);
        if (servers == null)
            return;

        ModalMapping save = event.getValue("save");
        if (save == null) {
            event.reply("Please select a save file").setEphemeral(true).queue();
            return;
        }

        AttachmentInfo attachmentInfo;
        switch (save.getType()) {
            case FILE_UPLOAD -> {
                Message.Attachment attachment = save.getAsAttachmentList().getFirst();
                if (!isSaveFile(attachment)) {
                    event.reply(attachment.getUrl() + " is not a save file").setEphemeral(true).queue();
                    return;
                }
                attachmentInfo = new AttachmentInfo(attachment);
            }
            case STRING_SELECT -> {
                attachmentInfo = ATTACHMENT_CACHE.pop(event.getUser(), Integer.parseInt(save.getAsStringList().getFirst()));
                if (attachmentInfo == null) {
                    event.reply("Attachment context expired, please try again").setEphemeral(true).queue();
                    return;
                }
            }
            default -> {
                LOGGER.error("Unexpected save file component type: {}", save.getType());
                return;
            }
        }

        ModalMapping load = event.getValue("load");

        event.deferReply(isDashboard(event)).queue();

        if (load == null || !load.getAsBoolean()) {
            // Skip player check when just uploading
            uploadSave(event, attachmentInfo, serverIdStrings, servers, false, false);
            return;
        }

        checkForPlayersAsyncWithMDC(servers).thenAcceptAsync(withMDC(message -> {
            if (message == null) {
                // Skip confirmation if no players are connected
                uploadSave(event, attachmentInfo, serverIdStrings, servers, true, false);
                return;
            }

            UUID key = UPLOAD_INFO_CACHE.put(new UploadInfo(attachmentInfo, serverIdStrings));
            event.getHook().editOriginal(message).setComponents(ActionRow.of(
                    Button.primary(buildId(UPLOAD_AND_DEFER_LOAD_BUTTON_ID, event.getUser().getId(), key), "Upload and Defer Load"),
                    Button.danger(buildId(UPLOAD_CONFIRM_BUTTON_ID, event.getUser().getId(), key), "Load Anyway"),
                    Button.secondary(buildId(UPLOAD_CANCEL_BUTTON_ID, event.getUser().getId(), key), "Cancel")
            )).queue();
        }));
    }

    public static void onUploadCancelButton(ButtonInteractionEvent event, String userId, String keyString) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        UPLOAD_INFO_CACHE.pop(UUID.fromString(keyString));

        event.deferEdit().queue();
        event.getHook().deleteOriginal().queue();
    }

    public static void onUploadConfirmButton(ButtonInteractionEvent event, String userId, String keyString, boolean deferLoad) {
        if (!event.getUser().getId().equals(userId)) {
            // Ignore
            event.deferEdit().queue();
            return;
        }

        UploadInfo uploadInfo = UPLOAD_INFO_CACHE.pop(UUID.fromString(keyString));
        if (uploadInfo == null) {
            event.deferEdit().queue();
            event.getHook().deleteOriginal().queue();
            event.getHook().sendMessage("Context expired, please try again").setEphemeral(true).queue();
            return;
        }

        List<Server> servers = getServersIfAdmin(event, uploadInfo.serverIdStrings);
        if (servers == null)
            return;

        event.deferEdit().queue();
        uploadSave(event, uploadInfo.attachmentInfo, uploadInfo.serverIdStrings, servers, !deferLoad, deferLoad);
    }

    private static void uploadSave(IDeferrableCallback callback, AttachmentInfo attachmentInfo, List<String> serverIdStrings, List<Server> servers, boolean load, boolean deferLoad) {
        NamedAttachmentProxy attachment = attachmentInfo.getProxy();
        String monospaceFilename = safeMonospace(attachment.getFileName());
        String saveName = SaveFileReader.saveNameOf(attachment.getFileName());
        splitAndConsumeAttachment(callback.getHook(), attachment, servers.size(), (uploadStreams, uploadExecutor) -> {

            List<String> messageLines = Collections.synchronizedList(servers.stream()
                    .map(server -> "Uploading " + monospaceFilename + " to " + inlineServerDisplayName(server.getName()) + "...")
                    .collect(Collectors.toList())
            );
            // No need to synchronize here, the list won't be changing yet
            callback.getHook().editOriginal(String.join("\n", messageLines))
                    .setComponents(Collections.emptySet()).queue();

            GuildManager guildManager = getGuildManager(callback);
            for (int i = 0; i < servers.size(); i++) {
                final int index = i;
                Server server = servers.get(index);
                UUID serverId = UUID.fromString(serverIdStrings.get(index));

                LOGGER.info("Uploading save \"{}\" to {}", saveName, serverNameForLog(server.getName()));

                CompletableFuture<@Nullable Void> uploadFuture = requestAsyncWithMDC(server, "upload " + monospaceFilename + " to", httpsApi -> {
                    try (InputStream uploadStream = uploadStreams[index]) {
                        httpsApi.uploadSave(uploadStream, saveName, false, false);
                    } catch (IOException e) {
                        throw new CompletionException(e);
                    }
                }, uploadExecutor).thenRunAsync(withMDC(() -> logActionWithServer(callback, "uploaded " + attachment.getUrl() + " to", server.getName())));

                CompletableFuture<String> responseFuture;
                if (load) {
                    responseFuture = uploadFuture.thenComposeAsync(withMDC(_ -> {

                        // Edit message with intermediate progress update
                        messageLines.set(index, "Loading " + monospaceFilename + " on " + inlineServerDisplayName(server.getName()) + "...");
                        synchronized (messageLines) {
                            callback.getHook().editOriginal(String.join("\n", messageLines)).queue();
                        }

                        LOGGER.info("Loading save \"{}\" on {}", saveName, serverNameForLog(server.getName()));

                        return requestAsyncWithMDC(server, "load " + monospaceFilename + " on", httpsApi -> {
                            httpsApi.loadSave(saveName, false);
                        });
                    })).thenApplyAsync(withMDC(_ -> {

                        // Cancel previously deferred loads
                        guildManager.cancelDeferredLoad(serverId);

                        logActionWithServer(callback, "loaded " + monospaceFilename + " on", server.getName());
                        return "Successfully loaded " + monospaceFilename + " on " + inlineServerDisplayName(server.getName());

                    }));
                } else if (deferLoad) {
                    responseFuture = uploadFuture.thenApplyAsync(withMDC(_ -> {

                        // Defer load
                        guildManager.deferLoad(serverId, saveName);

                        logActionWithServer(callback, "deferred loading " + monospaceFilename + " on", server.getName());
                        return monospaceFilename + " will be loaded on " + inlineServerDisplayName(server.getName()) + " when there are no players connected";

                    }));
                } else {
                    responseFuture = uploadFuture.thenApplyAsync(withMDC(_ -> "Successfully uploaded " + monospaceFilename + " to " + inlineServerDisplayName(server.getName())));
                }
                responseFuture.exceptionally(withMDC(InteractionUtils::exceptionMessage)).thenAcceptAsync(withMDC(message -> {
                    messageLines.set(index, message);
                    synchronized (messageLines) {
                        callback.getHook().editOriginal(String.join("\n", messageLines)).queue();
                    }
                }));
            }
        });
    }

}
