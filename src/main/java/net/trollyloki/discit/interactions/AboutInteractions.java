package net.trollyloki.discit.interactions;

import net.dv8tion.jda.api.components.MessageTopLevelComponent;
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
        List<ContainerChildComponent> containerChildComponents = new ArrayList<>(3);

        containerChildComponents.add(TextDisplay.of("## About Discit"));
        if (Discit.VERSION != null)
            containerChildComponents.add(TextDisplay.of("Running version **" + Discit.VERSION + "**"));
        containerChildComponents.add(TextDisplay.of("Created by TrollyLoki"));

        List<MessageTopLevelComponent> components = new ArrayList<>(3);
        components.add(Container.of(containerChildComponents));
        components.add(ActionRow.of(
                Button.link("https://github.com/TrollyLoki/Discit", "Source"),
                Button.link("https://github.com/TrollyLoki/Discit/issues", "Report an Issue")
        ));
        if (Discit.VERSION != null) components.add(ActionRow.of(
                Button.link("https://github.com/TrollyLoki/Discit/releases/tag/v" + Discit.VERSION, "Release Notes")
        ));

        event.replyComponents(components).useComponentsV2().setEphemeral(true).queue();
    }

}
