package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.selections.EntitySelectMenu;
import net.dv8tion.jda.api.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.components.separator.Separator;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.entities.IMentionable;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.EntitySelectInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.interactions.Interaction;
import net.trollyloki.discit.Discit;
import net.trollyloki.discit.GuildManager;
import org.jspecify.annotations.NullMarked;

import java.time.Duration;
import java.util.function.BiConsumer;

import static net.trollyloki.discit.FormattingUtils.formatDuration;
import static net.trollyloki.discit.InteractionUtils.*;
import static net.trollyloki.discit.interactions.ListInteractions.LIST_COMMAND_NAME;

@NullMarked
public final class SettingsInteractions {
    private SettingsInteractions() {
    }

    public static final String
            SETTINGS_COMMAND_NAME = "settings",
            ADMIN_ROLE_SELECT_ID = "admin-role",
            DASHBOARD_CHANNEL_SELECT_ID = "dashboard-channel",
            LOG_CHANNEL_SELECT_ID = "log-channel",
            ALERT_ROLE_SELECT_ID = "alert-role",
            UNSET_ALERT_ROLE_BUTTON_ID = "unset-alert-role",
            DEFAULT_OFFLINE_ALERT_DELAY_SELECT_ID = "offline-alert-delay";

    private static Container settingsContainer(Interaction interaction) {
        GuildManager guildManager = getGuildManager(interaction);
        Member member = interaction.getMember();
        boolean canManageGuild = member != null && member.hasPermission(Permission.MANAGE_SERVER);

        EntitySelectMenu.Builder adminRoleSelect = EntitySelectMenu.create(ADMIN_ROLE_SELECT_ID, EntitySelectMenu.SelectTarget.ROLE);
        Role currentAdminRole = guildManager.getAdminRole();
        if (currentAdminRole != null) {
            adminRoleSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentAdminRole));
        }

        EntitySelectMenu.Builder dashboardChannelSelect = messageChannelSelect(DASHBOARD_CHANNEL_SELECT_ID);
        GuildMessageChannel currentDashboardChannel = guildManager.getDashboardChannel();
        if (currentDashboardChannel != null) {
            dashboardChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentDashboardChannel));
        }

        EntitySelectMenu.Builder logChannelSelect = messageChannelSelect(LOG_CHANNEL_SELECT_ID);
        GuildMessageChannel currentLogChannel = guildManager.getLogChannel();
        if (currentLogChannel != null) {
            logChannelSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentLogChannel));
        }

        EntitySelectMenu.Builder alertRoleSelect = EntitySelectMenu.create(ALERT_ROLE_SELECT_ID, EntitySelectMenu.SelectTarget.ROLE);
        Role currentAlertRole = guildManager.getAlertRole();
        if (currentAlertRole != null) {
            alertRoleSelect.setDefaultValues(EntitySelectMenu.DefaultValue.from(currentAlertRole));
        }

        StringSelectMenu defaultOfflineAlertDelaySelect = createAlertDelaySelectMenu(DEFAULT_OFFLINE_ALERT_DELAY_SELECT_ID, guildManager.getDefaultOfflineAlertDelay());

        String title = "## Settings";
        if (interaction.getGuild() != null) {
            title += " for " + interaction.getGuild().getName();
        }
        return Container.of(
                TextDisplay.of(title),
                Separator.createDivider(Separator.Spacing.SMALL),
                TextDisplay.of("### Administrator Role"),
                TextDisplay.of("Users with this role will have full administrator access to **all added servers**"),
                ActionRow.of(adminRoleSelect.setPlaceholder("Select a role").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Dashboard Channel"),
                TextDisplay.of("Live server statuses will be displayed in this channel"),
                ActionRow.of(dashboardChannelSelect.setPlaceholder("Select a channel").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Log Channel"),
                TextDisplay.of("A message will be sent to this channel each time an action that requires administrator access is performed"),
                ActionRow.of(logChannelSelect.setPlaceholder("Select a channel").setDisabled(!canManageGuild).build()),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Alert Role"),
                TextDisplay.of("This role will be mentioned in alerts if set"),
                ActionRow.of(alertRoleSelect.setPlaceholder("Select a role").build()),
                ActionRow.of(Button.secondary(UNSET_ALERT_ROLE_BUTTON_ID, "Unset Alert Role").withDisabled(currentAlertRole == null)),
                Separator.createInvisible(Separator.Spacing.SMALL),
                TextDisplay.of("### Default Offline Alert Delay"),
                TextDisplay.of("Newly added servers will have their offline alert delay set to this value"),
                TextDisplay.of("This setting can be changed for individual servers later via " + Discit.get().getCommand(LIST_COMMAND_NAME).getAsMention()),
                ActionRow.of(defaultOfflineAlertDelaySelect)
        );
    }

    public static void onSettingsCommand(SlashCommandInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        event.replyComponents(settingsContainer(event)).useComponentsV2().setEphemeral(true).queue();
    }

    public static void onAdminRoleSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = onEntitySelectHelper(event, GuildManager::setAdminRole);

        event.getHook().sendMessage("Administrator role set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the administrator role to " + selection.getAsMention());
    }

    public static void onDashboardChannelSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = onEntitySelectHelper(event, GuildManager::setDashboardChannel);

        event.getHook().sendMessage("Dashboard channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the dashboard channel to " + selection.getAsMention());
    }

    public static void onLogChannelSelect(EntitySelectInteractionEvent event) {
        if (cannotManageGuild(event))
            return;

        IMentionable selection = onEntitySelectHelper(event, GuildManager::setLogChannel);

        event.getHook().sendMessage("Log channel set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the log channel to " + selection.getAsMention());
    }

    public static void onAlertRoleSelect(EntitySelectInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        IMentionable selection = onEntitySelectHelper(event, GuildManager::setAlertRole);

        event.getHook().sendMessage("Alert role set to " + selection.getAsMention()).setEphemeral(true).queue();
        logAction(event, "set the alert role to " + selection.getAsMention());
    }

    public static void onUnsetAlertRoleButton(ButtonInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        getGuildManager(event).setAlertRole(null);

        event.editComponents(settingsContainer(event)).useComponentsV2().queue();

        event.getHook().sendMessage("Alert role unset").setEphemeral(true).queue();
        logAction(event, "unset the alert role");
    }

    private static IMentionable onEntitySelectHelper(EntitySelectInteractionEvent event, BiConsumer<GuildManager, String> setter) {
        IMentionable selection = event.getValues().getFirst();

        setter.accept(getGuildManager(event), selection.getId());

        event.editComponents(settingsContainer(event)).useComponentsV2().queue();

        return selection;
    }

    public static void onDefaultOfflineAlertDelaySelect(StringSelectInteractionEvent event) {
        if (isNotAdmin(event))
            return;

        Duration duration = parseAlertDelay(event.getValues().getFirst());

        getGuildManager(event).setDefaultOfflineAlertDelay(duration);

        event.editComponents(settingsContainer(event)).useComponentsV2().queue();

        if (duration != null) {
            String formatted = formatDuration(duration.toSeconds());
            event.getHook().sendMessage("Default offline alert delay set to " + formatted).setEphemeral(true).queue();
            logAction(event, "set the offline alert delay for new servers to " + formatted);
        } else {
            event.getHook().sendMessage("Default offline alerts disabled").setEphemeral(true).queue();
            logAction(event, "disabled offline alerts for new servers");
        }
    }

}
