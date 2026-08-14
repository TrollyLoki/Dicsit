package net.trollyloki.dicsit.monitoring;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.actionrow.ActionRowChildComponent;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.buttons.ButtonStyle;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.TimeFormat;
import net.dv8tion.jda.api.utils.Timestamp;
import net.trollyloki.dicsit.GuildManager;
import net.trollyloki.dicsit.interactions.ChangePasswordInteractions;
import net.trollyloki.jicsit.save.SaveFileReader;
import net.trollyloki.jicsit.server.https.ServerGameState;
import net.trollyloki.jicsit.server.query.ServerStatus;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

import static net.trollyloki.dicsit.FormattingUtils.buildLink;
import static net.trollyloki.dicsit.FormattingUtils.escapeAll;
import static net.trollyloki.dicsit.FormattingUtils.escapedServerName;
import static net.trollyloki.dicsit.FormattingUtils.formatGameDuration;
import static net.trollyloki.dicsit.InteractionListener.DASHBOARD_REFRESH_BUTTON_ID;
import static net.trollyloki.dicsit.InteractionListener.buildId;
import static net.trollyloki.dicsit.InteractionUtils.GREEN_ACCENT;
import static net.trollyloki.dicsit.InteractionUtils.RED_ACCENT;
import static net.trollyloki.dicsit.InteractionUtils.YELLOW_ACCENT;
import static net.trollyloki.dicsit.LoggingUtils.serverNameForLog;
import static net.trollyloki.dicsit.interactions.AddInteractions.CLAIM_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ChangePasswordInteractions.CHANGE_PASSWORD_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.CreativeModeInteractions.CREATIVE_MODE_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.DeferredActionsInteractions.CANCEL_DEFERRED_LOAD_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.DeferredActionsInteractions.CANCEL_DEFERRED_RELOAD_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.DeferredActionsInteractions.CANCEL_DEFERRED_RESTART_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.InvalidateTokensInteractions.INVALIDATE_TOKENS_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ListInteractions.AUTHENTICATE_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.NewSessionInteractions.NEW_SESSION_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ReloadInteractions.RELOAD_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ReloadInteractions.RESTART_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.RenameInteractions.RENAME_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.SaveInteractions.SAVE_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.ServerOptionsInteractions.SERVER_OPTIONS_BUTTON_ID;
import static net.trollyloki.dicsit.interactions.UploadInteractions.UPLOAD_BUTTON_ID;

@NullMarked
public class ServerInfoCache {

    private static final Logger LOGGER = LoggerFactory.getLogger(ServerInfoCache.class);

    private final UUID serverId;

    private final DashboardUpdater dashboardUpdater;

    private @Nullable String name;
    private int build;
    private @Nullable ServerStatus status;
    private @Nullable String message;
    private @Nullable ServerGameState gameState;
    private @Nullable Duration ping;

    private @Nullable Timestamp lastUpdated;

    private boolean authenticated;
    private boolean disableSaving;
    private @Nullable String deferredLoadSaveName;
    private boolean deferredReload;
    private boolean deferredRestart;

    public ServerInfoCache(GuildManager guildManager, UUID serverId, boolean authenticated, boolean disableSaving, @Nullable String deferredLoadSaveName, boolean deferredReload, boolean deferredRestart) {
        this.serverId = serverId;

        this.dashboardUpdater = new DashboardUpdater(guildManager, serverId);

        this.authenticated = authenticated;
        this.disableSaving = disableSaving;
        this.deferredLoadSaveName = deferredLoadSaveName;
        this.deferredReload = deferredReload;
        this.deferredRestart = deferredRestart;
    }

