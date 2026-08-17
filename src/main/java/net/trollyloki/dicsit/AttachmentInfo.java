package net.trollyloki.dicsit;

import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.utils.NamedAttachmentProxy;
import org.jspecify.annotations.NullMarked;

@NullMarked
public record AttachmentInfo(String url, String fileName) {

    public AttachmentInfo(Message.Attachment attachment) {
        this(attachment.getUrl(), attachment.getFileName());
    }

    public NamedAttachmentProxy getProxy() {
        return new NamedAttachmentProxy(url, fileName);
    }

}
