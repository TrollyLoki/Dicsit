package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.trollyloki.discit.Discit;
import org.jspecify.annotations.NullMarked;

@NullMarked
public final class AboutInteractions {
    private AboutInteractions() {
    }

    public static final String ABOUT_COMMAND_NAME = "about";

    public static void onAboutCommand(SlashCommandInteractionEvent event) {
        event.reply("Running Discit" + (Discit.VERSION == null ? "" : " **v" + Discit.VERSION + "**"))
                .setEphemeral(true).queue();
    }

}