    public synchronized void setInfo(@Nullable String name, int build, ServerStatus status, @Nullable String message, @Nullable ServerGameState gameState, @Nullable Duration ping) {
        if (Objects.equals(name, this.name)
                && build == this.build
                && status == this.status
                && Objects.equals(message, this.message)
                && Objects.equals(gameState, this.gameState)
        ) return;

        LOGGER.info("New server info for {}: \"{}\" {} {}", serverNameForLog(name), status, message == null ? null : '"' + message + '"', gameState);

        this.name = name;
        this.build = build;
        this.status = status;
        this.message = message;
        this.gameState = gameState;
        this.ping = ping;

        this.lastUpdated = TimeFormat.RELATIVE.now();

        updateDashboardMessage();
    }

    public synchronized void setAuthenticated(boolean authenticated) {
        if (authenticated == this.authenticated)
            return;

        this.authenticated = authenticated;

        updateDashboardMessage();
    }

    public synchronized void setDisableSaving(boolean disableSaving) {
        if (disableSaving == this.disableSaving)
            return;

        this.disableSaving = disableSaving;

        updateDashboardMessage();
    }

    public synchronized void setDeferredLoadSaveName(@Nullable String deferredLoadSaveName) {
        if (Objects.equals(deferredLoadSaveName, this.deferredLoadSaveName))
            return;

        this.deferredLoadSaveName = deferredLoadSaveName;

        updateDashboardMessage();
    }

    public synchronized void setDeferredReload(boolean deferredReload) {
        if (deferredReload == this.deferredReload)
            return;

        this.deferredReload = deferredReload;

        updateDashboardMessage();
    }

    public synchronized void setDeferredRestart(boolean deferredRestart) {
        if (deferredRestart == this.deferredRestart)
            return;

        this.deferredRestart = deferredRestart;

        updateDashboardMessage();
    }

    private void updateDashboardMessage() {
        dashboardUpdater.updateMessage(createContainer());
    }

    public synchronized void shutdown() {
        dashboardUpdater.shutdown();
    }

    private Container createContainer() {
        List<ContainerChildComponent> components = new ArrayList<>();

        components.add(TextDisplay.of("## " + escapedServerName(name)));

        String statusString = status != null ? status.toString() : "Unknown";
        if (gameState != null && gameState.isGamePaused()) {
            statusString += " – Paused";
        }
        components.add(TextDisplay.of(statusString));

        if (message != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of(Emoji.fromUnicode("⚠️").getFormatted() + " " + message));
        }

