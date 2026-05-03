package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.actionrow.ActionRow;
import net.dv8tion.jda.api.components.buttons.Button;
import net.dv8tion.jda.api.components.container.Container;
import net.dv8tion.jda.api.components.container.ContainerChildComponent;
import net.dv8tion.jda.api.components.textdisplay.TextDisplay;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.trollyloki.discit.Discit;
import org.jspecify.annotations.NullMarked;

import java.util.ArrayList;
import java.util.List;

@NullMarked
public final class AboutInteractions {
    private AboutInteractions() {
    }

    public static final String ABOUT_COMMAND_NAME = "about";

    public static void onAboutCommand(SlashCommandInteractionEvent event) {
        List<ContainerChildComponent> components = new ArrayList<>(3);

        components.add(TextDisplay.of("## About Discit"));
        if (Discit.VERSION != null) components.add(TextDisplay.of("Running version **" + Discit.VERSION + "**"));
        components.add(TextDisplay.of("Created by **TrollyLoki**"));

        event.replyComponents(
                Container.of(components),
                ActionRow.of(
                        Button.link("https://github.com/TrollyLoki/Discit", "Source"),
                        Button.link("https://github.com/TrollyLoki/Discit/issues", "Report an Issue")
                )
        ).useComponentsV2().setEphemeral(true).queue();
    }

}