        if (gameState != null) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("### " + escapeAll(gameState.activeSessionName())));
            components.add(TextDisplay.of("**Game Duration**\n" + formatGameDuration(gameState.totalGameDuration())));
            components.add(TextDisplay.of("### Current Players\n" + gameState.connectedPlayerCount() + "/" + gameState.playerLimit()));
            components.add(TextDisplay.of("### Average Tick Rate\n" + gameState.averageTickRate()));
        }

        components.add(Separator.createDivider(Separator.Spacing.SMALL));
        String subtext = "-# Last updated " + lastUpdated;
        if (ping != null) {
            subtext += String.format(" (%,d ms)", ping.toMillis());
        }
        if (build > 0) {
            subtext += " – " + buildLink("Build ", build);
        }
        components.add(TextDisplay.of(subtext));

        if (status.isHttpsApiAvailable()) {
            List<ActionRowChildComponent> primaryRow = new ArrayList<>(4);
            List<ActionRowChildComponent> gameRow = new ArrayList<>(2);
            List<ActionRowChildComponent> optionsRow = new ArrayList<>(2);
            List<ActionRowChildComponent> passwordsRow = new ArrayList<>(3);

            boolean playing = status == ServerStatus.PLAYING;
            if (playing) {
                primaryRow.add(Button.success(buildId(DASHBOARD_REFRESH_BUTTON_ID, serverId), "Refresh"));
            }

            if (!authenticated) {
                if (name == null || name.isEmpty()) {
                    primaryRow.add(Button.primary(buildId(CLAIM_BUTTON_ID, serverId, false), "Claim"));
                }
                primaryRow.add(Button.secondary(buildId(AUTHENTICATE_BUTTON_ID, serverId), "Authenticate"));
            } else {
                ButtonStyle uploadButtonStyle = ButtonStyle.PRIMARY;
                if (playing && !disableSaving) {
                    uploadButtonStyle = ButtonStyle.SECONDARY;
                    primaryRow.add(Button.primary(buildId(RELOAD_BUTTON_ID, serverId), "Reload Session"));
                    primaryRow.add(Button.secondary(buildId(SAVE_BUTTON_ID, serverId), "Download Save").withEmoji(Emoji.fromUnicode("💾")));
                }
                primaryRow.add(Button.of(uploadButtonStyle, buildId(UPLOAD_BUTTON_ID, serverId), "Upload Save").withEmoji(Emoji.fromUnicode("📡")));

                gameRow.add(Button.secondary(buildId(NEW_SESSION_BUTTON_ID, serverId), "New Session").withEmoji(Emoji.fromUnicode("🚀")));
                if (playing) {
                    gameRow.add(Button.secondary(buildId(CREATIVE_MODE_BUTTON_ID, serverId), "Creative Mode").withEmoji(Emoji.fromUnicode("✏️")));
                }

                optionsRow.add(Button.secondary(buildId(RENAME_BUTTON_ID, serverId), "Rename Server").withEmoji(Emoji.fromUnicode("🪧")));
                optionsRow.add(Button.secondary(buildId(SERVER_OPTIONS_BUTTON_ID, serverId), "Server Options").withEmoji(Emoji.fromUnicode("⚙️")));
                optionsRow.add(Button.secondary(buildId(RESTART_BUTTON_ID, serverId), "Restart Server").withEmoji(Emoji.fromUnicode("⏲️")));

                passwordsRow.add(changePasswordButton(ChangePasswordInteractions.PasswordType.CLIENT).withEmoji(Emoji.fromUnicode("🔓")));
                passwordsRow.add(changePasswordButton(ChangePasswordInteractions.PasswordType.ADMIN).withEmoji(Emoji.fromUnicode("🔐")));
                passwordsRow.add(Button.danger(buildId(INVALIDATE_TOKENS_BUTTON_ID, serverId), "Invalidate Tokens"));
            }

            components.add(ActionRow.of(primaryRow));
            if (!gameRow.isEmpty()) components.add(ActionRow.of(gameRow));
            if (!optionsRow.isEmpty()) components.add(ActionRow.of(optionsRow));
            if (!passwordsRow.isEmpty()) components.add(ActionRow.of(passwordsRow));
        }

        List<Button> deferredActionButtons = new ArrayList<>(3);
        if (deferredRestart) {
            deferredActionButtons.add(Button.secondary(buildId(CANCEL_DEFERRED_RESTART_BUTTON_ID, serverId, false), "Cancel Restarting"));
        }
        if (deferredReload) {
            deferredActionButtons.add(Button.secondary(buildId(CANCEL_DEFERRED_RELOAD_BUTTON_ID, serverId, false), "Cancel Reloading"));
        }
        if (deferredLoadSaveName != null) {
            deferredActionButtons.add(Button.secondary(buildId(CANCEL_DEFERRED_LOAD_BUTTON_ID, serverId, false), "Cancel Loading " + deferredLoadSaveName + SaveFileReader.EXTENSION));
        }
        if (!deferredActionButtons.isEmpty()) {
            components.add(Separator.createDivider(Separator.Spacing.SMALL));
            components.add(TextDisplay.of("### Deferred Actions"));
            components.add(ActionRow.of(deferredActionButtons));
        }

        return Container.of(components).withAccentColor(switch (status) {
            case OFFLINE -> RED_ACCENT;
            case IDLE, LOADING -> YELLOW_ACCENT;
            case PLAYING -> GREEN_ACCENT;
        });
    }

    private Button changePasswordButton(ChangePasswordInteractions.PasswordType type) {
        return Button.secondary(
                buildId(CHANGE_PASSWORD_BUTTON_ID, serverId, type.name().toLowerCase(Locale.ROOT)),
                "Change " + type
        );
    }

}
